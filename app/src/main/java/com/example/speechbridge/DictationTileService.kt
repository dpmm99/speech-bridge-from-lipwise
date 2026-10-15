package com.example.speechbridge

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A Quick Settings tile that starts dictation from anywhere - swipe down, tap, speak. It exists so
 * reaching this app's speech recognition does not depend on a keyboard offering it: Samsung
 * Keyboard's own voice-input list only ever holds Samsung's and Google's, and no third-party
 * recogniser can appear in it.
 */
class DictationTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = "Dictate"
        tile.subtitle = "Speech Bridge"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, DictateActivity::class.java)
            // A task of its own, so closing the dictation screen returns to whatever the user was
            // actually doing. With NEW_TASK alone it lands in this app's existing task, and closing
            // it reveals MainActivity instead of the app they were typing in.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
