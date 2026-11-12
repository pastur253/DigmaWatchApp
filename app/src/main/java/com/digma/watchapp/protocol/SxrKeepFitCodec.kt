package com.digma.watchapp.protocol

import java.nio.charset.StandardCharsets
import java.util.TimeZone
import java.util.UUID

/**
 * Codec for watches/bands built on the SXR / KeepFit BLE SDK (`000056ff`).
 *
 * Wire format: fixed 20-byte zero-padded frames where `byte[0]` is the opcode
 * and multi-byte integers are little-endian.
 */
object SxrKeepFitCodec {

    val SERVICE_UUID: UUID = UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("000033f3-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("000033f4-0000-1000-8000-00805f9b34fb")
    val RAW_WRITE_CHAR_UUID: UUID = UUID.fromString("000033f5-0000-1000-8000-00805f9b34fb")
    val RAW_NOTIFY_CHAR_UUID: UUID = UUID.fromString("000033f6-0000-1000-8000-00805f9b34fb")

    const val PACKET_SIZE = 20

    const val CMD_TIME_SYNC = 0x01
    const val CMD_USER_INFO = 0x02
    const val CMD_CURRENT_SPORT = 0x03
    const val CMD_FIND_WATCH = 0x04
    const val CMD_WATCH_ACTION = 0x06
    const val CMD_BATTERY = 0x0B
    const val CMD_DEVICE_INFO = 0x0C
    const val CMD_ACTIVITY_HISTORY = 0x10
    const val CMD_SLEEP_HISTORY = 0x11
    const val CMD_NOTIFICATION = 0x12
    const val CMD_HEART_RATE_START = 0x14
    const val CMD_HEART_RATE_STOP = 0x15
    const val CMD_HEART_RATE_HISTORY = 0x16
    const val CMD_AUTO_HEART = 0x19
    const val CMD_GOAL = 0x1A
    const val CMD_BAND_FUNCTION = 0x20
    const val CMD_LOCATION_ACTION = 0x22
    const val CMD_SENSOR_MODE = 0x23
    const val CMD_COMBINED_SENSOR = 0x24
    const val CMD_HR_COMPLETE = 0x27
    const val CMD_SPO2_COMPLETE = 0x28
    const val CMD_KEEPALIVE = 0x3A
    const val CMD_SPO2_MODE = 0x3E
    const val CMD_SPO2_RESULT = 0x3F
    const val CMD_APP_ID = 0x48
    const val CMD_BIND_INFO = 0x4B
    const val CMD_STEP_COUNTER = 0x51
    const val CMD_FIRMWARE_VERSION = 0xF6

    const val BIND_ACTION_INIT = 0
    const val BIND_ACTION_APP_START = 1
    const val BIND_ACTION_ACK = 2
    const val BIND_ACTION_ACK_CANCEL = 3
    const val BIND_ACTION_SUCCESS = 4
    const val BIND_ACTION_UNBOND = 5
    const val BIND_ACTION_UNBOND_ACK = 6

    private const val NOTIFY_TITLE_MAX_BYTES = 17
    private const val NOTIFY_CONTENT_MAX_BYTES = 17
    private const val NOTIFY_MAX_CONTENT_FRAMES = 253
    private const val MAX_UID = 9999

    sealed class SxrEvent {
        data class Standard(val packet: DigmaProtocolCodec.IncomingPacket) : SxrEvent()
        data class BindNotify(val action: Int, val state: Int) : SxrEvent()
        data class DeviceInfo(
            val macAddress: String?,
            val firmware: String?,
            val rawFirmwareNumber: Int? = null
        ) : SxrEvent()
        data class BandCapabilities(val bitmask: ByteArray) : SxrEvent() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as BandCapabilities
                return bitmask.contentEquals(other.bitmask)
            }

            override fun hashCode(): Int = bitmask.contentHashCode()
        }
        data class SensorSnapshot(
            val heartRateBpm: Int,
            val systolic: Int,
            val diastolic: Int,
            val spO2Percent: Int,
            val fatigue: Int,
            val stress: Int
        ) : SxrEvent()
        data class StepCounter(val cumulativeSteps: Int) : SxrEvent()
        data class ActivityBuckets(
            val baseEpochSeconds: Long,
            val stepSamples: List<Int>
        ) : SxrEvent()
        data class HeartRateHistory(
            val baseEpochSeconds: Long,
            val bpmSamples: List<Int>
        ) : SxrEvent()
        data class HeartRateComplete(val heartRateBpm: Int) : SxrEvent()
        data class CommandAck(val opcode: Int) : SxrEvent()
        data class CommandError(val opcode: Int) : SxrEvent()
        data class Unknown(val opcode: Int, val payload: ByteArray) : SxrEvent() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Unknown
                return opcode == other.opcode && payload.contentEquals(other.payload)
            }

            override fun hashCode(): Int = 31 * opcode + payload.contentHashCode()
        }
    }

    private fun frame(opcode: Int, builder: (ByteArray) -> Unit = {}): ByteArray {
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = (opcode and 0xFF).toByte()
        builder(packet)
        return packet
    }

    /**
     * Announces this application ID (`0x48`). Without it, some SXR firmware revisions
     * remain bound to an earlier app and drop queries silently.
     */
    fun buildAppIdPacket(appId: String = "com.digma.watchapp"): ByteArray = frame(CMD_APP_ID) { packet ->
        val ascii = appId.toByteArray(StandardCharsets.US_ASCII)
        val count = minOf(ascii.size, 18)
        System.arraycopy(ascii, 0, packet, 1, count)
    }

    fun buildBindCommand(action: Int, state: Int = 0, type: Int = 1): ByteArray = frame(CMD_BIND_INFO) { packet ->
        packet[1] = (action and 0xFF).toByte()
        packet[2] = (state and 0xFF).toByte()
        packet[3] = (type and 0xFF).toByte()
    }

    fun buildBindAppStartPacket(): ByteArray = buildBindCommand(BIND_ACTION_APP_START)

    fun buildBindSuccessPacket(): ByteArray = buildBindCommand(BIND_ACTION_SUCCESS)

    /**
     * SXR clock sync (`0x01`): bytes 1..4 = little-endian local wall-clock epoch seconds
     * (`utcEpoch + offsetSeconds`), byte 5 = signed whole hours offset from UTC.
     */
    fun buildTimeSyncPacket(
        epochSeconds: Long = System.currentTimeMillis() / 1000L,
        timeZone: TimeZone = TimeZone.getDefault()
    ): ByteArray = frame(CMD_TIME_SYNC) { packet ->
        val offsetSeconds = timeZone.getOffset(epochSeconds * 1000L) / 1000L
        val localEpoch = (epochSeconds + offsetSeconds).coerceAtLeast(0L)
        writeU32Le(packet, 1, localEpoch)
        packet[5] = ((offsetSeconds / 3600L) and 0xFFL).toByte()
    }

    fun buildUserInfoPacket(
        ageYears: Int = 30,
        isMale: Boolean = true,
        heightCm: Int = 175,
        weightKg: Int = 75
    ): ByteArray = frame(CMD_USER_INFO) { packet ->
        val age = ageYears.coerceIn(0, 127)
        packet[1] = (age or if (isMale) 0x80 else 0x00).toByte()
        packet[2] = heightCm.coerceIn(0, 255).toByte()
        packet[3] = weightKg.coerceIn(0, 255).toByte()
        packet[4] = 0x00 // metric
    }

    fun buildBatteryQueryPacket(): ByteArray = frame(CMD_BATTERY)

    fun buildDeviceInfoQueryPacket(): ByteArray = frame(CMD_DEVICE_INFO)

    fun buildActivityQueryPacket(): ByteArray = frame(CMD_CURRENT_SPORT)

    fun buildActivityHistoryQueryPacket(sinceEpochSeconds: Long = 0L): ByteArray =
        frame(CMD_ACTIVITY_HISTORY) { packet ->
            writeU32Le(packet, 1, sinceEpochSeconds)
        }

    fun buildHeartRateHistoryQueryPacket(sinceEpochSeconds: Long = 0L): ByteArray =
        frame(CMD_HEART_RATE_HISTORY) { packet ->
            writeU32Le(packet, 1, sinceEpochSeconds)
        }

    fun buildBandFunctionQueryPacket(): ByteArray = frame(CMD_BAND_FUNCTION)

    fun buildFindWatchPacket(intensity: Int = 0x0A): ByteArray = frame(CMD_FIND_WATCH) { packet ->
        packet[1] = intensity.coerceIn(1, 255).toByte()
    }

    fun buildHeartRateStartPacket(): ByteArray = frame(CMD_HEART_RATE_START) { packet ->
        // bytes[1..4]=reference_u32le (0xB4 = 180s), byte[5]=mode_code (1 = active)
        packet[1] = 0xB4.toByte()
        packet[5] = 0x01
    }

    /** Builds a `0x14` setHeartRateMode request with explicit reference value and mode byte. */
    fun buildHeartRateModePacket(
        referenceValue: Long = 0L,
        mode: Int = 1
    ): ByteArray = frame(CMD_HEART_RATE_START) { packet ->
        writeU32Le(packet, 1, referenceValue)
        packet[5] = (mode and 0xFF).toByte()
    }

    fun buildHeartRateStopPacket(mode: Int = 1): ByteArray = frame(CMD_HEART_RATE_STOP) { packet ->
        packet[5] = (mode and 0xFF).toByte()
    }

    /** Builds a `0x23` combined-sensor request (mode 1 = BP+HR, mode 2 = SpO2+HR+BP). */
    fun buildCombinedMeasurementStartPacket(mode: Int = 2): ByteArray = frame(CMD_SENSOR_MODE) { packet ->
        packet[1] = (mode and 0xFF).toByte()
    }

    fun buildCombinedMeasurementStopPacket(): ByteArray = frame(CMD_SENSOR_MODE)

    fun buildSpO2StartPacket(): ByteArray = buildCombinedMeasurementStartPacket(mode = 2)

    fun buildSpO2StopPacket(): ByteArray = buildCombinedMeasurementStopPacket()

    fun buildKeepalivePacket(): ByteArray = frame(CMD_KEEPALIVE)

    /**
     * Builds the `0x12` multi-frame notification sequence:
     * - Frame 1: `[0x12, totalFrames, 0x01, 0x00, category, uid[0..3], 0...]`
     * - Frame 2: `[0x12, totalFrames, 0x02, titleUtf8(<=17), 0...]`
     * - Frame 3..N: `[0x12, totalFrames, seq(3..), contentChunkUtf8(<=17), 0...]`
     */
    fun buildNotificationPackets(
        type: DigmaProtocolCodec.NotificationType,
        title: String,
        text: String,
        uid: Int = 1,
        categoryOverride: Int? = null
    ): List<ByteArray> {
        val normalizedUid = ((uid % (MAX_UID + 1)) + (MAX_UID + 1)) % (MAX_UID + 1)
        val uidBytes = String.format(java.util.Locale.US, "%04d", normalizedUid)
            .toByteArray(StandardCharsets.US_ASCII)
        val category = (categoryOverride ?: (type.code.toInt() and 0xFF)) and 0xFF

        val titleBytes = utf8Prefix(title, NOTIFY_TITLE_MAX_BYTES)
        val contentChunks = utf8Chunks(text, NOTIFY_CONTENT_MAX_BYTES, NOTIFY_MAX_CONTENT_FRAMES)
        val effectiveChunks = contentChunks.ifEmpty { listOf(ByteArray(0)) }
        val totalFrames = 2 + effectiveChunks.size

        val frames = ArrayList<ByteArray>(totalFrames)
        frames += frame(CMD_NOTIFICATION) { packet ->
            packet[1] = (totalFrames and 0xFF).toByte()
            packet[2] = 0x01
            packet[3] = 0x00
            packet[4] = category.toByte()
            System.arraycopy(uidBytes, 0, packet, 5, uidBytes.size)
        }
        frames += frame(CMD_NOTIFICATION) { packet ->
            packet[1] = (totalFrames and 0xFF).toByte()
            packet[2] = 0x02
            System.arraycopy(titleBytes, 0, packet, 3, titleBytes.size)
        }
        effectiveChunks.forEachIndexed { index, chunk ->
            val sequence = index + 3
            frames += frame(CMD_NOTIFICATION) { packet ->
                packet[1] = (totalFrames and 0xFF).toByte()
                packet[2] = (sequence and 0xFF).toByte()
                System.arraycopy(chunk, 0, packet, 3, chunk.size)
            }
        }
        return frames
    }

    fun parseIncomingPacket(
        data: ByteArray,
        preferThreeByteSport: Boolean? = null
    ): SxrEvent {
        if (data.isEmpty()) return SxrEvent.Unknown(0, data)
        val opcode = data[0].toInt() and 0xFF
        return when (opcode) {
            CMD_TIME_SYNC -> SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.TimeSyncAck)
            CMD_USER_INFO,
            CMD_AUTO_HEART,
            CMD_HEART_RATE_STOP,
            CMD_GOAL,
            CMD_SENSOR_MODE,
            0x25,
            CMD_SPO2_COMPLETE,
            CMD_KEEPALIVE -> SxrEvent.CommandAck(opcode)
            CMD_HR_COMPLETE -> parseHeartRateComplete(data)
            CMD_CURRENT_SPORT -> parseCurrentSport(data, preferThreeByteSport)
            CMD_ACTIVITY_HISTORY -> parseActivityHistory(data)
            CMD_HEART_RATE_HISTORY -> parseHeartRateHistory(data)
            CMD_FIND_WATCH -> SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.FindWatchAck)
            CMD_WATCH_ACTION -> parseWatchAction(data)
            CMD_LOCATION_ACTION -> SxrEvent.Standard(
                DigmaProtocolCodec.IncomingPacket.WatchEvent(DigmaProtocolCodec.WatchAction.UNKNOWN)
            )
            CMD_BATTERY -> parseBattery(data)
            CMD_DEVICE_INFO -> parseDeviceInfo(data)
            CMD_NOTIFICATION -> SxrEvent.CommandAck(CMD_NOTIFICATION)
            CMD_HEART_RATE_START -> parseLiveHeartRate(data)
            CMD_BAND_FUNCTION -> parseBandCapabilities(data)
            CMD_COMBINED_SENSOR -> parseCombinedSensor(data)
            CMD_SPO2_RESULT -> parseSpo2Result(data)
            CMD_BIND_INFO -> parseBindNotify(data)
            CMD_STEP_COUNTER -> parseStepCounter(data)
            CMD_FIRMWARE_VERSION -> parseFirmwareVersion(data)
            0x81, 0x82, 0x83, 0x84, 0x8B, 0x8C, 0x92, 0x94, 0x95, 0xA0, 0xA3, 0xA5 -> SxrEvent.CommandError(opcode)
            else -> SxrEvent.Unknown(opcode, data)
        }
    }

    /**
     * Infers whether a `0x03` current-sport frame uses 3-byte `u24le` fields (smartwatches)
     * vs 4-byte `u32le` fields (rings). Returns `null` when both layouts are plausible.
     */
    fun detectThreeByteSportLayout(data: ByteArray): Boolean? {
        if (data.size < 14 || (data[0].toInt() and 0xFF) != CMD_CURRENT_SPORT) return null
        if (data.size < 17) return true
        // In 4-byte ring frames ([5..8], [9..12], [13..16]), data[8] and data[12] are zero
        // for normal daily values (<16.7M), and data[11] is zero below 65,536m distance.
        if (data[8].toInt() != 0 || data[12].toInt() != 0) return true
        val steps3 = u24le(data, 5)
        if (data[11].toInt() != 0 && steps3 < 40_000) return true
        val dist4 = u32le(data, 9)
        if (dist4 >= 65_536L && steps3 < 40_000) return true
        val cal4 = u32le(data, 13)
        if (cal4 > 65_535L) return true
        if (cal4 in 1L..65_535L &&
            data[11].toInt() == 0 &&
            data[12].toInt() == 0 &&
            (data[13].toInt() != 0 || dist4 >= 256L)
        ) {
            return false
        }
        return null
    }

    /** Parses standard Bluetooth SIG Heart Rate Measurement characteristic (`0x2A37`) frames. */
    fun parseStandardBleHeartRate(data: ByteArray): DigmaProtocolCodec.IncomingPacket.HeartRate? {
        if (data.size < 2) return null
        val flags = data[0].toInt() and 0xFF
        val isUint16 = (flags and 0x01) != 0
        val bpm = if (isUint16) {
            if (data.size < 3) return null
            u16le(data, 1)
        } else {
            data[1].toInt() and 0xFF
        }
        if (bpm <= 0) return null
        return DigmaProtocolCodec.IncomingPacket.HeartRate(bpm, 0)
    }

    private fun parseCurrentSport(
        data: ByteArray,
        preferThreeByteSport: Boolean? = null
    ): SxrEvent {
        if (data.size < 14) return SxrEvent.Unknown(CMD_CURRENT_SPORT, data)
        val useThreeByteFields = preferThreeByteSport ?: (detectThreeByteSportLayout(data) != false)
        val steps = if (useThreeByteFields) u24le(data, 5) else u32le(data, 5).toInt()
        val distanceMeters = if (useThreeByteFields) u24le(data, 8) else u32le(data, 9).toInt()
        val rawCalories = if (useThreeByteFields) u24le(data, 11) else u32le(data, 13).toInt()
        // Daily calories cannot exceed 65,535 kcal; fallback to u16le if byte[13] holds status/duration.
        val calories = if (useThreeByteFields && rawCalories > 65_535) {
            u16le(data, 11)
        } else {
            rawCalories
        }
        return SxrEvent.Standard(
            DigmaProtocolCodec.IncomingPacket.Activity(
                steps = steps,
                calories = calories,
                distanceMeters = distanceMeters
            )
        )
    }

    private fun parseActivityHistory(data: ByteArray): SxrEvent {
        if (data.size < 20) return SxrEvent.Unknown(CMD_ACTIVITY_HISTORY, data)
        val baseEpochSeconds = u32le(data, 1)
        val samples = (5..19).map { data[it].toInt() and 0xFF }
        return SxrEvent.ActivityBuckets(baseEpochSeconds = baseEpochSeconds, stepSamples = samples)
    }

    private fun parseStepCounter(data: ByteArray): SxrEvent {
        if (data.size < 5) return SxrEvent.Unknown(CMD_STEP_COUNTER, data)
        val steps = u32le(data, 1).toInt()
        return SxrEvent.StepCounter(cumulativeSteps = steps)
    }

    private fun parseBattery(data: ByteArray): SxrEvent {
        if (data.size < 2) return SxrEvent.Unknown(CMD_BATTERY, data)
        val percent = (data[1].toInt() and 0xFF).coerceIn(0, 100)
        return SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.Battery(percent))
    }

    private fun parseDeviceInfo(data: ByteArray): SxrEvent {
        if (data.size < 3) return SxrEvent.Unknown(CMD_DEVICE_INFO, data)
        val mac = if (data.size >= 9) {
            (3..8).joinToString(":") { String.format(java.util.Locale.US, "%02X", data[it]) }
        } else {
            null
        }
        val firmware = if (data.size >= 13) {
            val version = u16le(data, 1)
            val cid = u16le(data, 9)
            val did = u16le(data, 11)
            String.format(java.util.Locale.US, "%04X%04XV%d", cid, did, version)
        } else {
            null
        }
        return SxrEvent.DeviceInfo(macAddress = mac, firmware = firmware)
    }

    private fun parseFirmwareVersion(data: ByteArray): SxrEvent {
        if (data.size < 6) return SxrEvent.Unknown(CMD_FIRMWARE_VERSION, data)
        return SxrEvent.DeviceInfo(macAddress = null, firmware = null, rawFirmwareNumber = u16le(data, 4))
    }

    private fun parseLiveHeartRate(data: ByteArray): SxrEvent {
        if (data.size < 2) return SxrEvent.Unknown(CMD_HEART_RATE_START, data)
        if (data.size < 6) {
            val compactBpm = data[1].toInt() and 0xFF
            return when {
                compactBpm in 25..250 -> SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.HeartRate(compactBpm, 0))
                compactBpm == 0 -> SxrEvent.CommandError(CMD_HEART_RATE_START)
                else -> SxrEvent.Unknown(CMD_HEART_RATE_START, data)
            }
        }
        val ts = u32le(data, 1)
        val bpm = data[5].toInt() and 0xFF
        if (bpm in 25..250) {
            return SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.HeartRate(bpm, 0))
        }
        if (ts == 0L && bpm == 0) return SxrEvent.CommandError(CMD_HEART_RATE_START)
        val compactBpm = data[2].toInt() and 0xFF
        if (ts in 1L..3L && compactBpm in 25..250) {
            return SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.HeartRate(compactBpm, 0))
        }
        return SxrEvent.CommandAck(CMD_HEART_RATE_START)
    }

    private fun parseHeartRateHistory(data: ByteArray): SxrEvent {
        if (data.size < 2) return SxrEvent.Unknown(CMD_HEART_RATE_HISTORY, data)
        if (data.size < 6) {
            val compactBpm = data[1].toInt() and 0xFF
            val samples = if (compactBpm in 25..250) listOf(compactBpm) else emptyList()
            return SxrEvent.HeartRateHistory(baseEpochSeconds = 0L, bpmSamples = samples)
        }
        val baseEpochSeconds = u32le(data, 1)
        val tailSamples = (5 until data.size)
            .map { data[it].toInt() and 0xFF }
            .filter { it in 25..250 }
        val samples = if (tailSamples.isNotEmpty()) {
            tailSamples
        } else if ((data[2].toInt() == 0 && data[3].toInt() == 0 && data[4].toInt() == 0) &&
            (data[1].toInt() and 0xFF) in 25..250
        ) {
            listOf(data[1].toInt() and 0xFF)
        } else {
            emptyList()
        }
        return SxrEvent.HeartRateHistory(baseEpochSeconds = baseEpochSeconds, bpmSamples = samples)
    }

    private fun parseHeartRateComplete(data: ByteArray): SxrEvent {
        val tailBpm = if (data.size >= 6) data[5].toInt() and 0xFF else 0
        val compactBpm = if (data.size >= 5 && data[2].toInt() == 0 && data[3].toInt() == 0 && data[4].toInt() == 0) {
            data[1].toInt() and 0xFF
        } else if (data.size in 2..4) {
            data[1].toInt() and 0xFF
        } else {
            0
        }
        val bpm = when {
            tailBpm in 25..250 -> tailBpm
            compactBpm in 25..250 -> compactBpm
            else -> 0
        }
        return SxrEvent.HeartRateComplete(heartRateBpm = bpm)
    }

    private fun parseCombinedSensor(data: ByteArray): SxrEvent {
        if (data.size < 6) return SxrEvent.Unknown(CMD_COMBINED_SENSOR, data)
        val hrRaw = data[1].toInt() and 0xFF
        val hr = if (hrRaw in 25..250) hrRaw else 0
        val systolic = data[2].toInt() and 0xFF
        val diastolic = data[3].toInt() and 0xFF
        val spo2Raw = data[4].toInt() and 0xFF
        val spo2 = if (spo2Raw in 80..100) spo2Raw else 0
        val fatigue = data[5].toInt() and 0xFF
        val stress = if (data.size > 6) data[6].toInt() and 0xFF else 0
        return SxrEvent.SensorSnapshot(
            heartRateBpm = hr,
            systolic = systolic,
            diastolic = diastolic,
            spO2Percent = spo2,
            fatigue = fatigue,
            stress = stress
        )
    }

    private fun parseSpo2Result(data: ByteArray): SxrEvent {
        if (data.size < 2) return SxrEvent.Unknown(CMD_SPO2_RESULT, data)
        val spo2 = data[1].toInt() and 0xFF
        return if (spo2 in 80..100) {
            SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.HeartRate(0, spo2))
        } else {
            SxrEvent.CommandAck(CMD_SPO2_RESULT)
        }
    }

    private fun parseBindNotify(data: ByteArray): SxrEvent {
        if (data.size < 2) return SxrEvent.Unknown(CMD_BIND_INFO, data)
        val action = data[1].toInt() and 0xFF
        val state = if (data.size >= 3) data[2].toInt() and 0xFF else 0
        return SxrEvent.BindNotify(action = action, state = state)
    }

    private fun parseBandCapabilities(data: ByteArray): SxrEvent {
        if (data.size < 2) return SxrEvent.Unknown(CMD_BAND_FUNCTION, data)
        return SxrEvent.BandCapabilities(data.copyOfRange(1, data.size))
    }

    private fun parseWatchAction(data: ByteArray): SxrEvent {
        if (data.size < 2) return SxrEvent.Unknown(CMD_WATCH_ACTION, data)
        val code = data[1].toInt() and 0xFF
        val action = when (code) {
            1 -> DigmaProtocolCodec.WatchAction.FIND_PHONE
            2 -> DigmaProtocolCodec.WatchAction.CAMERA_SHUTTER
            16 -> DigmaProtocolCodec.WatchAction.MUSIC_PLAY_PAUSE
            32 -> DigmaProtocolCodec.WatchAction.MUSIC_NEXT
            64 -> DigmaProtocolCodec.WatchAction.MUSIC_PREV
            else -> DigmaProtocolCodec.WatchAction.UNKNOWN
        }
        return SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.WatchEvent(action))
    }

    private fun writeU32Le(target: ByteArray, offset: Int, value: Long) {
        val v = value and 0xFFFFFFFFL
        target[offset] = (v and 0xFFL).toByte()
        target[offset + 1] = ((v ushr 8) and 0xFFL).toByte()
        target[offset + 2] = ((v ushr 16) and 0xFFL).toByte()
        target[offset + 3] = ((v ushr 24) and 0xFFL).toByte()
    }

    private fun u16le(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u24le(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
    }

    private fun u32le(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun utf8Prefix(text: String, maxBytes: Int): ByteArray {
        val chunks = utf8Chunks(text, maxBytes, maxChunks = 1)
        return chunks.firstOrNull() ?: ByteArray(0)
    }

    /**
     * Splits [text] into UTF-8 chunks of at most [maxBytesPerChunk] bytes without ever
     * splitting a multi-byte UTF-8 sequence or UTF-16 surrogate pair across frames.
     */
    internal fun utf8Chunks(
        text: String,
        maxBytesPerChunk: Int,
        maxChunks: Int = Int.MAX_VALUE
    ): List<ByteArray> {
        if (text.isEmpty() || maxBytesPerChunk <= 0 || maxChunks <= 0) return emptyList()
        val result = mutableListOf<ByteArray>()
        var index = 0
        while (index < text.length && result.size < maxChunks) {
            var chunkEnd = index
            var chunkBytes = 0
            while (chunkEnd < text.length) {
                val codePoint = text.codePointAt(chunkEnd)
                val charCount = Character.charCount(codePoint)
                val utf8Len = utf8CodePointLength(codePoint)
                if (chunkBytes + utf8Len > maxBytesPerChunk) {
                    if (chunkBytes == 0) {
                        // Single code point does not fit within maxBytesPerChunk; stop safely.
                        return result
                    }
                    break
                }
                chunkBytes += utf8Len
                chunkEnd += charCount
            }
            result += text.substring(index, chunkEnd).toByteArray(StandardCharsets.UTF_8)
            index = chunkEnd
        }
        return result
    }

    private fun utf8CodePointLength(codePoint: Int): Int = when {
        codePoint <= 0x7F -> 1
        codePoint <= 0x7FF -> 2
        codePoint <= 0xFFFF -> 3
        else -> 4
    }
}
