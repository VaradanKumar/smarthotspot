package com.varadan.hotspot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BluetoothManager {

    private fun getAdapter(context: Context): BluetoothAdapter? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager
        return bluetoothManager?.adapter
    }

    fun isBluetoothSupported(context: Context): Boolean {
        return getAdapter(context) != null
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        if (!hasBluetoothPermission(context)) return false
        return getAdapter(context)?.isEnabled == true
    }

    fun hasBluetoothPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            val advertise = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
            connect == PackageManager.PERMISSION_GRANTED && advertise == PackageManager.PERMISSION_GRANTED
        } else true // This app advertises only; it does not scan on Android 11 and lower.
    }

    fun getBluetoothName(context: Context): String {
        if (!hasBluetoothPermission(context)) return "No Permission"
        return try {
            getAdapter(context)?.name ?: "Unknown"
        } catch (e: SecurityException) {
            "Permission Required"
        }
    }
}
