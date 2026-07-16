package com.varadan.hotspot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object BluetoothServer {

    private const val TAG = "BluetoothServer"
    private const val APP_NAME = "SmartHotspot"
    private val APP_UUID: UUID = UUID.fromString("94f39d29-7d6d-437d-973b-fba39e49d4ee")

    private var serverSocket: BluetoothServerSocket? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val isRunning = AtomicBoolean(false)
    
    var activePort: Int = -1
        private set

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE Advertisement started successfully")
            LogManager.addLog("BLE Beacon: Active")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE Advertisement failed: $errorCode")
            LogManager.addLog("BLE Beacon: Failed ($errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer(context: Context, onMessageReceived: (String) -> Unit) {
        if (isRunning.get()) {
            Log.d(TAG, "Server is already running")
            return
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null) {
            Log.e(TAG, "Bluetooth not supported")
            return
        }

        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            return
        }

        Thread {
            isRunning.set(true)
            try {
                Log.d(TAG, "Starting RFCOMM server for $APP_NAME ($APP_UUID)")
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                
                // Safe Port Discovery for Android 16 / One UI 8
                val port = try {
                    val socketStr = serverSocket.toString()
                    // Try to find "Channel: X" or "port=X" or "mChannel=X"
                    val regex = Regex("(?:Channel|port|mChannel)[^0-9]*([0-9]+)")
                    val match = regex.find(socketStr)
                    match?.groupValues?.get(1)?.toInt() ?: run {
                        // Fallback: Try reflection but handle security block
                        val mChannelField = serverSocket?.javaClass?.getDeclaredField("mChannel")
                        mChannelField?.isAccessible = true
                        mChannelField?.get(serverSocket) as Int
                    }
                } catch (e: Exception) { 
                    Log.d(TAG, "Standard port discovery blocked, using laptop scan fallback")
                    -1 
                }
                
                Log.d(TAG, "Server started on port: $port. Waiting for connection...")
                LogManager.addLog("Server started on port: $port")
                activePort = port

                // Start BLE Beacon with port info
                if (port != -1) {
                    startAdvertising(adapter, port)
                }

                while (isRunning.get()) {
                    val socket: BluetoothSocket? = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Accept failed: ${e.message}")
                            LogManager.addLog("Error: Accept failed")
                        } else {
                            Log.d(TAG, "Server socket closed intentionally")
                        }
                        null
                    }

                    if (socket != null) {
                        Log.i(TAG, "Incoming connection accepted")
                        LogManager.addLog("Client Connected!")
                        handleClient(socket, onMessageReceived)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fatal server error: ${e.message}")
                LogManager.addLog("Fatal Error: ${e.message}")
            } finally {
                stopServer()
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun handleClient(socket: BluetoothSocket, onMessageReceived: (String) -> Unit) {
        try {
            val deviceName = try { socket.remoteDevice.name } catch (e: SecurityException) { "Unknown Device" }
            Log.d(TAG, "Client connected: $deviceName")
            LogManager.addLog("Connection from: $deviceName")
            
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val writer = socket.outputStream.bufferedWriter(Charsets.UTF_8)

            while (isRunning.get()) {
                val message = reader.readLine()?.trim() ?: break
                if (message.isEmpty()) continue

                Log.d(TAG, "Received message: $message")
                
                if (message == "HELO") {
                    LogManager.addLog("Handshake: HELO received")
                    writer.write("READY\n")
                    writer.flush()
                    LogManager.addLog("Handshake: Sent READY")
                } else {
                    LogManager.addLog("Message: $message")
                    onMessageReceived(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handling error: ${e.message}")
            LogManager.addLog("Comm Error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter, port: Int) {
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BLE Advertising not supported")
            return
        }

        // Use BALANCED mode with high power for better reliability
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // Shrink the packet to fit the 31-byte limit
        // We use Manufacturer Data (ID 0xFFFF) to hide the 1-byte port
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(APP_UUID))
            .addManufacturerData(0xFFFF, byteArrayOf(port.toByte()))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        isRunning.set(false)
        activePort = -1
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            advertiser = null
            
            serverSocket?.close()
            serverSocket = null
            Log.d(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}")
        }
    }

    fun isServerRunning(): Boolean {
        return isRunning.get()
    }
}
