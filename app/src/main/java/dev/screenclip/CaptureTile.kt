package dev.screenclip

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * The trigger that makes this app useful: fired from inside whatever app you are
 * looking at, rather than from the launcher.
 */
class CaptureTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            // A shutter, not a toggle. Never STATE_UNAVAILABLE — that makes the tile
            // unclickable and strands the user in the shade with no route to setup.
            state = Tile.STATE_INACTIVE
            subtitle = getString(
                if (ShotService.instance != null) R.string.tile_instant else R.string.tile_prompts,
            )
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // A second press while a selection is open means "get rid of it".
        if (CaptureSession.isActive) {
            CaptureSession.cancelLive(getString(R.string.cancelled))
            return
        }
        if (isLocked) unlockAndRun { begin() } else begin()
    }

    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            launch(Intent(this, SetupActivity::class.java))
            return
        }
        val service = ShotService.instance
        if (service == null) {
            // No accessibility service: fall back to MediaProjection, which needs an
            // activity to host the consent prompt.
            launch(Intent(this, ConsentActivity::class.java))
            return
        }
        val app = applicationContext
        service.capture(Settle.SHADE) { frozen, message ->
            if (frozen == null) {
                message?.let { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() }
                return@capture
            }
            if (CaptureSession.begin(app, frozen) { } == null) frozen.bitmap.recycle()
        }
    }

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The Intent overload throws for targetSdk 34+; a bare startActivity would
            // lose the background-activity-start allowance the tile grants us.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
