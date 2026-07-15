package com.varadan.hotspot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BluetoothManager {

    private fun getAdapter(context: Context): BluetoothAdapter? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bluetoothManager.adapter
    }

    // Check whether Bluetooth hardware exists
    fun isBluetoothSupported(context: Context): Boolean {
        return getAdapter(context) != null
    }

    // Check whether Bluetooth is ON
    fun isBluetoothEnabled(context: Context): Boolean {
        if (!hasBluetoothPermission(context)) return false
        return getAdapter(context)?.isEnabled == true
    }

    // Check necessary Bluetooth permissions based on Android version
    fun hasBluetoothPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // On older versions, BLUETOOTH and BLUETOOTH_ADMIN are sufficient (declared in manifest)
            true
        }
    }

    // Get phone Bluetooth name
    fun getBluetoothName(context: Context): String {
        if (!hasBluetoothPermission(context)) return "Permission Denied"
        return try {
            getAdapter(context)?.name ?: "Unknown"
        } catch (e: SecurityException) {
            "Permission Required"
        }
    }

    // Get phone Bluetooth MAC address
    fun getBluetoothAddress(context: Context): String {
        if (!hasBluetoothPermission(context)) return "Permission Denied"
        return try {
            getAdapter(context)?.address ?: "Unavailable"
        } catch (e: SecurityException) {
            "Permission Required"
        }
    }
}