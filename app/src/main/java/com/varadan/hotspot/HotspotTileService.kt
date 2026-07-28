package com.varadan.hotspot

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.os.Build

class HotspotTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = BluetoothServer.isServerRunning()
        val intent = Intent(this, BluetoothHotspotService::class.java)
        
        if (isRunning) {
            intent.action = BluetoothHotspotService.ACTION_STOP_SERVICE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        
        // Give it a moment to update state then refresh tile
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            updateTile()
        }, 500)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRunning = BluetoothServer.isServerRunning()
        
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "AirBeam"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_airbeam)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) "Ready" else "Standby"
        }
        tile.updateTile()
    }
}
