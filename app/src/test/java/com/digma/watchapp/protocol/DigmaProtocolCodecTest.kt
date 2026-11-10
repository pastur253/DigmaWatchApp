package com.digma.watchapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DigmaProtocolCodecTest {

    @Test
    fun testBuildTimeSyncPacket() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.NOVEMBER) // 11th month (index 10) -> +1 = 11
            set(Calendar.DAY_OF_MONTH, 10)
            set(Calendar.HOUR_OF_DAY, 17)
            set(Calendar.MINUTE, 45)
            set(Calendar.SECOND, 30)
        }

        val packet = DigmaProtocolCodec.buildTimeSyncPacket(cal)

        assertEquals(11, packet.size)
        assertEquals(0xAB.toByte(), packet[0])
        assertEquals(DigmaProtocolCodec.CMD_TIME_SYNC, packet[1])
        assertEquals(0x07.toByte(), packet[2])

        val year = ((packet[3].toInt() and 0xFF) shl 8) or (packet[4].toInt() and 0xFF)
        assertEquals(2026, year)
        assertEquals(11, packet[5].toInt())
        assertEquals(10, packet[6].toInt())
        assertEquals(17, packet[7].toInt())
        assertEquals(45, packet[8].toInt())
        assertEquals(30, packet[9].toInt())

        val calculatedChk = DigmaProtocolCodec.calculateChecksum(packet, 1, 9)
        assertEquals(calculatedChk, packet[10])
    }

    @Test
    fun testBuildFindWatchPacket() {
        val packet = DigmaProtocolCodec.buildFindWatchPacket()
        assertEquals(5, packet.size)
        assertEquals(0xAB.toByte(), packet[0])
        assertEquals(DigmaProtocolCodec.CMD_FIND_WATCH, packet[1])
        assertEquals(0x01.toByte(), packet[2])
        assertEquals(0x01.toByte(), packet[3])
    }

    @Test
    fun testBuildNotificationPacketWithCyrillic() {
        val title = "Тест"
        val message = "Привет от DigmaWatchApp!"
        val packet = DigmaProtocolCodec.buildNotificationPacket(
            type = DigmaProtocolCodec.NotificationType.APP_MESSAGE,
            title = title,
            body = message
        )

        assertTrue(packet.size > 10)
        assertEquals(0xAB.toByte(), packet[0])
        assertEquals(DigmaProtocolCodec.CMD_NOTIFICATION, packet[1])
        assertEquals(DigmaProtocolCodec.NotificationType.APP_MESSAGE.code, packet[3])

        // Verify checksum at the end
        val expectedChk = DigmaProtocolCodec.calculateChecksum(packet, 1, packet.size - 2)
        assertEquals(expectedChk, packet[packet.size - 1])
    }

    @Test
    fun testParseBatteryPacket() {
        // [0xAB, 0x91, 0x01, 85, Checksum]
        val packet = byteArrayOf(0xAB.toByte(), 0x91.toByte(), 0x01, 85, 0x00)
        val event = DigmaProtocolCodec.parseIncomingPacket(packet)

        assertNotNull(event)
        assertTrue(event is DigmaProtocolCodec.IncomingPacket.Battery)
        assertEquals(85, (event as DigmaProtocolCodec.IncomingPacket.Battery).percent)
    }

    @Test
    fun testParseHeartRatePacket() {
        // [0xAB, 0x31, 0x02, 75 (bpm), 98 (SpO2), Checksum]
        val packet = byteArrayOf(0xAB.toByte(), 0x31, 0x02, 75, 98, 0x00)
        val event = DigmaProtocolCodec.parseIncomingPacket(packet)

        assertNotNull(event)
        assertTrue(event is DigmaProtocolCodec.IncomingPacket.HeartRate)
        val hrEvent = event as DigmaProtocolCodec.IncomingPacket.HeartRate
        assertEquals(75, hrEvent.heartRateBpm)
        assertEquals(98, hrEvent.spO2Percent)
    }

    @Test
    fun testParseActivityStepsPacket() {
        // [0xAB, 0x51, 0x08, 0x00, 0x00, 0x15, 0x7C (5500 steps), 0x00, 0xC8 (200 kcal), Checksum]
        val packet = byteArrayOf(
            0xAB.toByte(), 0x51, 0x08,
            0x00, 0x00, 0x15, 0x7C,
            0x00, 0xC8.toByte(),
            0x00, 0x00,
            0x00
        )
        val event = DigmaProtocolCodec.parseIncomingPacket(packet)

        assertNotNull(event)
        assertTrue(event is DigmaProtocolCodec.IncomingPacket.Activity)
        val actEvent = event as DigmaProtocolCodec.IncomingPacket.Activity
        assertEquals(5500, actEvent.steps)
        assertEquals(200, actEvent.calories)
    }

    @Test
    fun testParseWatchEventFindPhone() {
        // [0xAB, 0xFE, 0x01, 0x01, Checksum]
        val packet = byteArrayOf(0xAB.toByte(), 0xFE.toByte(), 0x01, 0x01, 0x00)
        val event = DigmaProtocolCodec.parseIncomingPacket(packet)

        assertNotNull(event)
        assertTrue(event is DigmaProtocolCodec.IncomingPacket.WatchEvent)
        assertEquals(
            DigmaProtocolCodec.WatchAction.FIND_PHONE,
            (event as DigmaProtocolCodec.IncomingPacket.WatchEvent).action
        )
    }

    @Test
    fun testParseIncomingPacketWithHeader0x5A() {
        // [0x5A, 0x91, 0x01, 92, Checksum] -> Incoming response from watch with 0x5A header
        val packet = byteArrayOf(0x5A.toByte(), 0x91.toByte(), 0x01, 92, 0x00)
        val event = DigmaProtocolCodec.parseIncomingPacket(packet)

        assertNotNull(event)
        assertTrue(event is DigmaProtocolCodec.IncomingPacket.Battery)
        assertEquals(92, (event as DigmaProtocolCodec.IncomingPacket.Battery).percent)
    }

    @Test
    fun testParseIncomingHeartRateWithHeader0x5A() {
        // [0x5A, 0x31, 0x02, 72, 99, 0x00]
        val packet = byteArrayOf(0x5A.toByte(), 0x31, 0x02, 72, 99, 0x00)
        val event = DigmaProtocolCodec.parseIncomingPacket(packet)

        assertNotNull(event)
        assertTrue(event is DigmaProtocolCodec.IncomingPacket.HeartRate)
        val hr = event as DigmaProtocolCodec.IncomingPacket.HeartRate
        assertEquals(72, hr.heartRateBpm)
        assertEquals(99, hr.spO2Percent)
    }

    @Test
    fun testPadToLength() {
        val original = byteArrayOf(0xAB.toByte(), 0x09, 0x01, 0x01, 0x08)
        val padded20 = DigmaProtocolCodec.padToLength(original, 20)
        assertEquals(20, padded20.size)
        assertEquals(0xAB.toByte(), padded20[0])
        assertEquals(0x09.toByte(), padded20[1])
        assertEquals(0x08.toByte(), padded20[4])
        assertEquals(0x00.toByte(), padded20[5])
        assertEquals(0x00.toByte(), padded20[19])
    }

    @Test
    fun testBuildFindWatchPacketLefun() {
        val pkt = DigmaProtocolCodec.buildFindWatchPacketLefun()
        assertEquals(4, pkt.size)
        assertEquals(0xAB.toByte(), pkt[0])
        assertEquals(0x04.toByte(), pkt[1])
        assertEquals(0x09.toByte(), pkt[2])
        assertEquals(0x90.toByte(), pkt[3])
    }
}
