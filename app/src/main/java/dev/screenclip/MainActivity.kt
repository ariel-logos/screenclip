package dev.screenclip

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * The launcher icon: a trampoline, not a screen. Setup only appears when something
 * is actually missing.
 *
 * Note that capturing from here can only ever photograph what is behind the launcher.
 * The Quick Settings tile is the trigger that matters.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val PREFS = "screenclip"
        const val SEEN_SETUP = "seen_setup"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup is shown until it has been seen once, not merely until the overlay
        // permission is granted. Otherwise a user who grants that permission never
        // learns instant capture exists, and taps through a consent prompt forever.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!Settings.canDrawOverlays(this) || !prefs.getBoolean(SEEN_SETUP, false)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        if (CaptureSession.isActive) {
            finish()
            return
        }

        val service = ShotService.instance
        if (service == null) {
            startActivity(Intent(this, ConsentActivity::class.java))
            finish()
            return
        }

        // Instant path: no consent, no service, no notification.
        CaptureSignals.armed()
        val app = applicationContext
        service.capture(Settle.OUR_UI) { frozen, message ->
            if (frozen == null) {
                message?.let { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() }
                return@capture
            }
            if (CaptureSession.begin(app, frozen) { } == null) frozen.bitmap.recycle()
        }
        finish()
    }

    /** The signal the capture waits on: our window is genuinely off screen now. */
    override fun onStop() {
        super.onStop()
        CaptureSignals.markUiHidden()
    }
}
