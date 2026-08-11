package com.varadan.hotspot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/** Owns the phone's BLE peripheral. All state transitions run on the main looper. */
object BluetoothServer {
    private const val TAG = "BluetoothServer"
    private val serviceUuid = UUID.fromString("94f39d29-7d6d-437d-973b-fba39e49d4ee")
    private val commandCharacteristicUuid = UUID.fromString("00000001-94f3-9d29-7d6d-973bfba39e49")
    private val telemetryCharacteristicUuid = UUID.fromString("00000002-94f3-9d29-7d6d-973bfba39e49")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    enum class ServerState { INACTIVE, STARTING, GATT_READY, ADVERTISING, STOPPING }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var state = ServerState.INACTIVE
    private var generation = 0L
    private var adapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var gattServer: BluetoothGattServer? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val telemetrySubscribers = mutableSetOf<BluetoothDevice>()
    private var logCallback: ((String) -> Unit)? = null
    private var messageReceivedCallback: ((String) -> Unit)? = null

    private fun log(message: String) {
        Log.d(TAG, message)
        logCallback?.invoke(message)
    }

    private fun active(session: Long) = session == generation && state != ServerState.INACTIVE && state != ServerState.STOPPING

    @SuppressLint("MissingPermission")
    fun startServer(context: Context, onLog: (String) -> Unit, onMessageReceived: (String) -> Unit) {
        handler.post {
            logCallback = onLog
            messageReceivedCallback = onMessageReceived
            when (state) {
                ServerState.INACTIVE -> startServerOnMain(context.applicationContext)
                ServerState.STOPPING -> {
                    log("BLE server is stopping; start will be retried after cleanup")
                    handler.postDelayed({ startServer(context, onLog, onMessageReceived) }, 250)
                }
                else -> log("BLE server already active ($state)")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServerOnMain(context: Context) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val localAdapter = manager?.adapter
        if (localAdapter == null) {
            log("Cannot start BLE server: Bluetooth is unavailable")
            return
        }
        if (!localAdapter.isEnabled) {
            log("Cannot start BLE server: Bluetooth is off")
            return
        }
        if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) {
            log("Cannot start BLE server: BLE is not supported")
            return
        }
        val localAdvertiser = localAdapter.bluetoothLeAdvertiser
        if (localAdvertiser == null) {
            log("Cannot start BLE server: BLE advertising is not supported")
            return
        }

        state = ServerState.STARTING
        generation += 1
        val session = generation
        adapter = localAdapter
        advertiser = localAdvertiser
        advertiseCallback = createAdvertiseCallback(session)
        try {
            gattServer = manager.openGattServer(context, createGattServerCallback(session))
            if (gattServer == null) {
                log("Failed to open GATT server")
                finishStopOnMain()
                return
            }
            addGattService(session)
        } catch (error: SecurityException) {
            log("Bluetooth permission was revoked: ${error.message}")
            finishStopOnMain()
        } catch (error: Exception) {
            log("Failed to start GATT server: ${error.message}")
            finishStopOnMain()
        }
    }

    private fun createAdvertiseCallback(session: Long) = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            handler.post {
            if (!active(session)) return@post
            state = ServerState.ADVERTISING
            log("BLE advertising started")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
            if (!active(session)) return@post
            log("BLE advertising failed (error $errorCode)")
            stopServerOnMain()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun addGattService(session: Long) {
        if (!active(session)) return
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(
            commandCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        ))
        telemetryCharacteristic = BluetoothGattCharacteristic(
            telemetryCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).also { characteristic ->
            characteristic.addDescriptor(BluetoothGattDescriptor(
                cccdUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
        }
        service.addCharacteristic(requireNotNull(telemetryCharacteristic))
        if (gattServer?.addService(service) != true) {
            log("GATT service registration could not be initiated")
            stopServerOnMain()
        } else {
            log("Registering AirBeam GATT service")
        }
    }

    private fun createGattServerCallback(session: Long) = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
            if (!active(session)) return@post
            val deviceName = try { device.name ?: "Unknown device" } catch (_: SecurityException) { "Unknown device" }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectedDevices.add(device)
                    log("Connected: $deviceName")
                } else {
                    log("Connection failed for $deviceName (GATT status $status)")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device)
                    telemetrySubscribers.remove(device)
                    log("Disconnected: $deviceName (GATT status $status)")
                    // Advertising remains active while a central is connected. Starting it again here
                    // creates duplicate start requests and intermittent ADVERTISE_FAILED_ALREADY_STARTED.
                }
            }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
            if (!active(session) || service.uuid != serviceUuid) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("GATT service registration failed (status $status)")
                stopServerOnMain()
                return@post
            }
            state = ServerState.GATT_READY
            startAdvertising(session)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            handler.post {
            if (!active(session)) return@post
            val value = if (characteristic.uuid == telemetryCharacteristicUuid) characteristic.value ?: ByteArray(0) else ByteArray(0)
            val status = when {
                characteristic.uuid != telemetryCharacteristicUuid -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                offset > value.size -> BluetoothGatt.GATT_INVALID_OFFSET
                else -> BluetoothGatt.GATT_SUCCESS
            }
            gattServer?.sendResponse(device, requestId, status, offset, if (status == BluetoothGatt.GATT_SUCCESS) value.copyOfRange(offset, value.size) else null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            handler.post {
            if (!active(session)) return@post
            val commandValue = value
            val status = when {
                characteristic.uuid != commandCharacteristicUuid -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                preparedWrite || offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                commandValue == null || commandValue.isEmpty() -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                commandValue.size > 128 -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                else -> BluetoothGatt.GATT_SUCCESS
            }
            if (responseNeeded) gattServer?.sendResponse(device, requestId, status, 0, null)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                commandValue?.let {
                    val command = it.toString(Charsets.UTF_8).trim().uppercase()
                    log("Command received: $command")
                    messageReceivedCallback?.invoke(command)
                }
            }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            handler.post {
            if (!active(session)) return@post
            val enabled = if (telemetrySubscribers.contains(device)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            val status = if (descriptor.uuid == cccdUuid && descriptor.characteristic.uuid == telemetryCharacteristicUuid && offset <= enabled.size) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            gattServer?.sendResponse(device, requestId, status, offset, if (status == BluetoothGatt.GATT_SUCCESS) enabled.copyOfRange(offset, enabled.size) else null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            handler.post {
            if (!active(session)) return@post
            val isCccd = descriptor.uuid == cccdUuid && descriptor.characteristic.uuid == telemetryCharacteristicUuid
            val status = when {
                !isCccd -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                preparedWrite || offset != 0 || value == null -> BluetoothGatt.GATT_INVALID_OFFSET
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                    telemetrySubscribers.add(device)
                    log("Telemetry subscribed")
                    BluetoothGatt.GATT_SUCCESS
                }
                value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                    telemetrySubscribers.remove(device)
                    log("Telemetry unsubscribed")
                    BluetoothGatt.GATT_SUCCESS
                }
                else -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            }
            if (responseNeeded) gattServer?.sendResponse(device, requestId, status, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Telemetry notification failed (status $status)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(session: Long) {
        if (!active(session) || state == ServerState.ADVERTISING) return
        val callback = advertiseCallback ?: return
        val localAdvertiser = advertiser ?: run {
            log("BLE advertiser is unavailable")
            stopServerOnMain()
            return
        }
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(serviceUuid)).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        localAdvertiser.startAdvertising(settings, data, scanResponse, callback)
    }

    fun stopServer() = handler.post { stopServerOnMain() }

    @SuppressLint("MissingPermission")
    private fun stopServerOnMain() {
        if (state == ServerState.INACTIVE || state == ServerState.STOPPING) return
        state = ServerState.STOPPING
        generation += 1 // invalidates callbacks from this GATT/advertiser instance.
        try {
            advertiseCallback?.let { advertiser?.stopAdvertising(it) }
            gattServer?.clearServices()
            gattServer?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Error closing BLE resources", error)
        } finally {
            finishStopOnMain()
        }
    }

    private fun finishStopOnMain() {
        adapter = null
        advertiser = null
        advertiseCallback = null
        gattServer = null
        telemetryCharacteristic = null
        connectedDevices.clear()
        telemetrySubscribers.clear()
        state = ServerState.INACTIVE
        log("BLE server stopped")
    }

    fun getState(): ServerState = state
    fun isServerRunning(): Boolean = state == ServerState.STARTING || state == ServerState.GATT_READY || state == ServerState.ADVERTISING

    @SuppressLint("MissingPermission")
    fun updateTelemetry(data: String) = handler.post {
        if (state != ServerState.ADVERTISING) return@post
        val characteristic = telemetryCharacteristic ?: return@post
        val value = data.toByteArray(Charsets.UTF_8)
        characteristic.value = value
        telemetrySubscribers.toList().forEach { device ->
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(device, characteristic, false, value)
            } else {
                @Suppress("DEPRECATION") gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            }
            if (started != true) Log.w(TAG, "Could not queue telemetry notification")
        }
    }
}
