package com.varadan.hotspot

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log

class BluetoothHotspotService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_LOG_UPDATE = "com.varadan.hotspot.ACTION_LOG_UPDATE"
        const val EXTRA_LOG_MESSAGE = "com.varadan.hotspot.EXTRA_LOG_MESSAGE"
        const val ACTION_STOP_SERVICE = "com.varadan.hotspot.ACTION_STOP_SERVICE"
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothHotspotService = this@BluetoothHotspotService
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        broadcastUpdate("Bluetooth ON detected")
                        ensureServerRunning()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        broadcastUpdate("Bluetooth OFF detected")
                        BluetoothServer.stopServer()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            broadcastUpdate("Manual shutdown requested")
            BluetoothServer.stopServer()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        NotificationHelper.createChannel(this)
        val notification = NotificationHelper.getServiceNotification(this)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            broadcastUpdate("Foreground Service Active")
        } catch (e: Exception) {
            broadcastUpdate("FGS Error: ${e.message}")
            stopSelf()
        }

        ensureServerRunning()
        return START_STICKY
    }

    fun ensureServerRunning() {
        if (!BluetoothServer.isServerRunning()) {
            BluetoothServer.startServer(this, { logMsg -> broadcastUpdate(logMsg) }) { message ->
                handleRemoteCommand(message)
            }
        }
    }

    private fun handleRemoteCommand(message: String) {
        val cmd = message.trim().uppercase()
        broadcastUpdate("Command: $cmd")
        
        when {
            cmd.contains("HOTSPOT_ON") -> NotificationHelper.sendHotspotOn(this)
            cmd.contains("HOTSPOT_OFF") -> NotificationHelper.sendHotspotOff(this)
        }
    }

    fun broadcastUpdate(message: String) {
        val intent = Intent(ACTION_LOG_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_LOG_MESSAGE, message)
        }
        sendBroadcast(intent)
        // Also keep writing to the old LogManager for now so the UI doesn't break
        LogManager.addLog(message)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: Exception) {}
        BluetoothServer.stopServer()
        super.onDestroy()
    }
}
