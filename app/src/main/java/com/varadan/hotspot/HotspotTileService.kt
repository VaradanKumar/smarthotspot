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
            startForegroundService(intent)
        } else {
            startForegroundService(intent)
        }
        
        // Give it a moment to update state then refresh tile
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            updateTile()
        }, 1000)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = BluetoothServer.getState()
        val isRunning = state != BluetoothServer.ServerState.INACTIVE
        
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "AirBeam Pro"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_airbeam)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                BluetoothServer.ServerState.INACTIVE -> "Standby"
                BluetoothServer.ServerState.STARTING -> "Starting..."
                BluetoothServer.ServerState.GATT_READY -> "Ready"
                BluetoothServer.ServerState.ADVERTISING -> "Broadcasting"
                BluetoothServer.ServerState.STOPPING -> "Stopping..."
            }
        }
        tile.updateTile()
    }
}
