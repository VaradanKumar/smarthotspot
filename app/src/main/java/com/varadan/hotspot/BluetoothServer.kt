package com.varadan.hotspot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object BluetoothServer {

    private val serviceUuid: UUID = UUID.fromString("94f39d29-7d6d-437d-973b-fba39e49d4ee")
    private val commandCharacteristicUuid: UUID = UUID.fromString("00000001-94f3-9d29-7d6d-973bfba39e49")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)
    private val isStarting = AtomicBoolean(false)

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var logCallback: ((String) -> Unit)? = null
    private var messageReceivedCallback: ((String) -> Unit)? = null

    private fun log(message: String) {
        logCallback?.invoke(message)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isStarting.set(false)
            isRunning.set(true)
            log("BLE service is ready")
        }

        override fun onStartFailure(errorCode: Int) {
            log("BLE advertising failed ($errorCode)")
            stopServer()
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            mainHandler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> log("BLE client connected")
                    BluetoothProfile.STATE_DISCONNECTED -> log("BLE client disconnected")
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != serviceUuid) return

            mainHandler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("BLE service setup failed ($status)")
                    stopServer()
                    return@post
                }

                startAdvertising()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val status = when {
                characteristic.uuid != commandCharacteristicUuid -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                preparedWrite || offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                value == null || value.isEmpty() -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                else -> BluetoothGatt.GATT_SUCCESS
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val message = value!!.toString(Charsets.UTF_8).trim().uppercase()
                mainHandler.post {
                    log("BLE command received: $message")
                    messageReceivedCallback?.invoke(message)
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, status, 0, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer(context: Context, onLog: (String) -> Unit, onMessageReceived: (String) -> Unit) {
        if (isRunning.get() || !isStarting.compareAndSet(false, true)) return

        logCallback = onLog
        messageReceivedCallback = onMessageReceived

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            isStarting.set(false)
            log("Bluetooth is off")
            return
        }

        mainHandler.post {
            closeResources()
            bluetoothAdapter = adapter
            
            val leAdvertiser = adapter.bluetoothLeAdvertiser
            if (leAdvertiser == null) {
                isStarting.set(false)
                log("Hardware does not support BLE advertising")
                return@post
            }

            gattServer = bluetoothManager.openGattServer(context.applicationContext, gattServerCallback)
            if (gattServer == null) {
                log("Unable to open BLE GATT server")
                stopServer()
                return@post
            }

            val commandCharacteristic = BluetoothGattCharacteristic(
                commandCharacteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(commandCharacteristic)

            if (!gattServer!!.addService(service)) {
                log("Unable to add BLE command service")
                stopServer()
            } else {
                log("Starting BLE command service")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val adapter = bluetoothAdapter
        val leAdvertiser = adapter?.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            log("BLE advertiser is unavailable (check BT status)")
            stopServer()
            return
        }
        advertiser = leAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        isRunning.set(false)
        isStarting.set(false)
        mainHandler.post { closeResources() }
    }

    @SuppressLint("MissingPermission")
    private fun closeResources() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            gattServer?.clearServices()
            gattServer?.close()
        } catch (_: Exception) {
        }
        advertiser = null
        gattServer = null
        bluetoothAdapter = null
    }

    fun isServerRunning(): Boolean = isRunning.get()
}
