package dev.screenclip

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Hosts the MediaProjection consent prompt for the fallback path.
 *
 * Its own task (taskAffinity="") so that moveTaskToBack reveals the app the user was
 * actually in, rather than dragging the launcher along with it.
 */
class ConsentActivity : ComponentActivity() {

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, R.string.denied, Toast.LENGTH_SHORT).show()
                finish()
                return@registerForActivityResult
            }
            // Armed here, not in onCreate: the consent dialog stopping this activity
            // would otherwise mark the UI hidden while the dialog is still on screen,
            // and the freeze would commit a picture of the prompt.
            CaptureSignals.armed()
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, data),
            )
            moveTaskToBack(true)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        // API 34+ pins the prompt to the whole display, so "share one app" is never
        // offered — a partial mirror would produce a letterboxed frame whose
        // coordinates no longer match the screen.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        consent.launch(intent)
    }

    override fun onStop() {
        super.onStop()
        CaptureSignals.markUiHidden()
    }
}
