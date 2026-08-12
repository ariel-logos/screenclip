package dev.screenclip

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * The MediaProjection freeze, unchanged in behaviour from the version verified on
 * device. Used when the accessibility service is not enabled.
 */
class ProjectionFreezer(
    private val ctx: Context,
    private val resultCode: Int,
    private val resultData: Intent,
) : Freezer {

    private companion object {
        /** No new frame for this long means the screen has stopped moving. */
        const val QUIET_MS = 120L

        /** Commit whatever we have rather than failing; the UI signal may never arrive. */
        const val GIVE_UP_MS = 2500L
    }

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = ctx.getSystemService(WindowManager::class.java)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var pendingImage: Image? = null
    private var haveFrame = false
    private var settled = false
    private val bounds = Rect()

    private var done: ((Frozen?, String?) -> Unit)? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Only ever a system/ROM/user revocation: our own stop() unregisters first.
            release()
            if (!haveFrame) fail(ctx.getString(R.string.capture_blocked))
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (!haveFrame && (width != bounds.width() || height != bounds.height())) {
                fail(ctx.getString(R.string.single_app_capture))
            }
        }
    }

    override fun freeze(done: (Frozen?, String?) -> Unit) {
        this.done = done
        bounds.set(windowManager.currentWindowMetrics.bounds)

        val projection = ctx.getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, resultData)
            .apply { registerCallback(callback, main) }
        this.projection = projection

        val source = ImageReader.newInstance(
            bounds.width(), bounds.height(), PixelFormat.RGBA_8888, 2,
        )
        reader = source
        source.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (haveFrame) {
                image.close()
                return@setOnImageAvailableListener
            }
            // Keep only the newest, and convert nothing until we commit.
            pendingImage?.close()
            pendingImage = image
            main.removeCallbacks(quiet)
            main.postDelayed(quiet, QUIET_MS)
        }, main)

        display = projection.createVirtualDisplay(
            "screenclip",
            bounds.width(),
            bounds.height(),
            ctx.resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            source.surface,
            null,
            main,
        )
        if (display == null) {
            fail(ctx.getString(R.string.no_frame))
            return
        }

        // A late signal must still be able to wake the commit: if the last frame landed
        // before the activity finished disappearing, the quiet timer has already fired.
        CaptureSignals.onUiHidden = {
            main.post {
                if (!haveFrame) {
                    main.removeCallbacks(quiet)
                    main.postDelayed(quiet, QUIET_MS)
                }
            }
        }
        main.postDelayed(giveUp, GIVE_UP_MS)
    }

    private val quiet = Runnable {
        if (!haveFrame && CaptureSignals.uiHidden) commit()
    }

    private val giveUp = Runnable {
        if (haveFrame) return@Runnable
        // Holding valid pixels and failing anyway would be the worse bug.
        if (pendingImage != null) commit() else fail(ctx.getString(R.string.no_frame))
    }

    private fun commit() {
        val image = pendingImage ?: return
        main.removeCallbacks(quiet)
        main.removeCallbacks(giveUp)
        CaptureSignals.clear()

        val bitmap = image.toBitmap()
        pendingImage = null
        image.close()
        // Set before releasing, so the callback guard cannot see a half-finished state.
        haveFrame = true
        release()

        if (isBlank(bitmap)) {
            bitmap.recycle()
            fail(ctx.getString(R.string.protected_content))
            return
        }
        deliver(Frozen(bitmap, Rect(bounds)), null)
    }

    private fun fail(message: String) = deliver(null, message)

    private fun deliver(frozen: Frozen?, message: String?) {
        if (settled) return
        settled = true
        val callback = done
        done = null
        callback?.invoke(frozen, message)
    }

    override fun release() {
        CaptureSignals.clear()
        pendingImage?.close()
        pendingImage = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.let {
            // Before stop(), and load-bearing: AOSP dispatchStop() fans out to every
            // registered callback including the caller's, so a self-initiated stop
            // would re-enter onStop() and tear down the session.
            it.unregisterCallback(callback)
            it.stop()
        }
        projection = null
    }

    /**
     * A mirror of protected content comes back fully transparent. Pure black is
     * deliberately NOT treated as blank — plenty of real screens are black. (The
     * accessibility path needs a different test entirely; see ShotService.)
     */
    private fun isBlank(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (i in 0 until 8) {
            val y = (bitmap.height - 1) * i / 7
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (pixel in row) if (pixel ushr 24 != 0) return false
        }
        return true
    }

    /**
     * ImageReader pads rows out to the buffer stride, so the bitmap has to be made wide
     * enough to hold the padding and then trimmed back.
     */
    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }
}
