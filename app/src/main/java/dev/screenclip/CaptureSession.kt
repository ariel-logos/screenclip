package dev.screenclip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Everything from "I have a screenshot" onward: the overlay, the adjustable crop, and
 * the copy/save actions. Shared verbatim by both capture paths, so the accessibility
 * route inherits the pipeline already proven on device.
 */
class CaptureSession private constructor(
    private val ctx: Context,
    private val frozen: Frozen,
    private val onEnded: () -> Unit,
) {

    private enum class Phase { ADJUSTING, SAVING, DONE }

    companion object {
        private const val TAG = "ScreenClip"
        private const val WATCHDOG_MS = 300_000L
        private const val CACHE_TTL_MS = 60L * 60L * 1000L

        /**
         * At most one session, process-wide. Without this a second trigger would
         * screenshot our own overlay and bake the scrim and buttons into the frame —
         * the MediaProjection path is structurally immune, the accessibility path is not.
         */
        @Volatile
        private var live: CaptureSession? = null

        val isActive: Boolean get() = live != null

        /** Returns null when a session is already running; the caller owns the bitmap then. */
        fun begin(ctx: Context, frozen: Frozen, onEnded: () -> Unit): CaptureSession? {
            if (live != null) return null
            val session = CaptureSession(ctx, frozen, onEnded)
            live = session
            session.start()
            return session
        }

        fun cancelLive(message: String?) {
            live?.finish(message)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val windowManager = ctx.getSystemService(WindowManager::class.java)

    private var phase = Phase.ADJUSTING
    private var overlay: OverlayRoot? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (phase != Phase.ADJUSTING) return
            val now = windowManager.currentWindowMetrics.bounds
            if (now.width() != frozen.bounds.width() || now.height() != frozen.bounds.height()) {
                // Unrecoverable: the frozen pixels describe the old geometry, so any
                // crop would be of the wrong content.
                finish(ctx.getString(R.string.rotated))
            }
        }

        override fun onLowMemory() = Unit
    }

    private fun start() {
        ctx.applicationContext.registerComponentCallbacks(configCallback)
        main.postDelayed(watchdog, WATCHDOG_MS)
        io.execute { pruneCache() }
        showOverlay()
    }

    private val watchdog = Runnable {
        if (phase != Phase.DONE) finish(ctx.getString(R.string.timed_out))
    }

    // ---- overlay -----------------------------------------------------------

    private fun showOverlay() {
        val root = OverlayRoot(ctx, frozen.bitmap)
        root.onAction = ::runAction
        root.onCancel = { finish(ctx.getString(R.string.cancelled)) }
        // removeView is asynchronous, so the bitmap can only be released once the view
        // has genuinely detached and can no longer draw.
        root.onDetached = { frozen.bitmap.recycle() }

        val params = WindowManager.LayoutParams(
            frozen.bounds.width(),
            frozen.bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_FOCUSABLE stays absent: the window must take focus for the
            // clipboard read-back and the back key.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        windowManager.addView(root, params)
        overlay = root
        overlayParams = params
    }

    /**
     * Instant visual dismissal; the window stays attached, VISIBLE and focusable.
     *
     * Note this leaves an alpha-0 window on screen, which still counts as *obscuring*
     * for touch filtering — so never send the user to a Settings permission screen
     * without detaching first, or Settings will silently discard their tap.
     */
    private fun hideOverlay() {
        val root = overlay ?: return
        val params = overlayParams ?: return
        params.alpha = 0f
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun detachOverlay() {
        val root = overlay ?: return
        overlay = null
        overlayParams = null
        // onDetached does the recycling; if the view never attached, do it here.
        runCatching { windowManager.removeView(root) }
            .onFailure { if (!frozen.bitmap.isRecycled) frozen.bitmap.recycle() }
    }

    // ---- actions -----------------------------------------------------------

    private fun runAction(action: Action) {
        if (phase != Phase.ADJUSTING) return
        val root = overlay ?: return
        val crop = root.frameCrop() ?: run {
            finish(ctx.getString(R.string.offscreen))
            return
        }
        phase = Phase.SAVING
        root.setBusy()
        // Synchronously on main, before the overlay can detach and recycle the source.
        val shot = cropFrom(frozen.bitmap, crop)
        hideOverlay()

        val wantsCopy = action != Action.SAVE
        io.execute {
            val png = ByteArrayOutputStream().use { out ->
                shot.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            shot.recycle()
            val file = if (wantsCopy) writeCacheCopy(png) else null
            main.post { deliver(action, png, file, crop) }
        }
    }

    private fun deliver(action: Action, png: ByteArray, file: File?, crop: Rect) {
        if (phase != Phase.SAVING) return

        if (file != null) {
            // Log the failure: a throw here used to be swallowed before the read-back
            // line ran, so a broken copy toasted success and left nothing in logcat.
            runCatching { copyToClipboard(file, crop) }
                .onFailure { Log.w(TAG, "clipboard write threw", it) }
        }
        // Focus was only needed for the clipboard, so the window can go now.
        detachOverlay()

        if (action == Action.COPY) {
            finish(ctx.getString(R.string.copied, crop.width(), crop.height()))
            return
        }

        io.execute {
            val saved = runCatching { ctx.saveToGallery(png) }
            main.post {
                val message = if (saved.isSuccess) {
                    if (action == Action.COPY_AND_SAVE) {
                        ctx.getString(
                            R.string.copied_and_saved, crop.width(), crop.height(), galleryFolder,
                        )
                    } else {
                        ctx.getString(R.string.saved, crop.width(), crop.height(), galleryFolder)
                    }
                } else {
                    Log.w(TAG, "gallery save failed", saved.exceptionOrNull())
                    ctx.getString(R.string.save_failed)
                }
                finish(message)
            }
        }
    }

    private fun writeCacheCopy(png: ByteArray): File {
        val file = File(ctx.cacheDir, "shots/clip-${SystemClock.elapsedRealtimeNanos()}.png")
        file.parentFile?.mkdirs()
        file.writeBytes(png)
        return file
    }

    private fun copyToClipboard(file: File, crop: Rect): Boolean {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val label = "Screenshot ${crop.width()} × ${crop.height()}"
        val clipboard = ctx.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newUri(ctx.contentResolver, label, uri))
        // Diagnostic only. AOSP allows the write without focus; it is the read-back
        // that needs it, so a false negative here is not worth retrying over.
        val stuck = clipboard.primaryClipDescription?.label == label
        Log.i(TAG, if (stuck) "clipboard write confirmed" else "clipboard write did NOT stick")
        return stuck
    }

    /**
     * Bitmap.createBitmap(source, …) returns the source itself when the rect covers the
     * whole immutable image, which would alias the frozen frame into the encoder.
     *
     * The destination is a plain sRGB ARGB_8888 bitmap, and that is load-bearing for
     * more than aliasing: an accessibility screenshot can arrive tagged Display P3, and
     * drawing it here is what converts it to sRGB for the PNG.
     */
    private fun cropFrom(source: Bitmap, r: Rect): Bitmap {
        val out = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, r, Rect(0, 0, r.width(), r.height()), null)
        return out
    }

    private fun pruneCache() {
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        File(ctx.cacheDir, "shots").listFiles()?.forEach {
            if (it.lastModified() < cutoff) it.delete()
        }
    }

    // ---- teardown ----------------------------------------------------------

    /**
     * Total teardown. There is no onDestroy to finish the job on the accessibility
     * path — the process stays bound for as long as the service is enabled — so
     * everything the session owns has to be released right here.
     */
    fun finish(message: String?) {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        main.removeCallbacksAndMessages(null)
        runCatching { ctx.applicationContext.unregisterComponentCallbacks(configCallback) }
        message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
        detachOverlay()
        // Graceful: an in-flight encode must still run to its shot.recycle().
        io.shutdown()
        if (live === this) live = null
        onEnded()
    }
}
