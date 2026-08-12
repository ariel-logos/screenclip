package dev.screenclip

/**
 * Process-local handshake between [MainActivity] and [CaptureService].
 *
 * The service must not freeze the screen until our own UI is off it: at the moment
 * getMediaProjection() returns, the consent dialog is still animating out and the
 * activity is still visible, so the first mirrored frame is a picture of this app.
 * The activity's onStop() is the real "we are gone now" signal — far better than
 * guessing with a delay.
 */
object CaptureSignals {

    @Volatile
    var uiHidden = false
        private set

    /** Set by the service so a late signal can wake the pending capture. */
    @Volatile
    var onUiHidden: (() -> Unit)? = null

    fun armed() {
        uiHidden = false
    }

    fun markUiHidden() {
        uiHidden = true
        onUiHidden?.invoke()
    }

    fun clear() {
        onUiHidden = null
    }
}
