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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Standard Bluetooth SIG services that must NOT be picked as vendor services
        private fun isStandardSigService(uuid: UUID): Boolean {
            val s = uuid.toString().lowercase()
            return s.startsWith("000018") && s.endsWith("-0000-1000-8000-00805f9b34fb")
        }

        // Known vendor services on Telink / AdorHealth / Smartline watches
        private val KNOWN_VENDOR_SERVICES = listOf(
            "0000fff0-0000-1000-8000-00805f9b34fb",
            "00010203-0405-0607-0809-0a0b0c0d1910",
            "0000fee7-0000-1000-8000-00805f9b34fb",
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000ff00-0000-1000-8000-00805f9b34fb",
            "0000ffff-0000-1000-8000-00805f9b34fb"
        )
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(Dispatchers.IO)
    private val writeMutex = Mutex()

    private var activeGatt: BluetoothGatt? = null
    var writeCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var notifyCharacteristic: BluetoothGattCharacteristic? = null
        private set

    @Volatile
    private var servicesDiscoveredStarted = false

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _healthMetrics = MutableStateFlow(HealthMetrics())
    val healthMetrics: StateFlow<HealthMetrics> = _healthMetrics.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<DigmaProtocolCodec.IncomingPacket>(extraBufferCapacity = 64)
    val incomingEvents: SharedFlow<DigmaProtocolCodec.IncomingPacket> = _incomingEvents.asSharedFlow()

    private val _diagnosticLogs = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLogs: StateFlow<List<String>> = _diagnosticLogs.asStateFlow()

    fun logDiagnostic(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val entry = "[$time] $message"
        Log.d(TAG, entry)
        val current = _diagnosticLogs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 100) current.removeAt(current.size - 1)
        _diagnosticLogs.value = current
    }

    fun clearDiagnosticLogs() {
        _diagnosticLogs.value = emptyList()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val device = gatt?.device ?: return
            val name = device.name ?: "Digma Smartline D3"
            val address = device.address

            logDiagnostic("onConnectionStateChange: newState=$newState, status=$status")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        _connectionState.value = BleConnectionState.Connecting(address, "$name (поиск сервисов...)")
                        logDiagnostic("BLE связь установлена. Запуск discoverServices()...")
                        // Wait a short moment before discovering services (improves stability on Android)
                        scope.launch {
                            delay(300)
                            gatt.discoverServices()
                        }
                    } else {
                        logDiagnostic("Ошибка подключения: status=$status")
                        disconnect()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logDiagnostic("Отключено от $address (status=$status)")
                    cleanup()
                    _connectionState.value = BleConnectionState.Disconnected
                }
                else -> Unit
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val device = gatt?.device ?: return
            val name = device.name ?: "Digma Smartline D3"
            val address = device.address

            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                logDiagnostic("Ошибка discoverServices: status=$status")
                _connectionState.value = BleConnectionState.Error("Ошибка обнаружения сервисов ($status)")
                return
            }

            logDiagnostic("Обнаружено сервисов: ${gatt.services.size}")
            resolveVendorCharacteristics(gatt.services)

            val writeChar = writeCharacteristic
            val notifyChar = notifyCharacteristic

            if (writeChar == null) {
                logDiagnostic("ВНИМАНИЕ: Не найдена характеристика для записи (Write)!")
            } else {
                logDiagnostic("Выбрана характеристика Write (TX): ${writeChar.uuid}")
            }

            if (notifyChar == null) {
                logDiagnostic("ВНИМАНИЕ: Не найдена характеристика для уведомлений (Notify)!")
            } else {
                logDiagnostic("Выбрана характеристика Notify (RX): ${notifyChar.uuid}")
            }

            // Step 2: Subscribe to notifications via descriptor 0x2902
            if (notifyChar != null) {
                logDiagnostic("Подписка на уведомления через дескриптор 0x2902...")
                enableNotification(gatt, notifyChar)
            } else {
                // If no notify char, immediately mark connected and sync
                _connectionState.value = BleConnectionState.Connected(address, name)
                triggerInitialSync()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            val device = gatt?.device
            val name = device?.name ?: "Digma Smartline D3"
            val address = device?.address ?: ""

            logDiagnostic("onDescriptorWrite: status=$status (${if (status == 0) "Успешно" else "Ошибка"})")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Connected(address, name)
                logDiagnostic("Готово! Канал связи открыт. Запуск первичной синхронизации...")
                triggerInitialSync()
            } else {
                logDiagnostic("Сбой записи дескриптора, пробуем начать синхронизацию...")
                _connectionState.value = BleConnectionState.Connected(address, name)
                triggerInitialSync()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            logDiagnostic("onCharacteristicWrite [${characteristic?.uuid}]: status=$status (${if (success) "Успешно" else "Сбой $status"})")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingData(characteristic.uuid, value)
        }

        @Deprecated("Deprecated for API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val char = characteristic ?: return
            val data = char.value ?: return
            handleIncomingData(char.uuid, data)
        }
    }

    fun triggerInitialSync() {
        scope.launch {
            _isSyncing.value = true
            try {
                delay(300)
                logDiagnostic("Синхронизация времени (мультиформат)...")
                syncTime()
                delay(500)
                logDiagnostic("Запрос уровня заряда батареи...")
                queryBattery()
                delay(500)
                logDiagnostic("Запрос данных активности (шаги, калории)...")
                requestActivity()
                delay(1200)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            logDiagnostic("Bluetooth выключен!")
            return
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _connectionState.value = BleConnectionState.Error("Неверный Bluetooth адрес")
            return
        }

        disconnect()
        _connectionState.value = BleConnectionState.Connecting(address, device.name ?: "Digma Watch")
        logDiagnostic("Подключение к $address (${device.name ?: "Unknown"})...")

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
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT", e)
        } finally {
            cleanup()
            _connectionState.value = BleConnectionState.Disconnected
            logDiagnostic("Отключено.")
        }
    }

    private fun cleanup() {
        activeGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
    }

    private fun resolveVendorCharacteristics(services: List<BluetoothGattService>) {
        writeCharacteristic = null
        notifyCharacteristic = null

        // 1. Log all discovered services for diagnosis
        for (service in services) {
            val sUuid = service.uuid.toString().lowercase()
            logDiagnostic("Service: $sUuid")
            for (char in service.characteristics) {
                val cUuid = char.uuid.toString().lowercase()
                val props = char.properties
                val isWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                        (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                val isNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                        (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                logDiagnostic("  └─ Char: $cUuid [Write=$isWrite, Notify=$isNotify, Props=0x${Integer.toHexString(props)}]")
            }
        }

        // 2. Known vendor UUID patterns (FFF0, Telink SPP, FEE7, etc.)
        for (knownServiceUuid in KNOWN_VENDOR_SERVICES) {
            val matchingService = services.firstOrNull { it.uuid.toString().equals(knownServiceUuid, ignoreCase = true) }
            if (matchingService != null) {
                logDiagnostic("Найден известный вендорный сервис: ${matchingService.uuid}")
                findCharacteristicsInService(matchingService)
                if (writeCharacteristic != null && notifyCharacteristic != null) {
                    return
                }
            }
        }

        // 3. Search known characteristic prefixes across non-SIG services
        val nonSigServices = services.filterNot { service ->
            isStandardSigService(service.uuid)
        }

        for (service in nonSigServices) {
            findCharacteristicsInService(service)
            if (writeCharacteristic != null && notifyCharacteristic != null) {
                return
            }
        }

        // 4. Ultimate fallback: inspect any service
        if (writeCharacteristic == null || notifyCharacteristic == null) {
            for (service in services) {
                findCharacteristicsInService(service)
                if (writeCharacteristic != null && notifyCharacteristic != null) break
            }
        }
    }

    private fun findCharacteristicsInService(service: BluetoothGattService) {
        val sUuid = service.uuid.toString().lowercase()

        // Pass 1: Handle FFF0 service with strict property verification
        // Standard FFF0 profile: FFF2 is WRITE (TX), FFF1 is NOTIFY (RX)
        if (sUuid.contains("fff0")) {
            for (char in service.characteristics) {
                val u = char.uuid.toString().lowercase()
                val props = char.properties
                val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                        (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                        (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)

                if (u.contains("fff2") && canWrite && writeCharacteristic == null) {
                    writeCharacteristic = char
                    logDiagnostic("Найдена FFF2 [Write TX]: ${char.uuid}")
                }
                if (u.contains("fff1") && canNotify && notifyCharacteristic == null) {
                    notifyCharacteristic = char
                    logDiagnostic("Найдена FFF1 [Notify RX]: ${char.uuid}")
                }
            }
        }

        // Pass 2: Match known vendor characteristic patterns with capability checks
        for (char in service.characteristics) {
            val u = char.uuid.toString().lowercase()
            val props = char.properties
            val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)

            // Write targets: FFF2 (standard), 1911 (Telink), 6e400002 (Nordic RX), fea2
            if (canWrite && writeCharacteristic == null) {
                if (u.contains("fff2") || u.contains("1911") || u.contains("fea2") || u.contains("6e400002") || u.contains("2d01")) {
                    writeCharacteristic = char
                    logDiagnostic("Найдена Write char по шаблону: ${char.uuid}")
                }
            }

            // Notify targets: FFF1 (standard), 1912 (Telink), 6e400003 (Nordic TX), fea1
            if (canNotify && notifyCharacteristic == null && char != writeCharacteristic) {
                if (u.contains("fff1") || u.contains("1912") || u.contains("fea1") || u.contains("6e400003") || u.contains("2d02")) {
                    notifyCharacteristic = char
                    logDiagnostic("Найдена Notify char по шаблону: ${char.uuid}")
                }
            }
        }

        // Pass 3: Fallback strictly by capability (first write-capable, first notify-capable)
        for (char in service.characteristics) {
            val props = char.properties
            val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)

            if (canWrite && writeCharacteristic == null && char != notifyCharacteristic) {
                writeCharacteristic = char
                logDiagnostic("Fallback Write char назначена по свойствам: ${char.uuid}")
            }
            if (canNotify && notifyCharacteristic == null && char != writeCharacteristic) {
                notifyCharacteristic = char
                logDiagnostic("Fallback Notify char назначена по свойствам: ${char.uuid}")
            }
        }
    }

    /**
     * Diagnostic feature: allows swapping write and notify characteristics dynamically
     * if a non-standard device revision has inverted them.
     */
    fun swapCharacteristics(): Boolean {
        val oldWrite = writeCharacteristic
        val oldNotify = notifyCharacteristic
        if (oldWrite == null && oldNotify == null) {
            logDiagnostic("Сбой swap: характеристики не определены")
            return false
        }

        writeCharacteristic = oldNotify
        notifyCharacteristic = oldWrite
        logDiagnostic("РУЧНОЙ SWAP ХАРАКТЕРИСТИК:\n  Write (TX) -> ${writeCharacteristic?.uuid}\n  Notify (RX) -> ${notifyCharacteristic?.uuid}")

        val gatt = activeGatt
        val newNotify = notifyCharacteristic
        if (gatt != null && newNotify != null) {
            enableNotification(gatt, newNotify)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        val success = gatt.setCharacteristicNotification(char, true)
        logDiagnostic("setCharacteristicNotification: $success")

        val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor != null) {
            val isIndicate = (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            descriptor.value = if (isIndicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val descWriteSuccess = gatt.writeDescriptor(descriptor)
            logDiagnostic("writeDescriptor (0x2902): $descWriteSuccess")

            // Timeout safety fallback: if onDescriptorWrite doesn't arrive within 800ms
            scope.launch {
                delay(800)
                if (_connectionState.value !is BleConnectionState.Connected) {
                    logDiagnostic("Таймаут ожидания дескриптора (800ms). Форсируем Connected и запуск синхронизации...")
                    val device = gatt.device
                    _connectionState.value = BleConnectionState.Connected(device.address, device.name ?: "Digma Smartline D3")
                    triggerInitialSync()
                }
            }
        } else {
            logDiagnostic("Дескриптор 0x2902 не найден на характеристике!")
            // Direct transition if descriptor absent
            _connectionState.value = BleConnectionState.Connected(gatt.device.address, gatt.device.name ?: "Digma Watch")
            triggerInitialSync()
        }
    }

    private fun handleIncomingData(charUuid: UUID, data: ByteArray) {
        val hexStr = data.joinToString(" ") { String.format("%02X", it) }
        logDiagnostic("RX [$charUuid]: $hexStr")

        val event = DigmaProtocolCodec.parseIncomingPacket(data)
        if (event == null) {
            logDiagnostic("RX: Пакет не распознан протоколом (len=${data.size})")
            return
        }

        // Any incoming packet confirms communication with watch
        _healthMetrics.value = _healthMetrics.value.copy(lastSyncTimestamp = System.currentTimeMillis())

        logDiagnostic("RX Событие: $event")

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
        val gatt = activeGatt
        val char = writeCharacteristic

        if (gatt == null) {
            logDiagnostic("TX Сбой: GATT не подключен")
            return false
        }
        if (char == null) {
            logDiagnostic("TX Сбой: writeCharacteristic = null")
            return false
        }

        val hexStr = packet.joinToString(" ") { String.format("%02X", it) }
        logDiagnostic("TX [${char.uuid}]: $hexStr")

        val canWriteNoResp = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        char.writeType = if (canWriteNoResp) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        char.value = packet

        return try {
            val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, packet, char.writeType)
            } else {
                if (gatt.writeCharacteristic(char)) 0 else -1
            }
            logDiagnostic("TX Результат: code=$status (type=${if (char.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) "NO_RESP" else "DEFAULT"})")
            status == 0
        } catch (e: Exception) {
            logDiagnostic("TX Исключение: ${e.localizedMessage}")
            false
        }
    }

    fun syncTime(): Boolean {
        _healthMetrics.value = _healthMetrics.value.copy(lastSyncTimestamp = System.currentTimeMillis())
        val pktA = DigmaProtocolCodec.buildTimeSyncPacket()
        val success = sendPacket(pktA)
        // Multi-format: send 1-byte year format and 20-byte padded format
        scope.launch {
            delay(120)
            val pktB = DigmaProtocolCodec.buildTimeSyncPacketShort()
            sendPacket(pktB)
            delay(120)
            val pktPadded = DigmaProtocolCodec.padToLength(pktA, 20)
            sendPacket(pktPadded)
        }
        return success
    }

    fun syncAll() {
        logDiagnostic("Запуск полной синхронизации...")
        triggerInitialSync()
    }

    fun queryBattery(): Boolean {
        val raw = DigmaProtocolCodec.buildBatteryQueryPacket()
        val success = sendPacket(raw)
        scope.launch {
            delay(120)
            val padded = DigmaProtocolCodec.padToLength(raw, 16)
            sendPacket(padded)
        }
        return success
    }

    fun requestActivity(): Boolean {
        val raw = DigmaProtocolCodec.buildActivityRequestPacket()
        val success = sendPacket(raw)
        scope.launch {
            delay(120)
            val padded = DigmaProtocolCodec.padToLength(raw, 16)
            sendPacket(padded)
        }
        return success
    }

    fun findWatch(): Boolean {
        logDiagnostic("Команда: Поиск часов (вибрация)...")
        val pkt1 = DigmaProtocolCodec.buildFindWatchPacket()
        val success = sendPacket(pkt1)
        scope.launch {
            delay(120)
            val pkt2 = DigmaProtocolCodec.buildFindWatchPacketLefun()
            sendPacket(pkt2)
            delay(120)
            val pktPadded = DigmaProtocolCodec.padToLength(pkt1, 20)
            sendPacket(pktPadded)
        }
        return success
    }

    fun toggleHeartRateMeasurement(start: Boolean): Boolean {
        _healthMetrics.value = _healthMetrics.value.copy(isMeasuringHeartRate = start)
        logDiagnostic("Команда: Замер пульса ($start)...")
        val pkt1 = DigmaProtocolCodec.buildHeartRateControlPacket(start)
        val success = sendPacket(pkt1)
        scope.launch {
            delay(120)
            val pkt2 = DigmaProtocolCodec.buildHeartRateControlPacketLefun(start)
            sendPacket(pkt2)
            delay(120)
            val pktPadded = DigmaProtocolCodec.padToLength(pkt1, 20)
            sendPacket(pktPadded)
        }
        return success
    }

    fun sendNotification(type: DigmaProtocolCodec.NotificationType, title: String, text: String): Boolean {
        logDiagnostic("Команда: Отправка push-уведомления ($type: $title)...")
        val packet = DigmaProtocolCodec.buildNotificationPacket(type, title, text)
        return sendPacket(packet)
    }

    // --- Diagnostic Direct Test Methods ---

    fun testFindWatchAdor(): Boolean {
        logDiagnostic("ТЕСТ: Find Watch (AdorHealth 0x09 0x01 0x01 0x08)")
        return sendPacket(DigmaProtocolCodec.buildFindWatchPacket())
    }

    fun testFindWatchLefun(): Boolean {
        logDiagnostic("ТЕСТ: Find Watch (Lefun/FT100 0x04 0x09 0x90)")
        return sendPacket(DigmaProtocolCodec.buildFindWatchPacketLefun())
    }

    fun testFindWatchPadded(): Boolean {
        logDiagnostic("ТЕСТ: Find Watch (20-байт фрейм с нулями)")
        return sendPacket(DigmaProtocolCodec.padToLength(DigmaProtocolCodec.buildFindWatchPacket(), 20))
    }
}
