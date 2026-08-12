package dev.screenclip

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * One still image of the screen, plus the geometry it was taken in.
 *
 * [bitmap] is always a software ARGB_8888 bitmap. That is not a detail: the shared
 * pipeline crops through a software Canvas and probes it with getPixels(), and both
 * throw outright on a Config.HARDWARE bitmap.
 */
class Frozen(val bitmap: Bitmap, val bounds: Rect)

/** Produces exactly one [Frozen], however it likes. */
interface Freezer {
    /** Calls [done] exactly once, on the main thread, with a frame or an error message. */
    fun freeze(done: (Frozen?, String?) -> Unit)

    /** Idempotent; safe to call whether or not [freeze] ever completed. */
    fun release()
}
