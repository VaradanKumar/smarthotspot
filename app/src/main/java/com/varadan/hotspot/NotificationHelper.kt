package com.varadan.hotspot

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {

    private const val CHANNEL_ID = "smart_hotspot_channel"
    private const val SERVICE_CHANNEL_ID = "hotspot_service_channel"

    fun createChannel(context: Context) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val hotspotChannel = NotificationChannel(
                CHANNEL_ID,
                "Smart Hotspot Status",
                NotificationManager.IMPORTANCE_HIGH
            )

            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Hotspot Remote Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the Bluetooth server running in the background"
                setShowBadge(false)
            }

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(hotspotChannel)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    fun getServiceNotification(context: Context): android.app.Notification {
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SmartHotspot Running")
            .setContentText("Always ready for laptop commands")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    fun sendHotspotOn(context: Context): Boolean {
        return sendNotification(context, 100, "HOTSPOT_ON")
    }

    fun sendHotspotOff(context: Context): Boolean {
        return sendNotification(context, 101, "HOTSPOT_OFF")
    }

    @SuppressLint("MissingPermission")
    private fun sendNotification(context: Context, notificationId: Int, command: String): Boolean {
        android.util.Log.i("NotificationHelper", "Attempting to send notification: $command")
        if (!canPostNotifications(context)) {
            android.util.Log.e("NotificationHelper", "Permission Denied: POST_NOTIFICATIONS")
            return false
        }

        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("SmartHotspot")
                .setContentText(command)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context)
                .notify(notificationId, notification)
            
            android.util.Log.i("NotificationHelper", "Notification sent successfully")
            return true
        } catch (e: Exception) {
            android.util.Log.e("NotificationHelper", "Failed to send notification: ${e.message}")
            return false
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
}
