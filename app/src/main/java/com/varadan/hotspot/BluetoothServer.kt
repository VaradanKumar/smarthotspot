package com.varadan.hotspot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

object BluetoothServer {

    private const val TAG = "BluetoothServer"

    private const val APP_NAME = "SmartHotspot"

    private val APP_UUID: UUID =
        UUID.fromString("12345678-1234-5678-1234-567812345678")

    @SuppressLint("MissingPermission")
    fun startServer(onMessageReceived: (String) -> Unit) {

        val adapter = BluetoothAdapter.getDefaultAdapter()

        if (adapter == null) {
            Log.d(TAG, "Bluetooth not supported")
            return
        }

        Thread {

            try {

                val serverSocket: BluetoothServerSocket =
                    adapter.listenUsingRfcommWithServiceRecord(
                        APP_NAME,
                        APP_UUID
                    )

                Log.d(TAG, "Waiting for laptop...")

                while (true) {

                    val socket: BluetoothSocket =
                        serverSocket.accept()

                    Log.d(TAG, "Laptop Connected")

                    val reader = BufferedReader(
                        InputStreamReader(
                            socket.inputStream
                        )
                    )

                    val message = reader.readLine()

                    if (message != null) {

                        Log.d(TAG, "Received: $message")

                        onMessageReceived(message)

                    }

                    socket.close()

                }

            } catch (e: Exception) {

                e.printStackTrace()

            }

        }.start()

    }

}