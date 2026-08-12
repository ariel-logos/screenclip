package dev.screenclip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors

/** What has to get off the screen before the shot is worth taking. */
enum class Settle {
    /** The notification shade / QS panel the tile was tapped from. */
    SHADE,

    /** Our own trampoline activity, launched from the launcher icon. */
    OUR_UI,
}

/**
 * Captures the screen with no consent prompt at all.
 *
 * The whole reason this service exists is [takeScreenshot]; it observes window events
 * only to know when the shade has finished collapsing, and never reads window content.
 */
class ShotService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenClip"

        /**
         * AOSP rejects a screenshot while (uptime - lastAccepted) <= 333, so 334 is the
         * first legal instant. Slack, because our clock sample and the server's sit on
         * opposite sides of a binder call.
         */
        private const val MIN_INTERVAL_MS = 394L

        /** Nothing guarantees the callback ever arrives; a wedged flag jams the tile. */
        private const val CALLBACK_TIMEOUT_MS = 4000L

        /** Same constant as the proven MediaProjection path. */
        private const val QUIET_MS = 120L
        private const val SETTLE_DEADLINE_MS = 1400L
        private const val BLIND_DELAY_MS = 450L

        /**
         * Earliest a shade capture may commit, measured from the dismiss request.
         *
         * The window predicate is necessary but not sufficient: this ROM blurs the
         * wallpaper behind Quick Settings, and the blur is still animating out after
         * the shade window has already shrunk — so a purely predicate-driven capture
         * freezes a blurred screen. Measured clean by ~150 ms after collapse; 450 ms
         * is comfortable margin and still reads as instant.
         */
        private const val SHADE_FLOOR_MS = 450L

        private const val SYSTEM_UI = "com.android.systemui"

        @Volatile
        var instance: ShotService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Deliberately not the main executor: copy() on a hardware bitmap is a synchronous
     * RenderThread readback of ~12 MB, and files a StrictMode slow-call on the main thread.
     */
    private val readback = Executors.newSingleThreadExecutor { Thread(it, "screenclip-readback") }

    private val windowManager by lazy { getSystemService(WindowManager::class.java) }

    private var lastAccepted = 0L
    private var inFlight = false
    private var blind = false

    override fun onServiceConnected() {
        instance = this
        // Without FLAG_RETRIEVE_INTERACTIVE_WINDOWS getWindows() returns empty, the
        // settle predicate reads "clear" immediately, and we photograph the shade —
        // with no exception anywhere to explain it.
        blind = serviceInfo.flags and
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0
        if (blind) Log.e(TAG, "no interactive windows: settle watcher is blind, using a delay")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        CaptureSession.cancelLive(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        readback.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---- capture -----------------------------------------------------------

    /** Delivers exactly once, on the main thread. */
    fun capture(settle: Settle, done: (Frozen?, String?) -> Unit) {
        if (inFlight) {
            done(null, null)
            return
        }
        inFlight = true
        val finish = { frozen: Frozen?, message: String? ->
            inFlight = false
            done(frozen, message)
        }

        if (settle == Settle.SHADE) dismissShade()
        awaitSettle(settle) { takeShot(finish) }
    }

    private fun dismissShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
    }

    /**
     * The accessibility analogue of the projection path's quiet period: wait for the
     * screen to actually be what we want to photograph, rather than guessing a delay.
     */
    private fun awaitSettle(settle: Settle, onSettled: () -> Unit) {
        if (blind) {
            main.postDelayed(onSettled, BLIND_DELAY_MS)
            return
        }
        val start = SystemClock.uptimeMillis()
        val floor = if (settle == Settle.SHADE) SHADE_FLOOR_MS else 0L
        var clearSince = 0L
        var escalated = false
        var logged = false
        val tick = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                if (isClear(settle)) {
                    if (clearSince == 0L) {
                        clearSince = now
                        if (!logged) {
                            logged = true
                            Log.i(TAG, "settle: predicate clear at ${now - start}ms")
                        }
                    }
                    if (now - clearSince >= QUIET_MS && now - start >= floor) {
                        Log.i(TAG, "settle: committing at ${now - start}ms")
                        onSettled()
                        return
                    }
                } else {
                    clearSince = 0L
                }
                if (now - start >= SETTLE_DEADLINE_MS) {
                    // Same rule as giveUp: commit rather than fail.
                    onSettled()
                    return
                }
                if (!escalated && now - start >= SETTLE_DEADLINE_MS / 2) {
                    escalated = true
                    // An expanded shade holds focus, and AOSP collapses it on back.
                    if (settle == Settle.SHADE) performGlobalAction(GLOBAL_ACTION_BACK)
                }
                main.postDelayed(this, 32)
            }
        }
        main.post(tick)
    }

    private fun isClear(settle: Settle): Boolean {
        val half = windowManager.currentWindowMetrics.bounds.height() / 2
        val rect = Rect()
        val windows = runCatching { windows }.getOrNull() ?: return false
        return when (settle) {
            // Matched by owner as well as by type: the panel is Window{NotificationShade}
            // owned by com.android.systemui, and relying on TYPE_SYSTEM alone leaves the
            // predicate blind if this ROM classifies it differently.
            Settle.SHADE -> windows.none { w ->
                val ours = w.root?.packageName
                val systemish = w.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    ours == SYSTEM_UI
                // getBoundsInScreen is the touchable region, so an expanded shade is
                // full height and a collapsed one shrinks back to the status strip.
                systemish && ours != packageName &&
                    rect.also { w.getBoundsInScreen(it) }.height() > half
            }

            Settle.OUR_UI -> CaptureSignals.uiHidden &&
                windows.none { it.root?.packageName == packageName }
        }
    }

    private fun takeShot(done: (Frozen?, String?) -> Unit) {
        // Swallow the rate limit rather than surfacing it: a user double-tapping the
        // tile deserves one screenshot, not an error.
        val wait = MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastAccepted)
        if (lastAccepted != 0L && wait > 0) {
            main.postDelayed({ takeShot(done) }, wait)
            return
        }
        lastAccepted = SystemClock.uptimeMillis()

        var handled = false
        val timeout = Runnable {
            if (!handled) {
                handled = true
                done(null, getString(R.string.no_frame))
            }
        }
        main.postDelayed(timeout, CALLBACK_TIMEOUT_MS)

        val settle: (Frozen?, String?) -> Unit = { frozen, message ->
            main.post {
                if (handled) {
                    frozen?.bitmap?.recycle()
                } else {
                    handled = true
                    main.removeCallbacks(timeout)
                    done(frozen, message)
                }
            }
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                readback,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bitmap = toSoftware(screenshot)
                        if (bitmap == null) {
                            settle(null, getString(R.string.no_frame))
                        } else if (looksBlacked(bitmap)) {
                            bitmap.recycle()
                            settle(null, getString(R.string.protected_content))
                        } else {
                            settle(Frozen(bitmap, Rect(0, 0, bitmap.width, bitmap.height)), null)
                        }
                    }

                    override fun onFailure(errorCode: Int) =
                        settle(null, messageFor(errorCode))
                },
            )
        } catch (e: SecurityException) {
            // canTakeScreenshot missing from the service XML throws synchronously —
            // the binder call is not oneway — instead of reporting error code 2.
            Log.e(TAG, "takeScreenshot refused", e)
            main.removeCallbacks(timeout)
            handled = true
            done(null, getString(R.string.no_frame))
        }
    }

    private fun messageFor(errorCode: Int): String = when (errorCode) {
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> getString(R.string.restricted_setting)
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> getString(R.string.too_fast)
        else -> getString(R.string.no_frame)
    }

    private fun toSoftware(result: ScreenshotResult): Bitmap? {
        val buffer = result.hardwareBuffer
        // Wrap first, close second: the Bitmap takes its own reference on the buffer.
        // Closing earlier throws; never closing leaks ~12 MB of graphics memory per
        // shot, in a process that now stays alive indefinitely.
        val hardware = try {
            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
        } finally {
            buffer.close()
        } ?: return null
        // Config.HARDWARE throws in the crop's software Canvas and in getPixels().
        // The P3 tag is preserved here and converted to sRGB by cropFrom's destination.
        val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
        hardware.recycle()
        return software
    }

    /**
     * Unlike a mirror, an accessibility screenshot of protected content succeeds and
     * comes back *opaque black* — SurfaceFlinger blacks it out with alpha 1.0 — so the
     * projection path's transparency test cannot detect it.
     *
     * Only the middle band is sampled: the status and navigation bars are never secure,
     * and testing the full frame would be defeated by the clock alone.
     */
    private fun looksBlacked(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        val top = bitmap.height / 5
        val span = bitmap.height * 3 / 5
        for (i in 0 until 8) {
            bitmap.getPixels(row, 0, bitmap.width, 0, top + span * i / 7, bitmap.width, 1)
            for (pixel in row) if (pixel and 0x00FFFFFF != 0) return false
        }
        return true
    }
}
