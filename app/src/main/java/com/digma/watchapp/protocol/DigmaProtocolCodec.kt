package com.digma.watchapp.protocol

import java.util.Calendar

object DigmaProtocolCodec {

    const val HEADER: Byte = 0xAB.toByte()

    const val CMD_TIME_SYNC: Byte = 0x01
    const val CMD_FIND_WATCH: Byte = 0x09
    const val CMD_HEART_RATE: Byte = 0x31
    const val CMD_ACTIVITY: Byte = 0x51
    const val CMD_NOTIFICATION: Byte = 0x72
    const val CMD_BATTERY: Byte = 0x91.toByte()
    const val CMD_WATCH_EVENT: Byte = 0xFE.toByte()

    enum class NotificationType(val code: Byte) {
        CALL(0x01),
        MISSED_CALL(0x02),
        SMS(0x03),
        APP_MESSAGE(0x04)
    }

    enum class WatchAction {
        FIND_PHONE,
        MUSIC_PLAY_PAUSE,
        MUSIC_NEXT,
        MUSIC_PREV,
        CAMERA_SHUTTER,
        UNKNOWN
    }

    sealed class IncomingPacket {
        data class Battery(val percent: Int) : IncomingPacket()
        data class Activity(val steps: Int, val calories: Int, val distanceMeters: Int) : IncomingPacket()
        data class HeartRate(val heartRateBpm: Int, val spO2Percent: Int) : IncomingPacket()
        data class WatchEvent(val action: WatchAction) : IncomingPacket()
        object TimeSyncAck : IncomingPacket()
        object FindWatchAck : IncomingPacket()
        data class Unknown(val command: Byte, val payload: ByteArray) : IncomingPacket() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Unknown
                return command == other.command && payload.contentEquals(other.payload)
            }
            override fun hashCode(): Int = 31 * command + payload.contentHashCode()
        }
    }

    /**
     * Calculates 1-byte XOR checksum over the payload slice.
     */
    fun calculateChecksum(bytes: ByteArray, offset: Int = 1, length: Int = bytes.size - 2): Byte {
        var chk = 0
        for (i in offset until (offset + length)) {
            chk = chk xor (bytes[i].toInt() and 0xFF)
        }
        return (chk and 0xFF).toByte()
    }

    /**
     * Packet: [0xAB, 0x01, 0x07, YearH, YearL, Month, Day, Hour, Min, Sec, Checksum]
     */
    fun buildTimeSyncPacket(calendar: Calendar = Calendar.getInstance()): ByteArray {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val min = calendar.get(Calendar.MINUTE)
        val sec = calendar.get(Calendar.SECOND)

        val packet = ByteArray(11)
        packet[0] = HEADER
        packet[1] = CMD_TIME_SYNC
        packet[2] = 0x07 // length
        packet[3] = ((year shr 8) and 0xFF).toByte()
        packet[4] = (year and 0xFF).toByte()
        packet[5] = (month and 0xFF).toByte()
        packet[6] = (day and 0xFF).toByte()
        packet[7] = (hour and 0xFF).toByte()
        packet[8] = (min and 0xFF).toByte()
        packet[9] = (sec and 0xFF).toByte()
        packet[10] = calculateChecksum(packet, 1, 9)
        return packet
    }

    /**
     * Format B (1-byte year offset from 2000):
     * Packet: [0xAB, 0x01, 0x06, Year%100, Month, Day, Hour, Min, Sec, Checksum]
     */
    fun buildTimeSyncPacketShort(calendar: Calendar = Calendar.getInstance()): ByteArray {
        val year = calendar.get(Calendar.YEAR) % 100
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val min = calendar.get(Calendar.MINUTE)
        val sec = calendar.get(Calendar.SECOND)

        val packet = ByteArray(10)
        packet[0] = HEADER
        packet[1] = CMD_TIME_SYNC
        packet[2] = 0x06
        packet[3] = (year and 0xFF).toByte()
        packet[4] = (month and 0xFF).toByte()
        packet[5] = (day and 0xFF).toByte()
        packet[6] = (hour and 0xFF).toByte()
        packet[7] = (min and 0xFF).toByte()
        packet[8] = (sec and 0xFF).toByte()
        packet[9] = calculateChecksum(packet, 1, 8)
        return packet
    }

    /**
     * Packet: [0xAB, 0x09, 0x01, 0x01, Checksum]
     */
    fun buildFindWatchPacket(): ByteArray {
        val packet = ByteArray(5)
        packet[0] = HEADER
        packet[1] = CMD_FIND_WATCH
        packet[2] = 0x01
        packet[3] = 0x01
        packet[4] = calculateChecksum(packet, 1, 3)
        return packet
    }

    /**
     * Packet: [0xAB, 0x31, 0x01, (1=Start / 0=Stop), Checksum]
     */
    fun buildHeartRateControlPacket(start: Boolean): ByteArray {
        val packet = ByteArray(5)
        packet[0] = HEADER
        packet[1] = CMD_HEART_RATE
        packet[2] = 0x01
        packet[3] = if (start) 0x01 else 0x00
        packet[4] = calculateChecksum(packet, 1, 3)
        return packet
    }

    /**
     * Packet: [0xAB, 0x91, 0x00, Checksum]
     */
    fun buildBatteryQueryPacket(): ByteArray {
        val packet = ByteArray(4)
        packet[0] = HEADER
        packet[1] = CMD_BATTERY
        packet[2] = 0x00
        packet[3] = calculateChecksum(packet, 1, 2)
        return packet
    }

    /**
     * Packet: [0xAB, 0x51, 0x00, Checksum]
     */
    fun buildActivityRequestPacket(): ByteArray {
        val packet = ByteArray(4)
        packet[0] = HEADER
        packet[1] = CMD_ACTIVITY
        packet[2] = 0x00
        packet[3] = calculateChecksum(packet, 1, 2)
        return packet
    }

    /**
     * Builds push notification packet framed to safely fit into BLE MTU:
     * Header: 0xAB, Cmd: 0x72, Len, Type, TitleLen, TitleBytes, BodyLen, BodyBytes, Checksum
     */
    fun buildNotificationPacket(
        type: NotificationType,
        title: String,
        body: String,
        maxPayloadBytes: Int = 120
    ): ByteArray {
        val cleanTitle = title.take(24)
        val cleanBody = body.take(90)

        val titleBytes = cleanTitle.toByteArray(Charsets.UTF_8)
        val bodyBytes = cleanBody.toByteArray(Charsets.UTF_8)

        val payloadLen = 1 + 1 + titleBytes.size + 1 + bodyBytes.size
        val packet = ByteArray(3 + payloadLen + 1)

        packet[0] = HEADER
        packet[1] = CMD_NOTIFICATION
        packet[2] = (payloadLen and 0xFF).toByte()
        packet[3] = type.code

        packet[4] = (titleBytes.size and 0xFF).toByte()
        System.arraycopy(titleBytes, 0, packet, 5, titleBytes.size)

        val bodyOffset = 5 + titleBytes.size
        packet[bodyOffset] = (bodyBytes.size and 0xFF).toByte()
        System.arraycopy(bodyBytes, 0, packet, bodyOffset + 1, bodyBytes.size)

        packet[packet.size - 1] = calculateChecksum(packet, 1, packet.size - 2)
        return packet
    }

    /**
     * Parses an incoming raw byte packet received from the watch notify characteristic.
     */
    fun parseIncomingPacket(packet: ByteArray): IncomingPacket? {
        if (packet.size < 4 || packet[0] != HEADER) {
            return null
        }

        val cmd = packet[1]
        val len = packet[2].toInt() and 0xFF
        if (packet.size < 3 + len) {
            return null
        }

        return when (cmd) {
            CMD_TIME_SYNC -> IncomingPacket.TimeSyncAck
            CMD_FIND_WATCH -> IncomingPacket.FindWatchAck
            CMD_BATTERY -> {
                val battery = if (len >= 1) packet[3].toInt() and 0xFF else 0
                IncomingPacket.Battery(battery.coerceIn(0, 100))
            }
            CMD_ACTIVITY -> {
                if (len >= 4) {
                    val stepsLe = (packet[3].toInt() and 0xFF) or
                            ((packet[4].toInt() and 0xFF) shl 8) or
                            ((packet[5].toInt() and 0xFF) shl 16) or
                            ((packet[6].toInt() and 0xFF) shl 24)

                    val stepsBe = ((packet[3].toInt() and 0xFF) shl 24) or
                            ((packet[4].toInt() and 0xFF) shl 16) or
                            ((packet[5].toInt() and 0xFF) shl 8) or
                            (packet[6].toInt() and 0xFF)

                    val steps = if (stepsBe in 0..500_000 && stepsLe !in 0..500_000) stepsBe else stepsLe

                    val calBe = if (len >= 8) ((packet[7].toInt() and 0xFF) shl 8) or (packet[8].toInt() and 0xFF) else 0
                    val calLe = if (len >= 8) (packet[7].toInt() and 0xFF) or ((packet[8].toInt() and 0xFF) shl 8) else 0
                    val cal = if (len >= 8 && packet[7].toInt() == 0) calBe else calLe

                    val distBe = if (len >= 12) ((packet[9].toInt() and 0xFF) shl 8) or (packet[10].toInt() and 0xFF) else 0
                    val distLe = if (len >= 12) (packet[9].toInt() and 0xFF) or ((packet[10].toInt() and 0xFF) shl 8) else 0
                    val dist = if (len >= 12 && packet[9].toInt() == 0) distBe else distLe

                    IncomingPacket.Activity(steps, cal, dist)
                } else {
                    IncomingPacket.Unknown(cmd, packet.copyOfRange(3, 3 + len))
                }
            }
            CMD_HEART_RATE -> {
                val hr = if (len >= 1) packet[3].toInt() and 0xFF else 0
                val spo2 = if (len >= 2) packet[4].toInt() and 0xFF else 0
                IncomingPacket.HeartRate(hr, spo2)
            }
            CMD_WATCH_EVENT -> {
                val actionCode = if (len >= 1) packet[3].toInt() and 0xFF else 0
                val action = when (actionCode) {
                    0x01 -> WatchAction.FIND_PHONE
                    0x02 -> WatchAction.MUSIC_PLAY_PAUSE
                    0x03 -> WatchAction.MUSIC_NEXT
                    0x04 -> WatchAction.MUSIC_PREV
                    0x05 -> WatchAction.CAMERA_SHUTTER
                    else -> WatchAction.UNKNOWN
                }
                IncomingPacket.WatchEvent(action)
            }
            else -> {
                IncomingPacket.Unknown(cmd, packet.copyOfRange(3, 3 + len))
            }
        }
    }
}
