package com.varadan.hotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received broadcast: $action")
        
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == "android.intent.action.QUICKBOOT_POWERON" || 
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            
            Log.d("BootReceiver", "Starting SmartHotspot service automatically")
            val serviceIntent = Intent(context, BluetoothHotspotService::class.java)
            try {
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to auto-start service: ${e.message}")
            }
        }
    }
}