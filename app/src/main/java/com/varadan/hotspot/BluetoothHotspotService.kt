package com.varadan.hotspot

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.Executors

class BluetoothHotspotService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_LOG_UPDATE = "com.varadan.hotspot.ACTION_LOG_UPDATE"
        const val EXTRA_LOG_MESSAGE = "com.varadan.hotspot.EXTRA_LOG_MESSAGE"
        const val ACTION_STOP_SERVICE = "com.varadan.hotspot.ACTION_STOP_SERVICE"
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentDisplayInfo: Int = -1
    private var telephonyCallback: Any? = null

    private fun startNetworkMonitoring() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.DisplayInfoListener {
                    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                        currentDisplayInfo = telephonyDisplayInfo.overrideNetworkType
                    }
                }
                tm.registerTelephonyCallback(Executors.newSingleThreadExecutor(), callback)
                telephonyCallback = callback
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                        currentDisplayInfo = telephonyDisplayInfo.overrideNetworkType
                    }
                }
                tm.listen(listener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
                telephonyCallback = listener
            }
        } catch (e: Exception) {
            Log.e("AirBeam", "Failed to start network monitoring: ${e.message}")
        }
    }

    private fun stopNetworkMonitoring() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val cb = telephonyCallback ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cb is android.telephony.TelephonyCallback) {
                tm.unregisterTelephonyCallback(cb)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cb is PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm.listen(cb, PhoneStateListener.LISTEN_NONE)
            }
            telephonyCallback = null
        } catch (e: Exception) {
            Log.e("AirBeam", "Failed to stop network monitoring: ${e.message}")
        }
    }
    
    private val telemetryRunnable = object : Runnable {
        private var heartbeatCount = 0
        override fun run() {
            if (BluetoothServer.isServerRunning()) {
                updateTelemetryData()
                heartbeatCount++
                if (heartbeatCount >= 2) { // Every 30s
                    broadcastUpdate("Engine Heartbeat: Active")
                    heartbeatCount = 0
                }
            }
            mainHandler.postDelayed(this, 15000) // Every 15 seconds
        }
    }

    private var isTelemetryLoopActive = false

    private fun updateTelemetryData() {
        if (!BluetoothServer.isServerRunning()) return

        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val signalLevel = try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tm.signalStrength?.level ?: 3
            } else {
                3
            }
        } catch (e: Exception) { 3 }

        val networkType = try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            val is5G = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                currentDisplayInfo == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && currentDisplayInfo == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
            } else {
                false
            }

            if (is5G) {
                "5G"
            } else {
                @SuppressLint("MissingPermission")
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) tm.dataNetworkType else tm.networkType
                when (type) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                    TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
                    else -> "4G"
                }
            }
        } catch (e: Exception) { "LTE" }

        val model = Build.MODEL.replace("|", "").replace(":", "")
        BluetoothServer.updateTelemetry("B:$batteryPct|S:$signalLevel|N:$networkType|M:$model")
    }

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
        startNetworkMonitoring()
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
            broadcastUpdate("Starting Engine...")
            BluetoothServer.startServer(this, { logMsg -> broadcastUpdate(logMsg) }) { message ->
                handleRemoteCommand(message)
            }
        }
        
        // Only start telemetry if server is running or starting
        if (!isTelemetryLoopActive) {
            isTelemetryLoopActive = true
            mainHandler.postDelayed(telemetryRunnable, 5000)
        }
    }

    private fun handleRemoteCommand(message: String) {
        val cmd = message.trim().uppercase()
        broadcastUpdate("Command: $cmd")
        
        when {
            cmd.contains("HOTSPOT_ON") -> NotificationHelper.sendHotspotOn(this)
            cmd.contains("HOTSPOT_OFF") -> NotificationHelper.sendHotspotOff(this)
            cmd.contains("LOCATE_PHONE") -> NotificationHelper.triggerLocateAlarm(this)
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
        stopNetworkMonitoring()
        mainHandler.removeCallbacks(telemetryRunnable)
        BluetoothServer.stopServer()
        super.onDestroy()
    }
}
