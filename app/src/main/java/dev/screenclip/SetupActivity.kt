package dev.screenclip

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** The only screen in the app. Reachable from the app icon and the tile, both by long press. */
class SetupActivity : ComponentActivity() {

    private val tile by lazy { ComponentName(this, CaptureTile::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(MainActivity.SEEN_SETUP, true)
            .apply()

        findViewById<Button>(R.id.grant_overlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }

        findViewById<Button>(R.id.enable_a11y).setOnClickListener { view ->
            val service = ShotService.instance
            if (service != null) {
                // A service may switch itself off; only turning it *on* has to go
                // through Settings.
                service.disableSelf()
                Toast.makeText(this, R.string.a11y_turned_off, Toast.LENGTH_SHORT).show()
                // The unbind is asynchronous, so re-read the state a moment later.
                view.postDelayed({ refresh() }, 500)
            } else {
                openAccessibilitySettings()
            }
        }

        findViewById<Button>(R.id.add_tile).setOnClickListener {
            if (!tileEnabled()) setTileEnabled(true)
            requestTile()
            refresh()
        }

        findViewById<Button>(R.id.remove_tile).setOnClickListener {
            // There is no API to remove a tile. Disabling the component is what makes
            // the system drop it from Quick Settings and from the tile picker.
            setTileEnabled(false)
            Toast.makeText(this, R.string.tile_removed, Toast.LENGTH_SHORT).show()
            refresh()
        }

        findViewById<Button>(R.id.capture_now).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val canOverlay = Settings.canDrawOverlays(this)
        val instant = ShotService.instance != null
        val hasTile = tileEnabled()

        findViewById<Button>(R.id.grant_overlay).isEnabled = !canOverlay
        findViewById<Button>(R.id.capture_now).isEnabled = canOverlay
        findViewById<TextView>(R.id.status_overlay).setText(
            if (canOverlay) R.string.state_on else R.string.state_required,
        )

        findViewById<Button>(R.id.enable_a11y).setText(
            if (instant) R.string.disable_a11y else R.string.enable_a11y,
        )
        findViewById<TextView>(R.id.status_a11y).setText(
            if (instant) R.string.state_on else R.string.state_off,
        )

        findViewById<Button>(R.id.add_tile).isEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        findViewById<Button>(R.id.remove_tile).isEnabled = hasTile
    }

    private fun tileEnabled(): Boolean =
        packageManager.getComponentEnabledSetting(tile) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun setTileEnabled(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            tile,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun openAccessibilitySettings() {
        // ACTION_ACCESSIBILITY_DETAILS_SETTINGS is a @SystemApi guarded by a signature
        // permission, so the list plus a highlight hint is the most direct link a
        // normal app can offer.
        val key = ComponentName(this, ShotService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", key)
            putExtra(
                ":settings:show_fragment_args",
                Bundle().apply { putString(":settings:fragment_args_key", key) },
            )
        }
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        Toast.makeText(this, R.string.a11y_hint, Toast.LENGTH_LONG).show()
    }

    private fun requestTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.tile_manual, Toast.LENGTH_LONG).show()
            return
        }
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            tile,
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_stat_capture),
            mainExecutor,
        ) { result ->
            val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            if (added) {
                runOnUiThread {
                    Toast.makeText(this, R.string.tile_added, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        }
    }
}
