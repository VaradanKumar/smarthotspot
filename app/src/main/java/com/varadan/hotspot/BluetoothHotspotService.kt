package com.varadan.hotspot

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class BluetoothHotspotService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val TAG = "HotspotService"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth turned ON, restarting server")
                        LogManager.addLog("BT ON: Restarting Server")
                        ensureServerRunning()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth turned OFF, stopping server")
                        LogManager.addLog("BT OFF: Server Paused")
                        BluetoothServer.stopServer()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // Acquire WakeLock to keep radio active
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartHotspot::RadioLock").apply {
            acquire()
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        
        // Ensure channels are created (important for boot start)
        NotificationHelper.createChannel(this)
        
        // Start as foreground service
        val notification = NotificationHelper.getServiceNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }

        ensureServerRunning()

        return START_STICKY
    }

    private fun ensureServerRunning() {
        // Ensure Bluetooth Server is running
        if (!BluetoothServer.isServerRunning()) {
            if (BluetoothManager.hasBluetoothPermission(this) && BluetoothManager.isBluetoothEnabled(this)) {
                BluetoothServer.startServer(this) { message ->
                    handleRemoteCommand(message)
                }
            } else {
                Log.w(TAG, "Bluetooth not ready for server")
            }
        }
    }

    private fun handleRemoteCommand(message: String) {
        val cleanMessage = message.trim().uppercase()
        Log.i(TAG, "Command received from laptop: $cleanMessage")
        
        // Show a popup so the user knows the signal arrived
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            android.widget.Toast.makeText(this, "Remote Signal: $cleanMessage", android.widget.Toast.LENGTH_SHORT).show()
        }

        when {
            cleanMessage.contains("HOTSPOT_ON") -> {
                Log.i(TAG, "Triggering HOTSPOT_ON notification")
                NotificationHelper.sendHotspotOn(this)
            }
            cleanMessage.contains("HOTSPOT_OFF") -> {
                Log.i(TAG, "Triggering HOTSPOT_OFF notification")
                NotificationHelper.sendHotspotOff(this)
            }
            else -> {
                Log.w(TAG, "Unknown command string: $cleanMessage")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        unregisterReceiver(bluetoothStateReceiver)
        
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        BluetoothServer.stopServer()
        super.onDestroy()
    }
}