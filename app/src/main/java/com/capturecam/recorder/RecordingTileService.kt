package com.capturecam.recorder

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Ajuste rápido para empezar y parar sin abrir la app ni dejarla en el vídeo. */
class RecordingTileService : TileService() {

    override fun onStartListening() = refresh()

    // La variante con Intent solo se usa por debajo de Android 14, donde sigue siendo la válida.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (RecorderState.phase != Phase.IDLE) {
            RecorderService.send(this, RecorderService.ACTION_STOP)
            return
        }
        val intent = Intent(this, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            // Con targetSdk 34+ la variante con Intent lanza una excepción.
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val idle = RecorderState.phase == Phase.IDLE
        tile.state = if (idle) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(if (idle) R.string.tile_label else R.string.tile_stop)
        tile.updateTile()
    }
}
