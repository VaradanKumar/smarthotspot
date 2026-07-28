package com.varadan.hotspot

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
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
                "AirBeam Activity",
                NotificationManager.IMPORTANCE_HIGH
            )

            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "AirBeam Engine",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the AirBeam server running"
                setShowBadge(false)
            }

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(hotspotChannel)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    fun getServiceNotification(context: Context): android.app.Notification {
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_airbeam)
            .setContentTitle("AirBeam Active")
            .setContentText("Listening for laptop commands")
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
            val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_airbeam)
                .setContentTitle("AirBeam")
                .setContentText(command)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setTimeoutAfter(10000)
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
