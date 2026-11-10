package com.digma.watchapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.digma.watchapp.model.HealthMetrics
import com.digma.watchapp.protocol.DigmaProtocolCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Standard vendor service & characteristic candidates for Digma / Telink
        private val CANDIDATE_WRITE_CHARS = listOf(
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1911"),
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        )
        private val CANDIDATE_NOTIFY_CHARS = listOf(
            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1912"),
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        )
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(Dispatchers.IO)

    private var activeGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _healthMetrics = MutableStateFlow(HealthMetrics())
    val healthMetrics: StateFlow<HealthMetrics> = _healthMetrics.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<DigmaProtocolCodec.IncomingPacket>(extraBufferCapacity = 64)
    val incomingEvents: SharedFlow<DigmaProtocolCodec.IncomingPacket> = _incomingEvents.asSharedFlow()

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val device = gatt?.device ?: return
            val name = device.name ?: "Digma Smartline D3"
            val address = device.address

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to $address. Discovering services...")
                    _connectionState.value = BleConnectionState.Connected(address, name)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from $address (status=$status)")
                    cleanup()
                    _connectionState.value = BleConnectionState.Disconnected
                }
                else -> Unit
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                Log.e(TAG, "Service discovery failed with status $status")
                return
            }

            resolveCharacteristics(gatt.services)

            // Request larger MTU
            gatt.requestMtu(128)

            // Subscribe to notifications
            notifyCharacteristic?.let { char ->
                enableNotification(gatt, char)
            }

            // Immediately synchronize time upon connection
            scope.launch {
                syncTime()
                queryBattery()
                requestActivity()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingData(value)
        }

        @Deprecated("Deprecated for API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val data = characteristic?.value ?: return
            handleIncomingData(data)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _connectionState.value = BleConnectionState.Error("Неверный Bluetooth адрес")
            return
        }

        disconnect()
        _connectionState.value = BleConnectionState.Connecting(address, device.name ?: "Digma Watch")

        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "Error closing GATT", e)
        } finally {
            cleanup()
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    private fun cleanup() {
        activeGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
    }

    private fun resolveCharacteristics(services: List<BluetoothGattService>) {
        for (service in services) {
            for (char in service.characteristics) {
                val uuid = char.uuid
                val props = char.properties

                if (CANDIDATE_WRITE_CHARS.contains(uuid) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                ) {
                    if (writeCharacteristic == null || CANDIDATE_WRITE_CHARS.contains(uuid)) {
                        writeCharacteristic = char
                    }
                }

                if (CANDIDATE_NOTIFY_CHARS.contains(uuid) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                ) {
                    if (notifyCharacteristic == null || CANDIDATE_NOTIFY_CHARS.contains(uuid)) {
                        notifyCharacteristic = char
                    }
                }
            }
        }
        Log.d(TAG, "Resolved Write: ${writeCharacteristic?.uuid}, Notify: ${notifyCharacteristic?.uuid}")
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleIncomingData(data: ByteArray) {
        val event = DigmaProtocolCodec.parseIncomingPacket(data) ?: return
        Log.d(TAG, "Received packet: $event")

        scope.launch {
            _incomingEvents.emit(event)

            when (event) {
                is DigmaProtocolCodec.IncomingPacket.Battery -> {
                    _healthMetrics.value = _healthMetrics.value.copy(
                        batteryPercent = event.percent,
                        lastSyncTimestamp = System.currentTimeMillis()
                    )
                }
                is DigmaProtocolCodec.IncomingPacket.Activity -> {
                    _healthMetrics.value = _healthMetrics.value.copy(
                        steps = event.steps,
                        caloriesKcal = event.calories,
                        distanceMeters = event.distanceMeters,
                        lastSyncTimestamp = System.currentTimeMillis()
                    )
                }
                is DigmaProtocolCodec.IncomingPacket.HeartRate -> {
                    _healthMetrics.value = _healthMetrics.value.copy(
                        heartRateBpm = event.heartRateBpm,
                        spO2Percent = event.spO2Percent,
                        lastSyncTimestamp = System.currentTimeMillis()
                    )
                }
                else -> Unit
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendPacket(packet: ByteArray): Boolean {
        val gatt = activeGatt ?: return false
        val char = writeCharacteristic ?: return false

        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == 0
        } else {
            char.value = packet
            gatt.writeCharacteristic(char)
        }
    }

    fun syncTime(): Boolean {
        return sendPacket(DigmaProtocolCodec.buildTimeSyncPacket())
    }

    fun queryBattery(): Boolean {
        return sendPacket(DigmaProtocolCodec.buildBatteryQueryPacket())
    }

    fun requestActivity(): Boolean {
        return sendPacket(DigmaProtocolCodec.buildActivityRequestPacket())
    }

    fun findWatch(): Boolean {
        return sendPacket(DigmaProtocolCodec.buildFindWatchPacket())
    }

    fun toggleHeartRateMeasurement(start: Boolean): Boolean {
        _healthMetrics.value = _healthMetrics.value.copy(isMeasuringHeartRate = start)
        return sendPacket(DigmaProtocolCodec.buildHeartRateControlPacket(start))
    }

    fun sendNotification(type: DigmaProtocolCodec.NotificationType, title: String, text: String): Boolean {
        return sendPacket(DigmaProtocolCodec.buildNotificationPacket(type, title, text))
    }
}
