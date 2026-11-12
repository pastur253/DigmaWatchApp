package com.digma.watchapp.ble

import android.Manifest
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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.digma.watchapp.data.HealthHistoryStore
import com.digma.watchapp.model.DailyActivityPoint
import com.digma.watchapp.model.HealthMetrics
import com.digma.watchapp.model.HeartRateSample
import com.digma.watchapp.model.HourlyActivityPoint
import com.digma.watchapp.model.WatchDevice
import com.digma.watchapp.protocol.DigmaProtocolCodec
import com.digma.watchapp.protocol.SxrKeepFitCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class WatchProtocolMode(val label: String) {
    AUTO("Авто"),
    SXR_KEEPFIT("SXR 56ff"),
    AB_LEGACY("0xAB")
}

class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val MAX_DIAGNOSTIC_LOGS = 400
        private const val GATT_OP_TIMEOUT_MS = 1500L
        private const val SXR_KEEPALIVE_INTERVAL_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val SCAN_WAKEUP_TIMEOUT_MS = 4_000L
        private const val MAX_FAILED_KEEPALIVES = 3
        private val RECONNECT_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L, 20_000L, 30_000L)

        private const val PREFS_BLE = "digma_ble_prefs"
        private const val KEY_SAVED_ADDRESS = "saved_device_address"
        private const val KEY_SAVED_NAME = "saved_device_name"
        private const val KEY_MANUAL_DISCONNECT = "manual_disconnect"

        private val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE_UUID: UUID =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHAR_UUID: UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_SERVICE_UUID: UUID =
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT_CHAR_UUID: UUID =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        // Standard Bluetooth SIG services that must NOT be picked as vendor services
        private fun isStandardSigService(uuid: UUID): Boolean {
            val s = uuid.toString().lowercase()
            return s.startsWith("000018") && s.endsWith("-0000-1000-8000-00805f9b34fb")
        }

        // Known vendor services ordered with SXR KeepFit (000056ff) first, then Telink / Nordic / FFF0
        private val KNOWN_VENDOR_SERVICES = listOf(
            "000056ff-0000-1000-8000-00805f9b34fb",
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val logLock = Any()

    private val commandQueue = Channel<Pair<Long, suspend () -> Unit>>(Channel.UNLIMITED)

    private var activeGatt: BluetoothGatt? = null
    var writeCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var notifyCharacteristic: BluetoothGattCharacteristic? = null
        private set
    private var rawNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var standardBatteryCharacteristic: BluetoothGattCharacteristic? = null
    private var standardHeartRateCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var connectionGeneration = 0L

    private var setupJob: Job? = null
    private var keepaliveJob: Job? = null
    private var hrFallbackJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectTimeoutJob: Job? = null
    @Volatile
    private var receivedHrSampleInCurrentSession = false
    @Volatile
    private var receivedHrHistoryInCurrentSync = false
    @Volatile
    private var receivedActivityHistoryInCurrentSync = false
    @Volatile
    private var confirmedThreeByteSportLayout: Boolean? = null
    @Volatile
    private var lastStepCounterRefreshMs = 0L
    @Volatile
    private var isPassiveAutoConnecting = false
    @Volatile
    private var reconnectAttempt = 0
    @Volatile
    private var consecutiveFailedKeepalives = 0

    private val blePrefs = context.getSharedPreferences(PREFS_BLE, Context.MODE_PRIVATE)
    @Volatile
    private var manualDisconnect = blePrefs.getBoolean(KEY_MANUAL_DISCONNECT, false)

    @Volatile
    private var pendingDescriptorWrite: CompletableDeferred<Int>? = null
    @Volatile
    private var pendingCharWrite: CompletableDeferred<Int>? = null
    @Volatile
    private var pendingCharRead: CompletableDeferred<Pair<Int, ByteArray?>>? = null

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

    private val _gattSummary = MutableStateFlow<List<String>>(emptyList())
    val gattSummary: StateFlow<List<String>> = _gattSummary.asStateFlow()

    private val _activeWriteUuid = MutableStateFlow<String?>(null)
    val activeWriteUuid: StateFlow<String?> = _activeWriteUuid.asStateFlow()

    private val _activeNotifyUuid = MutableStateFlow<String?>(null)
    val activeNotifyUuid: StateFlow<String?> = _activeNotifyUuid.asStateFlow()

    private val _protocolMode = MutableStateFlow(WatchProtocolMode.AUTO)
    val protocolMode: StateFlow<WatchProtocolMode> = _protocolMode.asStateFlow()

    private val _detectedProtocol = MutableStateFlow(WatchProtocolMode.AB_LEGACY)
    val detectedProtocol: StateFlow<WatchProtocolMode> = _detectedProtocol.asStateFlow()

    private val _deviceFirmware = MutableStateFlow<String?>(null)
    val deviceFirmware: StateFlow<String?> = _deviceFirmware.asStateFlow()

    private val _savedDevice = MutableStateFlow(loadSavedDeviceFromPrefs())
    val savedDevice: StateFlow<WatchDevice?> = _savedDevice.asStateFlow()

    private val _isAutoReconnecting = MutableStateFlow(false)
    val isAutoReconnecting: StateFlow<Boolean> = _isAutoReconnecting.asStateFlow()

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> {
                    logDiagnostic("Bluetooth выключен системой — сброс активного GATT")
                    reconnectJob?.cancel()
                    connectTimeoutJob?.cancel()
                    _isAutoReconnecting.value = false
                    teardownActiveGatt(closeGatt = true)
                    _connectionState.value = BleConnectionState.Disconnected
                }
                BluetoothAdapter.STATE_ON -> {
                    logDiagnostic("Bluetooth включён — проверка автоподключения")
                    if (!manualDisconnect && _savedDevice.value != null) {
                        scope.launch {
                            delay(600L)
                            connectSavedDeviceIfNeeded()
                        }
                    }
                }
            }
        }
    }

    private val historyStore = HealthHistoryStore(context)
    val heartRateHistory: StateFlow<List<HeartRateSample>> = historyStore.heartRateHistory
    val hourlyActivity: StateFlow<List<HourlyActivityPoint>> = historyStore.hourlyActivity
    val dailyActivity: StateFlow<List<DailyActivityPoint>> = historyStore.dailyActivity

    fun clearHealthHistory() {
        historyStore.clearAll()
    }

    private var notificationUidCounter = 1

    init {
        ContextCompat.registerReceiver(
            context,
            btStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        scope.launch {
            for ((gen, action) in commandQueue) {
                if (gen == connectionGeneration) {
                    try {
                        action()
                    } catch (e: Exception) {
                        Log.e(TAG, "Command execution error", e)
                    }
                }
            }
        }
    }

    private fun loadSavedDeviceFromPrefs(): WatchDevice? {
        val address = blePrefs.getString(KEY_SAVED_ADDRESS, null) ?: return null
        val name = blePrefs.getString(KEY_SAVED_NAME, null) ?: "Digma Smartline D3"
        return WatchDevice(name = name, address = address, rssi = 0, isConnected = false)
    }

    private fun saveDeviceToPrefs(address: String, name: String?) {
        val existingName = blePrefs.getString(KEY_SAVED_NAME, null)
        val resolvedName = name?.takeIf { it.isNotBlank() && it != "Unknown" && it != "Digma Watch" }
            ?: existingName
            ?: "Digma Smartline D3"
        blePrefs.edit()
            .putString(KEY_SAVED_ADDRESS, address)
            .putString(KEY_SAVED_NAME, resolvedName)
            .putBoolean(KEY_MANUAL_DISCONNECT, false)
            .apply()
        manualDisconnect = false
        _savedDevice.value = WatchDevice(
            name = resolvedName,
            address = address,
            rssi = 0,
            isConnected = _connectionState.value is BleConnectionState.Connected
        )
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun effectiveProtocol(): WatchProtocolMode {
        val selected = _protocolMode.value
        return if (selected == WatchProtocolMode.AUTO) _detectedProtocol.value else selected
    }

    fun setProtocolMode(mode: WatchProtocolMode) {
        _protocolMode.value = mode
        logDiagnostic("Режим протокола изменён на: ${mode.label} (активный=${effectiveProtocol().label})")
    }

    fun logDiagnostic(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val entry = "[$time] $message"
        Log.d(TAG, entry)
        synchronized(logLock) {
            val current = _diagnosticLogs.value.toMutableList()
            current.add(0, entry)
            if (current.size > MAX_DIAGNOSTIC_LOGS) {
                current.subList(MAX_DIAGNOSTIC_LOGS, current.size).clear()
            }
            _diagnosticLogs.value = current
        }
    }

    fun clearDiagnosticLogs() {
        synchronized(logLock) {
            _diagnosticLogs.value = emptyList()
        }
    }

    private fun appendGattSummary(line: String) {
        synchronized(logLock) {
            _gattSummary.value = _gattSummary.value + line
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val nonNullGatt = gatt ?: return
            val device = nonNullGatt.device ?: return
            val name = device.name ?: _savedDevice.value?.name ?: "Digma Smartline D3"
            val address = device.address

            if (activeGatt != null && nonNullGatt !== activeGatt) {
                logDiagnostic("Игнорируем callback устаревшего GATT ($address, state=$newState, status=$status)")
                try {
                    nonNullGatt.close()
                } catch (_: Exception) {
                }
                return
            }

            logDiagnostic("onConnectionStateChange: newState=$newState, status=$status")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectTimeoutJob?.cancel()
                    connectTimeoutJob = null
                    isPassiveAutoConnecting = false
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        reconnectJob?.cancel()
                        reconnectJob = null
                        reconnectAttempt = 0
                        _isAutoReconnecting.value = false
                        saveDeviceToPrefs(address, device.name)
                        val gen = connectionGeneration
                        _connectionState.value = BleConnectionState.Connecting(address, "$name (поиск сервисов...)")
                        logDiagnostic("BLE связь установлена. Запуск discoverServices()...")
                        scope.launch {
                            delay(300)
                            if (gen == connectionGeneration && activeGatt === nonNullGatt) {
                                nonNullGatt.discoverServices()
                            }
                        }
                    } else {
                        logDiagnostic("Ошибка подключения: status=$status")
                        teardownActiveGatt(closeGatt = true, explicitGatt = nonNullGatt)
                        _connectionState.value = BleConnectionState.Disconnected
                        if (!manualDisconnect) {
                            scheduleReconnect("Ошибка подключения (status=$status)")
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectTimeoutJob?.cancel()
                    connectTimeoutJob = null
                    isPassiveAutoConnecting = false
                    logDiagnostic("Отключено от $address (status=$status)")
                    teardownActiveGatt(closeGatt = true, explicitGatt = nonNullGatt)
                    _connectionState.value = BleConnectionState.Disconnected
                    if (!manualDisconnect) {
                        scheduleReconnect("Разрыв связи (status=$status)")
                    }
                }
                else -> Unit
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val nonNullGatt = gatt ?: return
            val device = nonNullGatt.device ?: return
            val name = device.name ?: _savedDevice.value?.name ?: "Digma Smartline D3"
            val address = device.address

            if (activeGatt != null && nonNullGatt !== activeGatt) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                logDiagnostic("Ошибка discoverServices: status=$status")
                _connectionState.value = BleConnectionState.Error("Ошибка обнаружения сервисов ($status)")
                if (!manualDisconnect) {
                    teardownActiveGatt(closeGatt = true, explicitGatt = nonNullGatt)
                    scheduleReconnect("Ошибка discoverServices ($status)")
                }
                return
            }

            logDiagnostic("Обнаружено сервисов: ${gatt.services.size}")
            resolveVendorCharacteristics(gatt.services)

            val writeChar = writeCharacteristic
            val notifyChar = notifyCharacteristic
            _activeWriteUuid.value = writeChar?.uuid?.toString()
            _activeNotifyUuid.value = notifyChar?.uuid?.toString()

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

            val gen = connectionGeneration
            setupJob?.cancel()
            setupJob = scope.launch {
                if (notifyChar != null) {
                    logDiagnostic("Подписка на уведомления через дескриптор 0x2902...")
                    enableNotificationSuspend(gatt, notifyChar)
                }

                val rawNotifyChar = rawNotifyCharacteristic
                if (rawNotifyChar != null && rawNotifyChar != notifyChar &&
                    gen == connectionGeneration && activeGatt === gatt
                ) {
                    logDiagnostic("Подписка на доп. канал SXR 33F6...")
                    enableNotificationSuspend(gatt, rawNotifyChar)
                }

                val standardHrChar = standardHeartRateCharacteristic
                if (standardHrChar != null && standardHrChar != notifyChar &&
                    standardHrChar != rawNotifyChar &&
                    gen == connectionGeneration && activeGatt === gatt
                ) {
                    logDiagnostic("Подписка на стандартный BLE HR (0x2A37)...")
                    enableNotificationSuspend(gatt, standardHrChar)
                }

                if (gen != connectionGeneration || activeGatt !== gatt) return@launch

                readStandardBatteryIfAvailable(gatt)

                if (gen != connectionGeneration || activeGatt !== gatt) return@launch

                _connectionState.value = BleConnectionState.Connected(address, name)
                logDiagnostic("Готово! Протокол: ${effectiveProtocol().label}. Запуск синхронизации...")
                runInitialSyncSuspend(gen)

                if (effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
                    startKeepaliveLoop(gen)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            val text = "onDescriptorWrite [${descriptor?.characteristic?.uuid}]: status=$status (${if (status == 0) "Успешно" else "Ошибка"})"
            logDiagnostic(text)
            appendGattSummary(text)
            pendingDescriptorWrite?.complete(status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            logDiagnostic("onCharacteristicWrite [${characteristic?.uuid}]: status=$status (${if (success) "Успешно" else "Сбой $status"})")
            pendingCharWrite?.complete(status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            pendingCharRead?.complete(status to value)
        }

        @Deprecated("Deprecated for API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            pendingCharRead?.complete(status to characteristic?.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingData(characteristic.uuid, value)
        }

        @Deprecated("Deprecated for API 33")
        @Suppress("DEPRECATION")
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
        val gen = connectionGeneration
        enqueueCommand(gen) {
            runInitialSyncSuspend(gen)
        }
    }

    private suspend fun runInitialSyncSuspend(gen: Long) {
        if (gen != connectionGeneration) return
        _isSyncing.value = true
        receivedHrHistoryInCurrentSync = false
        receivedActivityHistoryInCurrentSync = false
        try {
            delay(200)
            activeGatt?.let { readStandardBatteryIfAvailable(it) }
            if (gen != connectionGeneration) return
            if (effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
                logDiagnostic("SXR: Инициализация канала (AppId, DeviceInfo, Время, Профиль, АКБ, Активность, Пульс)...")
                sendPacketsSuspend(
                    listOf(
                        SxrKeepFitCodec.buildAppIdPacket(),
                        SxrKeepFitCodec.buildDeviceInfoQueryPacket(),
                        SxrKeepFitCodec.buildTimeSyncPacket(),
                        SxrKeepFitCodec.buildUserInfoPacket(),
                        SxrKeepFitCodec.buildBatteryQueryPacket(),
                        SxrKeepFitCodec.buildActivityQueryPacket(),
                        SxrKeepFitCodec.buildActivityHistoryQueryPacket(0L),
                        SxrKeepFitCodec.buildBandFunctionQueryPacket(),
                        SxrKeepFitCodec.buildHeartRateHistoryQueryPacket(0L)
                    ),
                    delayBetweenMs = 80L
                )
                delay(750)
                if (gen == connectionGeneration && effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
                    val midnightEpoch = SxrKeepFitCodec.localMidnightEpochSeconds()
                    val dayAgoEpoch = (SxrKeepFitCodec.localEpochSeconds() - 86_400L).coerceAtLeast(0L)
                    if (!receivedActivityHistoryInCurrentSync) {
                        sendPacketsSuspend(
                            listOf(SxrKeepFitCodec.buildActivityHistoryQueryPacket(midnightEpoch)),
                            delayBetweenMs = 0L
                        )
                    }
                    if (!receivedHrHistoryInCurrentSync) {
                        logDiagnostic("SXR: Повторный запрос истории пульса (за сегодня и 24ч)...")
                        sendPacketsSuspend(
                            listOf(
                                SxrKeepFitCodec.buildHeartRateHistoryQueryPacket(midnightEpoch),
                                SxrKeepFitCodec.buildHeartRateHistoryQueryPacket(dayAgoEpoch)
                            ),
                            delayBetweenMs = 120L
                        )
                        delay(450)
                    }
                }
            } else {
                logDiagnostic("0xAB: Синхронизация времени, АКБ, активности...")
                syncTimeLegacySuspend()
                delay(200)
                queryBatteryLegacySuspend()
                delay(200)
                requestActivityLegacySuspend()
            }
            delay(400)
        } finally {
            _isSyncing.value = false
        }
    }

    private fun startKeepaliveLoop(gen: Long) {
        keepaliveJob?.cancel()
        consecutiveFailedKeepalives = 0
        keepaliveJob = scope.launch {
            while (isActive && gen == connectionGeneration && activeGatt != null) {
                delay(SXR_KEEPALIVE_INTERVAL_MS)
                if (gen != connectionGeneration || activeGatt == null) break
                if (effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
                    val ok = sendPacketsSuspend(listOf(SxrKeepFitCodec.buildKeepalivePacket()), delayBetweenMs = 0L)
                    if (ok) {
                        consecutiveFailedKeepalives = 0
                    } else {
                        consecutiveFailedKeepalives++
                        if (consecutiveFailedKeepalives >= MAX_FAILED_KEEPALIVES &&
                            gen == connectionGeneration &&
                            activeGatt != null
                        ) {
                            logDiagnostic("Keepalive не отвечает ($MAX_FAILED_KEEPALIVES раза), сброс и переподключение...")
                            teardownActiveGatt(closeGatt = true)
                            _connectionState.value = BleConnectionState.Disconnected
                            if (!manualDisconnect) {
                                scheduleReconnect("Потеря отклика keepalive")
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, deviceName: String? = null) {
        saveDeviceToPrefs(address, deviceName)
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        _isAutoReconnecting.value = false
        initiateGattConnect(address, autoConnect = false)
    }

    fun connectSavedDevice(): Boolean {
        val saved = _savedDevice.value ?: return false
        manualDisconnect = false
        blePrefs.edit().putBoolean(KEY_MANUAL_DISCONNECT, false).apply()
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        _isAutoReconnecting.value = false
        return initiateGattConnect(saved.address, autoConnect = false)
    }

    fun connectSavedDeviceIfNeeded(): Boolean {
        val saved = _savedDevice.value ?: return false
        if (manualDisconnect) return false
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled || !hasBluetoothConnectPermission()) return false
        if (activeGatt != null) return true
        if (_isAutoReconnecting.value && reconnectJob?.isActive == true) return true
        logDiagnostic("Автоподключение к сохранённым часам ${saved.name} (${saved.address})...")
        return initiateGattConnect(saved.address, autoConnect = false)
    }

    @SuppressLint("MissingPermission")
    private fun initiateGattConnect(address: String, autoConnect: Boolean): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) {
            logDiagnostic("Bluetooth выключен!")
            return false
        }
        if (!hasBluetoothConnectPermission()) {
            logDiagnostic("Нет разрешения BLUETOOTH_CONNECT для подключения.")
            return false
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _connectionState.value = BleConnectionState.Error("Неверный Bluetooth адрес")
            return false
        }

        val hadPreviousGatt = activeGatt != null
        teardownActiveGatt(closeGatt = true)
        connectTimeoutJob?.cancel()

        val displayName = device.name ?: _savedDevice.value?.name ?: "Digma Smartline D3"
        val gen = connectionGeneration
        isPassiveAutoConnecting = autoConnect
        _connectionState.value = BleConnectionState.Connecting(
            address,
            if (autoConnect) "$displayName (ожидание сигнала...)" else displayName
        )

        scope.launch {
            if (hadPreviousGatt) {
                delay(220L)
            }
            if (gen != connectionGeneration || activeGatt != null) return@launch
            if (!adapter.isEnabled || !hasBluetoothConnectPermission()) return@launch

            logDiagnostic("Подключение к $address ($displayName, autoConnect=$autoConnect)...")
            val gatt = try {
                device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                logDiagnostic("Нет разрешения BLE: ${e.localizedMessage}")
                null
            } catch (e: Exception) {
                logDiagnostic("Ошибка connectGatt: ${e.localizedMessage}")
                null
            }

            if (gatt == null) {
                _connectionState.value = BleConnectionState.Disconnected
                if (!manualDisconnect) {
                    scheduleReconnect("connectGatt вернул null")
                }
                return@launch
            }

            if (gen == connectionGeneration) {
                activeGatt = gatt
                if (!autoConnect) {
                    startConnectTimeoutWatchdog(gen, address)
                }
            } else {
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
            }
        }
        return true
    }

    private fun startConnectTimeoutWatchdog(gen: Long, address: String) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (gen == connectionGeneration &&
                _connectionState.value is BleConnectionState.Connecting &&
                !isPassiveAutoConnecting
            ) {
                logDiagnostic("Таймаут подключения к $address (${CONNECT_TIMEOUT_MS / 1000}с)")
                teardownActiveGatt(closeGatt = true)
                _connectionState.value = BleConnectionState.Disconnected
                if (!manualDisconnect) {
                    scheduleReconnect("Таймаут подключения")
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        val saved = _savedDevice.value ?: return
        if (manualDisconnect) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            _isAutoReconnecting.value = false
            return
        }
        if (reconnectJob?.isActive == true) return

        _isAutoReconnecting.value = true
        reconnectJob = scope.launch {
            try {
                val idx = reconnectAttempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)
                val delayMs = RECONNECT_DELAYS_MS[idx]
                reconnectAttempt++
                logDiagnostic("Автопереподключение к ${saved.name} через ${delayMs / 1000.0}с (попытка #$reconnectAttempt, причина: $reason)")
                delay(delayMs)

                if (!isActive || manualDisconnect || _savedDevice.value == null) return@launch
                if (!adapter.isEnabled || !hasBluetoothConnectPermission()) return@launch
                if (activeGatt != null) return@launch

                val remoteDevice = try {
                    adapter.getRemoteDevice(saved.address)
                } catch (_: Exception) {
                    null
                }
                if (remoteDevice != null &&
                    (reconnectAttempt >= 2 || remoteDevice.type == BluetoothDevice.DEVICE_TYPE_UNKNOWN) &&
                    hasBluetoothScanPermission()
                ) {
                    primeBleAddressViaFilteredScan(saved.address)
                }

                if (!isActive || manualDisconnect || activeGatt != null) return@launch
                val useAutoConnect = reconnectAttempt >= 4
                initiateGattConnect(saved.address, autoConnect = useAutoConnect)
            } finally {
                if (activeGatt != null && _connectionState.value is BleConnectionState.Connected) {
                    _isAutoReconnecting.value = false
                } else if (manualDisconnect) {
                    _isAutoReconnecting.value = false
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun primeBleAddressViaFilteredScan(address: String) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val found = CompletableDeferred<Boolean>()
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (result?.device?.address.equals(address, ignoreCase = true)) {
                    found.complete(true)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                found.complete(false)
            }
        }

        try {
            logDiagnostic("Точечный BLE-поиск $address для восстановления канала...")
            scanner.startScan(listOf(filter), settings, callback)
            withTimeoutOrNull(SCAN_WAKEUP_TIMEOUT_MS) { found.await() }
        } catch (_: Exception) {
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(byUser: Boolean = true) {
        if (byUser) {
            manualDisconnect = true
            blePrefs.edit().putBoolean(KEY_MANUAL_DISCONNECT, true).apply()
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
            _isAutoReconnecting.value = false
        }
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        isPassiveAutoConnecting = false
        teardownActiveGatt(closeGatt = true)
        _connectionState.value = BleConnectionState.Disconnected
        logDiagnostic(if (byUser) "Отключено пользователем." else "Отключено.")
    }

    fun forgetSavedDevice() {
        disconnect(byUser = true)
        blePrefs.edit().clear().apply()
        manualDisconnect = false
        _savedDevice.value = null
        logDiagnostic("Сохранённые часы удалены.")
    }

    @SuppressLint("MissingPermission")
    private fun teardownActiveGatt(closeGatt: Boolean, explicitGatt: BluetoothGatt? = null) {
        val target = explicitGatt ?: activeGatt
        try {
            if (closeGatt && target != null) {
                try {
                    target.disconnect()
                } catch (_: Exception) {
                }
                try {
                    target.close()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT", e)
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        connectionGeneration++
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        isPassiveAutoConnecting = false
        consecutiveFailedKeepalives = 0
        setupJob?.cancel()
        setupJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
        hrFallbackJob?.cancel()
        hrFallbackJob = null
        receivedHrSampleInCurrentSession = false
        confirmedThreeByteSportLayout = null
        lastStepCounterRefreshMs = 0L
        pendingDescriptorWrite?.cancel()
        pendingDescriptorWrite = null
        pendingCharWrite?.cancel()
        pendingCharWrite = null
        pendingCharRead?.cancel()
        pendingCharRead = null
        activeGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        rawNotifyCharacteristic = null
        standardBatteryCharacteristic = null
        standardHeartRateCharacteristic = null
        _activeWriteUuid.value = null
        _activeNotifyUuid.value = null
        _deviceFirmware.value = null
    }

    private fun resolveVendorCharacteristics(services: List<BluetoothGattService>) {
        writeCharacteristic = null
        notifyCharacteristic = null
        rawNotifyCharacteristic = null
        standardBatteryCharacteristic = null
        standardHeartRateCharacteristic = null

        val summaryLines = mutableListOf<String>()
        var hasSxr56ff = false

        // 1. Record and log every discovered service & characteristic
        for (service in services) {
            val sUuid = service.uuid.toString().lowercase()
            if (service.uuid == SxrKeepFitCodec.SERVICE_UUID) {
                hasSxr56ff = true
            }
            summaryLines += "Service: $sUuid"
            logDiagnostic("Service: $sUuid")
            for (char in service.characteristics) {
                val cUuid = char.uuid.toString().lowercase()
                if (char.uuid == SxrKeepFitCodec.WRITE_CHAR_UUID || char.uuid == SxrKeepFitCodec.NOTIFY_CHAR_UUID) {
                    hasSxr56ff = true
                }
                if (service.uuid == BATTERY_SERVICE_UUID && char.uuid == BATTERY_LEVEL_CHAR_UUID) {
                    standardBatteryCharacteristic = char
                }
                val props = char.properties
                val isWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                val isNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                val isRead = (props and BluetoothGattCharacteristic.PROPERTY_READ != 0)
                val hasCccd = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) != null
                if (service.uuid == HEART_RATE_SERVICE_UUID && char.uuid == HEART_RATE_MEASUREMENT_CHAR_UUID && isNotify) {
                    standardHeartRateCharacteristic = char
                }
                if (char.uuid == SxrKeepFitCodec.RAW_NOTIFY_CHAR_UUID && isNotify) {
                    rawNotifyCharacteristic = char
                }
                val line = "  └─ Char: $cUuid [W=$isWrite, N=$isNotify, R=$isRead, CCCD=$hasCccd, Props=0x${Integer.toHexString(props)}]"
                summaryLines += line
                logDiagnostic(line)
            }
        }

        _detectedProtocol.value = if (hasSxr56ff) WatchProtocolMode.SXR_KEEPFIT else WatchProtocolMode.AB_LEGACY

        // 2. Match known vendor services (56ff first, then FFF0 / Telink / FEE7 / Nordic)
        for (knownServiceUuid in KNOWN_VENDOR_SERVICES) {
            val matchingService = services.firstOrNull { it.uuid.toString().equals(knownServiceUuid, ignoreCase = true) }
            if (matchingService != null) {
                logDiagnostic("Найден известный вендорный сервис: ${matchingService.uuid}")
                findCharacteristicsInService(matchingService)
                if (writeCharacteristic != null && notifyCharacteristic != null) {
                    break
                }
            }
        }

        // 3. Search across non-SIG services if still incomplete
        if (writeCharacteristic == null || notifyCharacteristic == null) {
            val nonSigServices = services.filterNot { isStandardSigService(it.uuid) }
            for (service in nonSigServices) {
                findCharacteristicsInService(service)
                if (writeCharacteristic != null && notifyCharacteristic != null) break
            }
        }

        // 4. Ultimate fallback across remaining services
        if (writeCharacteristic == null || notifyCharacteristic == null) {
            for (service in services) {
                findCharacteristicsInService(service)
                if (writeCharacteristic != null && notifyCharacteristic != null) break
            }
        }

        summaryLines += "Detected Protocol: ${_detectedProtocol.value.label} (Mode=${_protocolMode.value.label})"
        summaryLines += "Selected TX (Write): ${writeCharacteristic?.uuid}"
        summaryLines += "Selected RX (Notify): ${notifyCharacteristic?.uuid}"
        synchronized(logLock) {
            _gattSummary.value = summaryLines
        }
    }

    private fun findCharacteristicsInService(service: BluetoothGattService) {
        val sUuid = service.uuid.toString().lowercase()

        // Pass 1a: SXR KeepFit service (56ff -> 33f3 Write, 33f4 Notify)
        if (sUuid.contains("56ff")) {
            for (char in service.characteristics) {
                val props = char.properties
                val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)

                if (char.uuid == SxrKeepFitCodec.WRITE_CHAR_UUID && canWrite && writeCharacteristic == null) {
                    writeCharacteristic = char
                    logDiagnostic("Найдена SXR 33F3 [Write TX]: ${char.uuid}")
                }
                if (char.uuid == SxrKeepFitCodec.NOTIFY_CHAR_UUID && canNotify && notifyCharacteristic == null) {
                    notifyCharacteristic = char
                    logDiagnostic("Найдена SXR 33F4 [Notify RX]: ${char.uuid}")
                }
            }
            if (writeCharacteristic != null && notifyCharacteristic != null) return
        }

        // Pass 1b: FFF0 profile (FFF2 Write, FFF1 Notify)
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

        // Pass 2: Known vendor characteristic patterns
        for (char in service.characteristics) {
            val u = char.uuid.toString().lowercase()
            val props = char.properties
            val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)

            if (canWrite && writeCharacteristic == null && char != notifyCharacteristic) {
                if (u.contains("33f3") || u.contains("fff2") || u.contains("1911") ||
                    u.contains("fea2") || u.contains("6e400002") || u.contains("2d01")
                ) {
                    writeCharacteristic = char
                    logDiagnostic("Найдена Write char по шаблону: ${char.uuid}")
                }
            }

            if (canNotify && notifyCharacteristic == null && char != writeCharacteristic) {
                if (u.contains("33f4") || u.contains("fff1") || u.contains("1912") ||
                    u.contains("fea1") || u.contains("6e400003") || u.contains("2d02")
                ) {
                    notifyCharacteristic = char
                    logDiagnostic("Найдена Notify char по шаблону: ${char.uuid}")
                }
            }
        }

        // Pass 3: Fallback strictly by capability
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
     * Diagnostic helper: swaps TX and RX characteristics if a device revision inverted them.
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
        _activeWriteUuid.value = writeCharacteristic?.uuid?.toString()
        _activeNotifyUuid.value = notifyCharacteristic?.uuid?.toString()

        logDiagnostic(
            "РУЧНОЙ SWAP ХАРАКТЕРИСТИК:\n" +
                "  Write (TX) -> ${writeCharacteristic?.uuid}\n" +
                "  Notify (RX) -> ${notifyCharacteristic?.uuid}"
        )
        appendGattSummary("Manual Swap -> TX=${writeCharacteristic?.uuid}, RX=${notifyCharacteristic?.uuid}")

        val gatt = activeGatt
        val newNotify = notifyCharacteristic
        val gen = connectionGeneration
        if (gatt != null && newNotify != null) {
            scope.launch {
                if (gen == connectionGeneration && activeGatt === gatt) {
                    enableNotificationSuspend(gatt, newNotify)
                }
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun enableNotificationSuspend(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic
    ): Boolean = writeMutex.withLock {
        val success = gatt.setCharacteristicNotification(char, true)
        logDiagnostic("setCharacteristicNotification [${char.uuid}]: $success")
        appendGattSummary("setCharacteristicNotification [${char.uuid}]: $success")

        val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor == null) {
            val msg = "Дескриптор 0x2902 не найден на ${char.uuid}"
            logDiagnostic(msg)
            appendGattSummary(msg)
            return@withLock success
        }

        // Prefer NOTIFY over INDICATE when both properties exist
        val hasNotify = (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val value = if (hasNotify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        val deferred = CompletableDeferred<Int>()
        pendingDescriptorWrite = deferred

        val writeInitiated = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val statusCode = gatt.writeDescriptor(descriptor, value)
                logDiagnostic("writeDescriptor (0x2902, mode=${if (hasNotify) "NOTIFY" else "INDICATE"}): code=$statusCode")
                statusCode == 0
            } else {
                descriptor.value = value
                val ok = gatt.writeDescriptor(descriptor)
                logDiagnostic("writeDescriptor (0x2902, legacy): ok=$ok")
                ok
            }
        } catch (e: Exception) {
            logDiagnostic("Исключение writeDescriptor: ${e.localizedMessage}")
            false
        }

        if (!writeInitiated) {
            pendingDescriptorWrite = null
            return@withLock false
        }

        val callbackStatus = withTimeoutOrNull(GATT_OP_TIMEOUT_MS) { deferred.await() }
        if (callbackStatus == null) {
            pendingDescriptorWrite = null
            logDiagnostic("Таймаут ожидания onDescriptorWrite (${GATT_OP_TIMEOUT_MS}ms), продолжаем...")
            appendGattSummary("onDescriptorWrite timeout after ${GATT_OP_TIMEOUT_MS}ms")
            return@withLock false
        }
        return@withLock callbackStatus == BluetoothGatt.GATT_SUCCESS
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun readStandardBatteryIfAvailable(gatt: BluetoothGatt) {
        val batteryChar = standardBatteryCharacteristic ?: return
        if ((batteryChar.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) return

        writeMutex.withLock {
            val deferred = CompletableDeferred<Pair<Int, ByteArray?>>()
            pendingCharRead = deferred
            val started = try {
                gatt.readCharacteristic(batteryChar)
            } catch (e: Exception) {
                logDiagnostic("Исключение чтения SIG 0x2A19: ${e.localizedMessage}")
                false
            }
            if (!started) {
                pendingCharRead = null
                return@withLock
            }
            val result = withTimeoutOrNull(GATT_OP_TIMEOUT_MS) { deferred.await() }
            pendingCharRead = null
            if (result != null && result.first == BluetoothGatt.GATT_SUCCESS) {
                val data = result.second
                if (data != null && data.isNotEmpty()) {
                    val pct = (data[0].toInt() and 0xFF).coerceIn(0, 100)
                    logDiagnostic("SIG Батарея (0x2A19): $pct%")
                    _healthMetrics.update {
                        it.copy(
                            batteryPercent = pct,
                            lastSyncTimestamp = System.currentTimeMillis()
                        )
                    }
                }
            }
        }
    }

    private fun handleIncomingData(charUuid: UUID, data: ByteArray) {
        val hexStr = data.joinToString(" ") { String.format(Locale.US, "%02X", it) }
        logDiagnostic("RX [$charUuid]: $hexStr")

        if (charUuid == BATTERY_LEVEL_CHAR_UUID && data.isNotEmpty()) {
            val pct = (data[0].toInt() and 0xFF).coerceIn(0, 100)
            updateFromStandardPacket(DigmaProtocolCodec.IncomingPacket.Battery(pct))
            return
        }

        if (charUuid == HEART_RATE_MEASUREMENT_CHAR_UUID) {
            val hr = SxrKeepFitCodec.parseStandardBleHeartRate(data)
            if (hr != null) {
                updateFromStandardPacket(hr)
            }
            return
        }

        if (data.isNotEmpty() && (data[0].toInt() and 0xFF) == SxrKeepFitCodec.CMD_CURRENT_SPORT) {
            val inferredLayout = SxrKeepFitCodec.detectThreeByteSportLayout(data)
            if (inferredLayout != null && confirmedThreeByteSportLayout == null) {
                confirmedThreeByteSportLayout = inferredLayout
            }
        }

        if (effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
            when (val sxr = SxrKeepFitCodec.parseIncomingPacket(data, confirmedThreeByteSportLayout)) {
                is SxrKeepFitCodec.SxrEvent.BindNotify -> {
                    logDiagnostic("RX SXR Bind: action=${sxr.action}, state=${sxr.state}")
                    val gen = connectionGeneration
                    when (sxr.action) {
                        SxrKeepFitCodec.BIND_ACTION_INIT -> if (sxr.state == 0) {
                            enqueueCommand(gen) {
                                sendPacketsSuspend(listOf(SxrKeepFitCodec.buildBindAppStartPacket()), 0L)
                            }
                        }
                        SxrKeepFitCodec.BIND_ACTION_ACK -> {
                            enqueueCommand(gen) {
                                sendPacketsSuspend(listOf(SxrKeepFitCodec.buildBindSuccessPacket()), 0L)
                            }
                        }
                    }
                    return
                }
                is SxrKeepFitCodec.SxrEvent.DeviceInfo -> {
                    val info = listOfNotNull(sxr.firmware, sxr.macAddress, sxr.rawFirmwareNumber?.let { "ver=$it" })
                        .joinToString(" / ")
                    if (info.isNotEmpty()) {
                        _deviceFirmware.value = info
                        logDiagnostic("RX SXR Устройство: $info")
                    }
                    _healthMetrics.update { it.copy(lastSyncTimestamp = System.currentTimeMillis()) }
                    return
                }
                is SxrKeepFitCodec.SxrEvent.BandCapabilities -> {
                    val bitsHex = sxr.bitmask.joinToString("") { String.format(Locale.US, "%02X", it) }
                    logDiagnostic("RX SXR Возможности (0x20): $bitsHex")
                    return
                }
                is SxrKeepFitCodec.SxrEvent.SensorSnapshot -> {
                    logDiagnostic(
                        "RX SXR Датчики: HR=${sxr.heartRateBpm}, BP=${sxr.systolic}/${sxr.diastolic}, SpO2=${sxr.spO2Percent}%, fatigue=${sxr.fatigue}, stress=${sxr.stress}"
                    )
                    updateFromStandardPacket(
                        DigmaProtocolCodec.IncomingPacket.HeartRate(sxr.heartRateBpm, sxr.spO2Percent)
                    )
                    return
                }
                is SxrKeepFitCodec.SxrEvent.StepCounter -> {
                    handleSxrStepCounter(sxr.cumulativeSteps)
                    return
                }
                is SxrKeepFitCodec.SxrEvent.ActivityBuckets -> {
                    receivedActivityHistoryInCurrentSync = true
                    val sumSteps = sxr.stepSamples.sum()
                    logDiagnostic("RX SXR Бакеты активности (0x10): +$sumSteps шагов (${sxr.stepSamples.size} мин)")
                    historyStore.recordActivityBuckets(sxr.baseEpochSeconds, sxr.stepSamples)
                    _healthMetrics.update { it.copy(lastSyncTimestamp = System.currentTimeMillis()) }
                    return
                }
                is SxrKeepFitCodec.SxrEvent.HeartRateHistory -> {
                    receivedHrHistoryInCurrentSync = true
                    val lastBpm = sxr.bpmSamples.lastOrNull()
                    logDiagnostic(
                        "RX SXR История пульса (0x16): замеров=${sxr.bpmSamples.size}, посл=${lastBpm ?: 0} уд/мин"
                    )
                    historyStore.recordHeartRateBatch(sxr.baseEpochSeconds, sxr.bpmSamples)
                    if (lastBpm != null) {
                        _healthMetrics.update { it.copy(isMeasuringHeartRate = false) }
                        updateFromStandardPacket(
                            DigmaProtocolCodec.IncomingPacket.HeartRate(lastBpm, 0),
                            recordToHistory = false
                        )
                    } else {
                        _healthMetrics.update { it.copy(lastSyncTimestamp = System.currentTimeMillis()) }
                    }
                    return
                }
                is SxrKeepFitCodec.SxrEvent.HeartRateComplete -> {
                    logDiagnostic("RX SXR Замер пульса завершён (0x27): HR=${sxr.heartRateBpm}")
                    _healthMetrics.update {
                        it.copy(
                            isMeasuringHeartRate = false,
                            lastSyncTimestamp = System.currentTimeMillis()
                        )
                    }
                    if (sxr.heartRateBpm > 0) {
                        updateFromStandardPacket(DigmaProtocolCodec.IncomingPacket.HeartRate(sxr.heartRateBpm, 0))
                    }
                    return
                }
                is SxrKeepFitCodec.SxrEvent.CommandAck -> {
                    logDiagnostic(String.format(Locale.US, "RX SXR ACK: 0x%02X", sxr.opcode))
                    _healthMetrics.update { current ->
                        val stopMeasuring = sxr.opcode == SxrKeepFitCodec.CMD_SPO2_COMPLETE ||
                            sxr.opcode == SxrKeepFitCodec.CMD_HEART_RATE_STOP
                        current.copy(
                            isMeasuringHeartRate = if (stopMeasuring) false else current.isMeasuringHeartRate,
                            lastSyncTimestamp = System.currentTimeMillis()
                        )
                    }
                    return
                }
                is SxrKeepFitCodec.SxrEvent.CommandError -> {
                    logDiagnostic(String.format(Locale.US, "RX SXR Ошибка команды: 0x%02X", sxr.opcode))
                    return
                }
                is SxrKeepFitCodec.SxrEvent.Standard -> {
                    updateFromStandardPacket(sxr.packet)
                    return
                }
                is SxrKeepFitCodec.SxrEvent.Unknown -> {
                    val legacy = DigmaProtocolCodec.parseIncomingPacket(data)
                    if (legacy != null) {
                        updateFromStandardPacket(legacy)
                    } else {
                        logDiagnostic(String.format(Locale.US, "RX SXR не распознан: op=0x%02X, len=%d", sxr.opcode, data.size))
                    }
                    return
                }
            }
        }

        // Legacy 0xAB / 0x5A mode with SXR fallback
        val legacyEvent = DigmaProtocolCodec.parseIncomingPacket(data)
        if (legacyEvent != null) {
            updateFromStandardPacket(legacyEvent)
            return
        }

        when (val sxrFallback = SxrKeepFitCodec.parseIncomingPacket(data, confirmedThreeByteSportLayout)) {
            is SxrKeepFitCodec.SxrEvent.Standard -> updateFromStandardPacket(sxrFallback.packet)
            is SxrKeepFitCodec.SxrEvent.SensorSnapshot -> updateFromStandardPacket(
                DigmaProtocolCodec.IncomingPacket.HeartRate(sxrFallback.heartRateBpm, sxrFallback.spO2Percent)
            )
            is SxrKeepFitCodec.SxrEvent.StepCounter -> handleSxrStepCounter(sxrFallback.cumulativeSteps)
            is SxrKeepFitCodec.SxrEvent.ActivityBuckets -> {
                logDiagnostic("RX SXR Бакеты активности (0x10): +${sxrFallback.stepSamples.sum()} шагов")
                historyStore.recordActivityBuckets(sxrFallback.baseEpochSeconds, sxrFallback.stepSamples)
            }
            is SxrKeepFitCodec.SxrEvent.HeartRateHistory -> {
                historyStore.recordHeartRateBatch(sxrFallback.baseEpochSeconds, sxrFallback.bpmSamples)
                sxrFallback.bpmSamples.lastOrNull()?.let { lastBpm ->
                    updateFromStandardPacket(
                        DigmaProtocolCodec.IncomingPacket.HeartRate(lastBpm, 0),
                        recordToHistory = false
                    )
                }
            }
            is SxrKeepFitCodec.SxrEvent.HeartRateComplete -> {
                _healthMetrics.update { it.copy(isMeasuringHeartRate = false) }
                if (sxrFallback.heartRateBpm > 0) {
                    updateFromStandardPacket(DigmaProtocolCodec.IncomingPacket.HeartRate(sxrFallback.heartRateBpm, 0))
                }
            }
            else -> logDiagnostic("RX: Пакет не распознан протоколом (len=${data.size})")
        }
    }

    private fun handleSxrStepCounter(cumulativeSteps: Int) {
        logDiagnostic("RX SXR Шагомер (0x51): счётчик=$cumulativeSteps")
        _healthMetrics.update { it.copy(lastSyncTimestamp = System.currentTimeMillis()) }
        val now = System.currentTimeMillis()
        if (canTransmit() && now - lastStepCounterRefreshMs >= 5_000L) {
            lastStepCounterRefreshMs = now
            requestActivity()
        }
    }

    private fun updateFromStandardPacket(
        event: DigmaProtocolCodec.IncomingPacket,
        recordToHistory: Boolean = true
    ) {
        if (event is DigmaProtocolCodec.IncomingPacket.HeartRate && event.heartRateBpm in 25..250) {
            receivedHrSampleInCurrentSession = true
            hrFallbackJob?.cancel()
        }
        val now = System.currentTimeMillis()
        if (recordToHistory) {
            when (event) {
                is DigmaProtocolCodec.IncomingPacket.Activity ->
                    historyStore.recordCurrentActivity(event.steps, event.calories, event.distanceMeters, now)
                is DigmaProtocolCodec.IncomingPacket.HeartRate ->
                    if (event.heartRateBpm in 25..250) {
                        historyStore.recordHeartRate(event.heartRateBpm, now)
                    }
                else -> Unit
            }
        }
        _healthMetrics.update { current ->
            when (event) {
                is DigmaProtocolCodec.IncomingPacket.Battery -> current.copy(
                    batteryPercent = event.percent,
                    lastSyncTimestamp = now
                )
                is DigmaProtocolCodec.IncomingPacket.Activity -> current.copy(
                    steps = event.steps,
                    caloriesKcal = event.calories,
                    distanceMeters = event.distanceMeters,
                    lastSyncTimestamp = now
                )
                is DigmaProtocolCodec.IncomingPacket.HeartRate -> current.copy(
                    heartRateBpm = if (event.heartRateBpm > 0) event.heartRateBpm else current.heartRateBpm,
                    spO2Percent = if (event.spO2Percent > 0) event.spO2Percent else current.spO2Percent,
                    lastSyncTimestamp = now
                )
                else -> current.copy(lastSyncTimestamp = now)
            }
        }
        logDiagnostic("RX Событие: $event")

        scope.launch {
            _incomingEvents.emit(event)
        }
    }

    private fun enqueueCommand(gen: Long, action: suspend () -> Unit) {
        commandQueue.trySend(gen to action)
    }

    fun sendPacket(packet: ByteArray): Boolean {
        if (activeGatt == null) {
            logDiagnostic("TX Сбой: GATT не подключен")
            return false
        }
        if (writeCharacteristic == null) {
            logDiagnostic("TX Сбой: writeCharacteristic = null")
            return false
        }
        val gen = connectionGeneration
        enqueueCommand(gen) {
            sendPacketsSuspend(listOf(packet), delayBetweenMs = 0L)
        }
        return true
    }

    private suspend fun sendPacketsSuspend(
        packets: List<ByteArray>,
        delayBetweenMs: Long = 45L
    ): Boolean = writeMutex.withLock {
        val gatt = activeGatt
        val char = writeCharacteristic
        if (gatt == null) {
            logDiagnostic("TX Сбой: GATT не подключен")
            return@withLock false
        }
        if (char == null) {
            logDiagnostic("TX Сбой: writeCharacteristic = null")
            return@withLock false
        }

        var allOk = true
        packets.forEachIndexed { index, packet ->
            val ok = writeSinglePacketSuspend(gatt, char, packet)
            if (!ok) allOk = false
            if (index < packets.lastIndex && delayBetweenMs > 0L) {
                delay(delayBetweenMs)
            }
        }
        return@withLock allOk
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun writeSinglePacketSuspend(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        packet: ByteArray
    ): Boolean {
        val hexStr = packet.joinToString(" ") { String.format(Locale.US, "%02X", it) }
        logDiagnostic("TX [${char.uuid}]: $hexStr")

        val canWriteNoResp = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val writeType = if (canWriteNoResp) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        char.writeType = writeType

        val deferred = CompletableDeferred<Int>()
        pendingCharWrite = deferred

        val status = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, packet, writeType)
            } else {
                char.value = packet
                if (gatt.writeCharacteristic(char)) 0 else -1
            }
        } catch (e: Exception) {
            logDiagnostic("TX Исключение: ${e.localizedMessage}")
            pendingCharWrite = null
            return false
        }

        logDiagnostic("TX Результат: code=$status (type=${if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) "NO_RESP" else "DEFAULT"})")
        if (status != 0) {
            pendingCharWrite = null
            return false
        }

        val cbStatus = withTimeoutOrNull(GATT_OP_TIMEOUT_MS) { deferred.await() }
        if (cbStatus == null) {
            pendingCharWrite = null
            return false
        }
        return cbStatus == BluetoothGatt.GATT_SUCCESS
    }

    private fun canTransmit(): Boolean {
        if (activeGatt == null) {
            logDiagnostic("TX Сбой: GATT не подключен")
            return false
        }
        if (writeCharacteristic == null) {
            logDiagnostic("TX Сбой: writeCharacteristic = null")
            return false
        }
        return true
    }

    fun syncTime(): Boolean {
        if (!canTransmit()) return false
        _healthMetrics.update { it.copy(lastSyncTimestamp = System.currentTimeMillis()) }
        val gen = connectionGeneration
        enqueueCommand(gen) {
            if (effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
                sendPacketsSuspend(
                    listOf(
                        SxrKeepFitCodec.buildAppIdPacket(),
                        SxrKeepFitCodec.buildTimeSyncPacket()
                    ),
                    delayBetweenMs = 60L
                )
            } else {
                syncTimeLegacySuspend()
            }
        }
        return true
    }

    private suspend fun syncTimeLegacySuspend() {
        val pktA = DigmaProtocolCodec.buildTimeSyncPacket()
        val pktB = DigmaProtocolCodec.buildTimeSyncPacketShort()
        val pktPadded = DigmaProtocolCodec.padToLength(pktA, 20)
        sendPacketsSuspend(listOf(pktA, pktB, pktPadded), delayBetweenMs = 80L)
    }

    fun syncAll() {
        logDiagnostic("Запуск полной синхронизации...")
        triggerInitialSync()
    }

    fun queryBattery(): Boolean {
        if (!canTransmit()) return false
        val gen = connectionGeneration
        enqueueCommand(gen) {
            if (effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
                activeGatt?.let { readStandardBatteryIfAvailable(it) }
                sendPacketsSuspend(listOf(SxrKeepFitCodec.buildBatteryQueryPacket()), delayBetweenMs = 0L)
            } else {
                queryBatteryLegacySuspend()
            }
        }
        return true
    }

    private suspend fun queryBatteryLegacySuspend() {
        val raw = DigmaProtocolCodec.buildBatteryQueryPacket()
        val padded = DigmaProtocolCodec.padToLength(raw, 16)
        sendPacketsSuspend(listOf(raw, padded), delayBetweenMs = 80L)
    }

    fun requestActivity(): Boolean {
        if (!canTransmit()) return false
        val gen = connectionGeneration
        enqueueCommand(gen) {
            if (effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
                sendPacketsSuspend(listOf(SxrKeepFitCodec.buildActivityQueryPacket()), delayBetweenMs = 0L)
            } else {
                requestActivityLegacySuspend()
            }
        }
        return true
    }

    private suspend fun requestActivityLegacySuspend() {
        val raw = DigmaProtocolCodec.buildActivityRequestPacket()
        val padded = DigmaProtocolCodec.padToLength(raw, 16)
        sendPacketsSuspend(listOf(raw, padded), delayBetweenMs = 80L)
    }

    fun findWatch(): Boolean {
        if (!canTransmit()) return false
        logDiagnostic("Команда: Поиск часов (вибрация)...")
        val gen = connectionGeneration
        enqueueCommand(gen) {
            if (effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
                sendPacketsSuspend(listOf(SxrKeepFitCodec.buildFindWatchPacket()), delayBetweenMs = 0L)
            } else {
                val pkt1 = DigmaProtocolCodec.buildFindWatchPacket()
                val pkt2 = DigmaProtocolCodec.buildFindWatchPacketLefun()
                val pktPadded = DigmaProtocolCodec.padToLength(pkt1, 20)
                sendPacketsSuspend(listOf(pkt1, pkt2, pktPadded), delayBetweenMs = 80L)
            }
        }
        return true
    }

    fun toggleHeartRateMeasurement(start: Boolean): Boolean {
        if (!canTransmit()) return false
        hrFallbackJob?.cancel()
        receivedHrSampleInCurrentSession = false
        _healthMetrics.update { it.copy(isMeasuringHeartRate = start) }
        logDiagnostic("Команда: Замер пульса ($start)...")
        val gen = connectionGeneration
        if (effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
            if (start) {
                enqueueCommand(gen) {
                    sendPacketsSuspend(listOf(SxrKeepFitCodec.buildHeartRateStartPacket()), delayBetweenMs = 0L)
                }
                startHeartRateFallbackSequence(gen)
            } else {
                enqueueCommand(gen) {
                    sendPacketsSuspend(
                        listOf(
                            SxrKeepFitCodec.buildHeartRateStopPacket(),
                            SxrKeepFitCodec.buildCombinedMeasurementStopPacket()
                        ),
                        delayBetweenMs = 80L
                    )
                }
            }
        } else {
            enqueueCommand(gen) {
                val pkt1 = DigmaProtocolCodec.buildHeartRateControlPacket(start)
                val pkt2 = DigmaProtocolCodec.buildHeartRateControlPacketLefun(start)
                val pktPadded = DigmaProtocolCodec.padToLength(pkt1, 20)
                sendPacketsSuspend(listOf(pkt1, pkt2, pktPadded), delayBetweenMs = 80L)
            }
        }
        return true
    }

    private fun startHeartRateFallbackSequence(gen: Long) {
        hrFallbackJob = scope.launch {
            // Give the optical PPG sensor on the watch time to lock without resetting mode=0/ref=0.
            delay(8_000L)
            if (!isActive || gen != connectionGeneration || activeGatt == null) return@launch
            if (!_healthMetrics.value.isMeasuringHeartRate || receivedHrSampleInCurrentSession) return@launch

            logDiagnostic("SXR HR: опрашиваем буфер пульса (0x16)...")
            receivedHrHistoryInCurrentSync = false
            enqueueCommand(gen) {
                sendPacketsSuspend(
                    listOf(
                        SxrKeepFitCodec.buildHeartRateHistoryQueryPacket(0L),
                        SxrKeepFitCodec.buildHeartRateHistoryQueryPacket(SxrKeepFitCodec.localMidnightEpochSeconds())
                    ),
                    delayBetweenMs = 120L
                )
            }

            val fallbackProbes = listOf(
                Triple(5_000L, "PPG 0x23 (mode=1)") {
                    SxrKeepFitCodec.buildCombinedMeasurementStartPacket(mode = 1)
                },
                Triple(10_000L, "0x14 (ref=1, mode=1)") {
                    SxrKeepFitCodec.buildHeartRateModePacket(referenceValue = 1L, mode = 1)
                },
                Triple(8_000L, "PPG 0x23 (mode=2)") {
                    SxrKeepFitCodec.buildCombinedMeasurementStartPacket(mode = 2)
                }
            )
            for ((waitMs, label, builder) in fallbackProbes) {
                delay(waitMs)
                if (!isActive || gen != connectionGeneration || activeGatt == null) return@launch
                if (!_healthMetrics.value.isMeasuringHeartRate || receivedHrSampleInCurrentSession) return@launch
                logDiagnostic("SXR HR: пробуем альтернативный режим $label...")
                enqueueCommand(gen) {
                    sendPacketsSuspend(listOf(builder()), delayBetweenMs = 0L)
                }
            }

            delay(7_000L)
            if (!isActive || gen != connectionGeneration || activeGatt == null) return@launch
            if (!_healthMetrics.value.isMeasuringHeartRate || receivedHrSampleInCurrentSession) return@launch

            logDiagnostic("SXR HR: финальный опрос истории пульса (0x16)...")
            enqueueCommand(gen) {
                sendPacketsSuspend(
                    listOf(
                        SxrKeepFitCodec.buildHeartRateHistoryQueryPacket(0L),
                        SxrKeepFitCodec.buildHeartRateHistoryQueryPacket(
                            (SxrKeepFitCodec.localEpochSeconds() - 86_400L).coerceAtLeast(0L)
                        )
                    ),
                    delayBetweenMs = 120L
                )
            }

            delay(3_000L)
            if (!isActive || gen != connectionGeneration || activeGatt == null) return@launch
            if (_healthMetrics.value.isMeasuringHeartRate && !receivedHrSampleInCurrentSession) {
                logDiagnostic("SXR HR: таймаут ожидания замера пульса")
                _healthMetrics.update { it.copy(isMeasuringHeartRate = false) }
                enqueueCommand(gen) {
                    sendPacketsSuspend(
                        listOf(
                            SxrKeepFitCodec.buildHeartRateStopPacket(),
                            SxrKeepFitCodec.buildCombinedMeasurementStopPacket()
                        ),
                        delayBetweenMs = 80L
                    )
                }
            }
        }
    }

    fun queryHeartRateHistory(): Boolean {
        if (!canTransmit()) return false
        logDiagnostic("Команда: Запрос истории пульса (0x16)...")
        receivedHrHistoryInCurrentSync = false
        val gen = connectionGeneration
        enqueueCommand(gen) {
            if (effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
                sendPacketsSuspend(listOf(SxrKeepFitCodec.buildHeartRateHistoryQueryPacket(0L)), delayBetweenMs = 0L)
                delay(650L)
                if (!receivedHrHistoryInCurrentSync && gen == connectionGeneration) {
                    sendPacketsSuspend(
                        listOf(
                            SxrKeepFitCodec.buildHeartRateHistoryQueryPacket(SxrKeepFitCodec.localMidnightEpochSeconds()),
                            SxrKeepFitCodec.buildHeartRateHistoryQueryPacket(
                                (SxrKeepFitCodec.localEpochSeconds() - 86_400L).coerceAtLeast(0L)
                            )
                        ),
                        delayBetweenMs = 120L
                    )
                }
            }
        }
        return true
    }

    fun sendNotification(type: DigmaProtocolCodec.NotificationType, title: String, text: String): Boolean {
        if (!canTransmit()) return false
        logDiagnostic("Команда: Отправка push-уведомления ($type: $title)...")
        val gen = connectionGeneration
        val uid = notificationUidCounter++
        if (notificationUidCounter > 9999) notificationUidCounter = 1
        enqueueCommand(gen) {
            if (effectiveProtocol() == WatchProtocolMode.SXR_KEEPFIT) {
                val frames = SxrKeepFitCodec.buildNotificationPackets(type, title, text, uid = uid)
                sendPacketsSuspend(frames, delayBetweenMs = 50L)
            } else {
                val packet = DigmaProtocolCodec.buildNotificationPacket(type, title, text)
                sendPacketsSuspend(listOf(packet), delayBetweenMs = 0L)
            }
        }
        return true
    }

    // --- Diagnostic Direct Test Methods ---

    fun testFindWatchSxr(): Boolean {
        logDiagnostic("ТЕСТ: Find Watch (SXR KeepFit 0x04 0x0A)")
        return sendPacket(SxrKeepFitCodec.buildFindWatchPacket())
    }

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

    fun testHeartRateSxrMode(): Boolean {
        logDiagnostic("ТЕСТ: HR 0x14 (ref=1, mode=1)")
        return sendPacket(SxrKeepFitCodec.buildHeartRateModePacket(referenceValue = 1L, mode = 1))
    }

    fun testCombinedPpgSxr(mode: Int = 2): Boolean {
        logDiagnostic("ТЕСТ: PPG 0x23 (mode=$mode)")
        return sendPacket(SxrKeepFitCodec.buildCombinedMeasurementStartPacket(mode = mode))
    }
}
