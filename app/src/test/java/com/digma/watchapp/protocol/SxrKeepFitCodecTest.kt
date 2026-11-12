package com.digma.watchapp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.TimeZone

class SxrKeepFitCodecTest {

    @Test
    fun `buildAppIdPacket encodes opcode 0x48 and app id into 20 bytes`() {
        val pkt = SxrKeepFitCodec.buildAppIdPacket("com.digma.watchapp")
        assertEquals(20, pkt.size)
        assertEquals(0x48.toByte(), pkt[0])
        val decoded = String(pkt, 1, "com.digma.watchapp".length, StandardCharsets.US_ASCII)
        assertEquals("com.digma.watchapp", decoded)
        assertEquals(0.toByte(), pkt[19])
    }

    @Test
    fun `buildBindPackets encode INIT reply and ACK completion correctly`() {
        val start = SxrKeepFitCodec.buildBindAppStartPacket()
        assertEquals(20, start.size)
        assertEquals(0x4B.toByte(), start[0])
        assertEquals(0x01.toByte(), start[1])
        assertEquals(0x00.toByte(), start[2])
        assertEquals(0x01.toByte(), start[3])

        val done = SxrKeepFitCodec.buildBindSuccessPacket()
        assertEquals(20, done.size)
        assertEquals(0x4B.toByte(), done[0])
        assertEquals(0x04.toByte(), done[1])
        assertEquals(0x00.toByte(), done[2])
        assertEquals(0x01.toByte(), done[3])
    }

    @Test
    fun `buildTimeSyncPacket encodes local wall clock epoch LE u32 and tz hour`() {
        val tzMoscow = TimeZone.getTimeZone("GMT+3")
        val utcSec = 1_700_000_000L
        val pkt = SxrKeepFitCodec.buildTimeSyncPacket(utcSec, tzMoscow)

        assertEquals(20, pkt.size)
        assertEquals(0x01.toByte(), pkt[0])
        val expectedLocal = utcSec + 3 * 3600L
        val actualLocal = (pkt[1].toLong() and 0xFFL) or
            ((pkt[2].toLong() and 0xFFL) shl 8) or
            ((pkt[3].toLong() and 0xFFL) shl 16) or
            ((pkt[4].toLong() and 0xFFL) shl 24)
        assertEquals(expectedLocal, actualLocal)
        assertEquals(3.toByte(), pkt[5])

        val tzMinus5 = TimeZone.getTimeZone("GMT-5")
        val pktNeg = SxrKeepFitCodec.buildTimeSyncPacket(utcSec, tzMinus5)
        assertEquals((-5).toByte(), pktNeg[5])
    }

    @Test
    fun `simple query and control commands build exact 20-byte frames`() {
        val battery = SxrKeepFitCodec.buildBatteryQueryPacket()
        assertEquals(20, battery.size)
        assertEquals(0x0B.toByte(), battery[0])

        val info = SxrKeepFitCodec.buildDeviceInfoQueryPacket()
        assertEquals(20, info.size)
        assertEquals(0x0C.toByte(), info[0])

        val sport = SxrKeepFitCodec.buildActivityQueryPacket()
        assertEquals(20, sport.size)
        assertEquals(0x03.toByte(), sport[0])

        val find = SxrKeepFitCodec.buildFindWatchPacket()
        assertEquals(20, find.size)
        assertEquals(0x04.toByte(), find[0])
        assertEquals(0x0A.toByte(), find[1])

        val hrStart = SxrKeepFitCodec.buildHeartRateStartPacket()
        assertEquals(0x14.toByte(), hrStart[0])
        assertEquals(0xB4.toByte(), hrStart[1])
        assertEquals(0x01.toByte(), hrStart[5])

        val hrMode = SxrKeepFitCodec.buildHeartRateModePacket(referenceValue = 1L, mode = 1)
        assertEquals(0x14.toByte(), hrMode[0])
        assertEquals(0x01.toByte(), hrMode[1])
        assertEquals(0x01.toByte(), hrMode[5])

        val hrStop = SxrKeepFitCodec.buildHeartRateStopPacket()
        assertEquals(0x15.toByte(), hrStop[0])
        assertEquals(0x01.toByte(), hrStop[5])

        val spo2Start = SxrKeepFitCodec.buildSpO2StartPacket()
        assertEquals(0x23.toByte(), spo2Start[0])
        assertEquals(0x02.toByte(), spo2Start[1])

        val spo2Stop = SxrKeepFitCodec.buildSpO2StopPacket()
        assertEquals(0x23.toByte(), spo2Stop[0])
        assertEquals(0x00.toByte(), spo2Stop[1])

        val keepalive = SxrKeepFitCodec.buildKeepalivePacket()
        assertEquals(0x3A.toByte(), keepalive[0])
    }

    @Test
    fun `buildNotificationPackets chunks Cyrillic UTF-8 cleanly without splitting codepoints`() {
        val title = "Телеграм Сообщение"
        val message = "Привет! Проверка длинного русского текста для часов Digma D3L."
        val frames = SxrKeepFitCodec.buildNotificationPackets(
            type = DigmaProtocolCodec.NotificationType.APP_MESSAGE,
            title = title,
            text = message,
            uid = 7
        )

        assertTrue(frames.size >= 4)
        frames.forEach { assertEquals(20, it.size) }

        // Frame 1: header
        val total = frames.size.toByte()
        assertEquals(0x12.toByte(), frames[0][0])
        assertEquals(total, frames[0][1])
        assertEquals(0x01.toByte(), frames[0][2])
        assertEquals(0x00.toByte(), frames[0][3])
        assertEquals(DigmaProtocolCodec.NotificationType.APP_MESSAGE.code, frames[0][4])
        assertEquals("0007", String(frames[0], 5, 4, StandardCharsets.US_ASCII))

        // Frame 2: title
        assertEquals(0x12.toByte(), frames[1][0])
        assertEquals(total, frames[1][1])
        assertEquals(0x02.toByte(), frames[1][2])
        val decodedTitle = decodeNullTerminatedUtf8(frames[1], 3)
        assertFalse(decodedTitle.contains('\uFFFD'))
        assertTrue(decodedTitle.toByteArray(StandardCharsets.UTF_8).size <= 17)

        // Frame 3..N: content chunks
        val reconstructedContent = StringBuilder()
        for (i in 2 until frames.size) {
            val frame = frames[i]
            assertEquals(0x12.toByte(), frame[0])
            assertEquals(total, frame[1])
            assertEquals((i + 1).toByte(), frame[2])
            val part = decodeNullTerminatedUtf8(frame, 3)
            assertFalse("Split UTF-8 detected in chunk $i: $part", part.contains('\uFFFD'))
            reconstructedContent.append(part)
        }
        assertEquals(message, reconstructedContent.toString())
    }

    @Test
    fun `buildNotificationPackets emits 3 frames for empty body`() {
        val frames = SxrKeepFitCodec.buildNotificationPackets(
            type = DigmaProtocolCodec.NotificationType.SMS,
            title = "SMS",
            text = "",
            uid = 12
        )
        assertEquals(3, frames.size)
        assertEquals(3.toByte(), frames[0][1])
        assertEquals(DigmaProtocolCodec.NotificationType.SMS.code, frames[0][4])
        assertEquals("SMS", decodeNullTerminatedUtf8(frames[1], 3))
        assertEquals("", decodeNullTerminatedUtf8(frames[2], 3))
    }

    @Test
    fun `parseIncomingPacket decodes battery, deviceInfo, activity, stepCounter and errors`() {
        // 0x0B Battery 84%
        val battPkt = ByteArray(20).apply {
            this[0] = 0x0B
            this[1] = 84
            this[2] = 1
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.Battery(84)),
            SxrKeepFitCodec.parseIncomingPacket(battPkt)
        )

        // 0x0C Device Info -> MAC & firmware "%04X%04XV%d"
        val devPkt = ByteArray(20).apply {
            this[0] = 0x0C
            this[1] = 0x8A.toByte()
            this[2] = 0x00
            this[3] = 0xAA.toByte()
            this[4] = 0xBB.toByte()
            this[5] = 0xCC.toByte()
            this[6] = 0xDD.toByte()
            this[7] = 0xEE.toByte()
            this[8] = 0xFF.toByte()
            this[9] = 0x3A
            this[10] = 0x00
            this[11] = 0x2A
            this[12] = 0x00
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.DeviceInfo(macAddress = "AA:BB:CC:DD:EE:FF", firmware = "003A002AV138"),
            SxrKeepFitCodec.parseIncomingPacket(devPkt)
        )

        // 0x03 Current sport (steps=5432, distance=3900m, calories=215)
        val sportPkt = ByteArray(20).apply {
            this[0] = 0x03
            putU32Le(this, 5, 5432)
            putU32Le(this, 9, 3900)
            putU32Le(this, 13, 215)
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.Activity(5432, 215, 3900)),
            SxrKeepFitCodec.parseIncomingPacket(sportPkt)
        )

        // 0x51 Step counter (steps=1024)
        val stepPkt = ByteArray(20).apply {
            this[0] = 0x51
            putU32Le(this, 1, 1024)
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.StepCounter(1024),
            SxrKeepFitCodec.parseIncomingPacket(stepPkt)
        )

        // 0x8B error
        val errPkt = ByteArray(20).apply { this[0] = 0x8B.toByte() }
        assertEquals(SxrKeepFitCodec.SxrEvent.CommandError(0x8B), SxrKeepFitCodec.parseIncomingPacket(errPkt))
    }

    @Test
    fun `parseIncomingPacket decodes HR, combined sensor, SpO2, bind, and watch actions`() {
        // 0x14 live HR valid
        val hrOk = ByteArray(20).apply {
            this[0] = 0x14
            putU32Le(this, 1, 1_700_000_123)
            this[5] = 74
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.HeartRate(74, 0)),
            SxrKeepFitCodec.parseIncomingPacket(hrOk)
        )

        // 0x14 live HR ts==0 error
        val hrErr = ByteArray(20).apply {
            this[0] = 0x14
            this[5] = 0
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.CommandError(0x14),
            SxrKeepFitCodec.parseIncomingPacket(hrErr)
        )

        // 0x24 combined sensor
        val combined = ByteArray(20).apply {
            this[0] = 0x24
            this[1] = 78
            this[2] = 120
            this[3] = 80
            this[4] = 98
            this[5] = 25
            this[6] = 35
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.SensorSnapshot(78, 120, 80, 98, 25, 35),
            SxrKeepFitCodec.parseIncomingPacket(combined)
        )

        // 0x3F SpO2
        val spo2 = ByteArray(20).apply {
            this[0] = 0x3F
            this[1] = 97
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.HeartRate(0, 97)),
            SxrKeepFitCodec.parseIncomingPacket(spo2)
        )

        // 0x4B bind INIT
        val bind = ByteArray(20).apply {
            this[0] = 0x4B
            this[1] = 0x00
            this[2] = 0x00
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.BindNotify(0, 0),
            SxrKeepFitCodec.parseIncomingPacket(bind)
        )

        // 0x06 FIND_PHONE (1) & MUSIC_PLAY_PAUSE (16)
        val findPhone = ByteArray(20).apply {
            this[0] = 0x06
            this[1] = 0x01
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(
                DigmaProtocolCodec.IncomingPacket.WatchEvent(DigmaProtocolCodec.WatchAction.FIND_PHONE)
            ),
            SxrKeepFitCodec.parseIncomingPacket(findPhone)
        )

        val playPause = ByteArray(20).apply {
            this[0] = 0x06
            this[1] = 16
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(
                DigmaProtocolCodec.IncomingPacket.WatchEvent(DigmaProtocolCodec.WatchAction.MUSIC_PLAY_PAUSE)
            ),
            SxrKeepFitCodec.parseIncomingPacket(playPause)
        )
    }

    @Test
    fun `parseIncomingPacket handles 0x01 TimeSyncAck, 0x04 FindWatchAck, and 0x20 capabilities`() {
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.TimeSyncAck),
            SxrKeepFitCodec.parseIncomingPacket(ByteArray(20).apply { this[0] = 0x01 })
        )
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.FindWatchAck),
            SxrKeepFitCodec.parseIncomingPacket(ByteArray(20).apply { this[0] = 0x04 })
        )
        val cap = ByteArray(20).apply {
            this[0] = 0x20
            this[1] = 0x0F
            this[2] = 0xAA.toByte()
        }
        val parsed = SxrKeepFitCodec.parseIncomingPacket(cap) as SxrKeepFitCodec.SxrEvent.BandCapabilities
        assertArrayEquals(cap.copyOfRange(1, 20), parsed.bitmask)
    }

    @Test
    fun `parseCurrentSport decodes 3-byte u24le watch fields from Digma D3L regression vector`() {
        // Exact wire layout reconstructed from v0.0.6 report (352321569=0x15000021, 131072=0x00020000, 0)
        // Watch values: 33 steps (0x21), 21 meters (0x15), 2 kcal (0x02)
        val d3lPacket = byteArrayOf(
            0x03,
            0x10, 0x20, 0x30, 0x67,
            0x21, 0x00, 0x00,
            0x15, 0x00, 0x00,
            0x02, 0x00, 0x00,
            0x00, 0x00, 0x00,
            0x00, 0x00, 0x00
        )
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.Activity(33, 2, 21)),
            SxrKeepFitCodec.parseIncomingPacket(d3lPacket)
        )

        // 3-byte packet where distance is exact multiple of 256 (0x0100 = 256m) and calories=10
        val alignedWatchPacket = byteArrayOf(
            0x03,
            0x00, 0x00, 0x00, 0x00,
            0xC0.toByte(), 0x01, 0x00, // 448 steps
            0x00, 0x01, 0x00,          // 256m
            0x0A, 0x00, 0x00,          // 10 kcal
            0x00, 0x00, 0x00,
            0x00, 0x00, 0x00
        )
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.Activity(448, 10, 256)),
            SxrKeepFitCodec.parseIncomingPacket(alignedWatchPacket)
        )

        // Compact 14-byte 3-byte frame
        val compactPacket = byteArrayOf(
            0x03,
            0x00, 0x00, 0x00, 0x00,
            0x05, 0x00, 0x00,
            0x03, 0x00, 0x00,
            0x01, 0x00, 0x00
        )
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.Activity(5, 1, 3)),
            SxrKeepFitCodec.parseIncomingPacket(compactPacket)
        )

        // 3-byte packet with aligned distance (256m) AND non-zero trailing telemetry in bytes[14..16]
        val watchWithTailPacket = byteArrayOf(
            0x03,
            0x10, 0x20, 0x30, 0x67,
            0xF4.toByte(), 0x01, 0x00, // 500 steps
            0x00, 0x01, 0x00,          // 256m (byte[8] == 0)
            0x0F, 0x00, 0x00,          // 15 kcal
            0x0C, 0x00, 0x00,          // trailing telemetry (byte[14] != 0)
            0x00, 0x00, 0x00
        )
        assertEquals(
            true,
            SxrKeepFitCodec.detectThreeByteSportLayout(watchWithTailPacket)
        )
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.Activity(500, 15, 256)),
            SxrKeepFitCodec.parseIncomingPacket(watchWithTailPacket)
        )

        // 3-byte packet with status/duration in byte[13] must not inflate calories by 65536x
        val watchWithByte13Tail = byteArrayOf(
            0x03,
            0x00, 0x00, 0x00, 0x00,
            0x90.toByte(), 0x01, 0x00, // 400 steps
            0x2C, 0x01, 0x00,          // 300m
            0x19, 0x00, 0x04,          // 25 kcal + byte[13]=0x04
            0x00, 0x00, 0x00,
            0x00, 0x00, 0x00
        )
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.Activity(400, 25, 300)),
            SxrKeepFitCodec.parseIncomingPacket(watchWithByte13Tail)
        )

        // Ambiguous distance=256m, calories=0 with preferThreeByteSport=true
        val ambiguousWatchPacket = byteArrayOf(
            0x03,
            0x00, 0x00, 0x00, 0x00,
            0xFA.toByte(), 0x00, 0x00, // 250 steps
            0x00, 0x01, 0x00,          // 256m
            0x00, 0x00, 0x00,          // 0 kcal
            0x05, 0x00, 0x00,          // tail=5
            0x00, 0x00, 0x00
        )
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.Activity(250, 0, 256)),
            SxrKeepFitCodec.parseIncomingPacket(ambiguousWatchPacket, preferThreeByteSport = true)
        )

        // 0x10 Activity history buckets (15x 1-minute samples)
        val historyPkt = ByteArray(20).apply {
            this[0] = 0x10
            putU32Le(this, 1, 1_700_000_000)
            this[5] = 12
            this[6] = 18
            this[19] = 5
        }
        val historyEvent = SxrKeepFitCodec.parseIncomingPacket(historyPkt) as SxrKeepFitCodec.SxrEvent.ActivityBuckets
        assertEquals(1_700_000_000L, historyEvent.baseEpochSeconds)
        assertEquals(15, historyEvent.stepSamples.size)
        assertEquals(35, historyEvent.stepSamples.sum())
    }

    @Test
    fun `parseLiveHeartRate and parseStandardBleHeartRate handle zero-ts and SIG 0x2A37 frames`() {
        // 0x14 live HR with ts==0 but valid BPM at byte[5]
        val zeroTsHr = ByteArray(20).apply {
            this[0] = 0x14
            this[5] = 72
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.Standard(DigmaProtocolCodec.IncomingPacket.HeartRate(72, 0)),
            SxrKeepFitCodec.parseIncomingPacket(zeroTsHr)
        )

        // 0x14 echo with ts!=0 and bpm==0 should be CommandAck, not zero BPM sample
        val warmupEcho = ByteArray(20).apply {
            this[0] = 0x14
            this[1] = 0xB4.toByte()
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.CommandAck(0x14),
            SxrKeepFitCodec.parseIncomingPacket(warmupEcho)
        )

        // 0x15 HR stop ACK and 0x23 PPG ACK
        assertEquals(
            SxrKeepFitCodec.SxrEvent.CommandAck(0x15),
            SxrKeepFitCodec.parseIncomingPacket(ByteArray(20).apply { this[0] = 0x15 })
        )
        assertEquals(
            SxrKeepFitCodec.SxrEvent.CommandAck(0x23),
            SxrKeepFitCodec.parseIncomingPacket(ByteArray(20).apply { this[0] = 0x23 })
        )

        // Standard BLE 0x2A37 uint8 and uint16 formats
        assertEquals(
            DigmaProtocolCodec.IncomingPacket.HeartRate(68, 0),
            SxrKeepFitCodec.parseStandardBleHeartRate(byteArrayOf(0x00, 68))
        )
        assertEquals(
            DigmaProtocolCodec.IncomingPacket.HeartRate(112, 0),
            SxrKeepFitCodec.parseStandardBleHeartRate(byteArrayOf(0x01, 0x70, 0x00))
        )
        assertEquals(
            null,
            SxrKeepFitCodec.parseStandardBleHeartRate(byteArrayOf(0x00, 0x00))
        )

        // 0x14 mode=1 echo must NOT be treated as 1 BPM
        val modeEcho = SxrKeepFitCodec.buildHeartRateStartPacket()
        assertEquals(
            SxrKeepFitCodec.SxrEvent.CommandAck(SxrKeepFitCodec.CMD_HEART_RATE_START),
            SxrKeepFitCodec.parseIncomingPacket(modeEcho)
        )

        // 0x16 HR history query & multi-sample / single-sample decode
        val hrQuery = SxrKeepFitCodec.buildHeartRateHistoryQueryPacket(0L)
        assertEquals(SxrKeepFitCodec.CMD_HEART_RATE_HISTORY, hrQuery[0].toInt() and 0xFF)
        val hrHistory = ByteArray(20).apply {
            this[0] = 0x16
            putU32Le(this, 1, 1_700_000_000)
            this[5] = 68
            this[6] = 72
            this[7] = 75
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.HeartRateHistory(1_700_000_000L, listOf(68, 72, 75)),
            SxrKeepFitCodec.parseIncomingPacket(hrHistory)
        )

        // 0x27 HR complete with and without BPM
        val hrCompleteWithBpm = ByteArray(20).apply {
            this[0] = 0x27
            this[5] = 81
        }
        assertEquals(
            SxrKeepFitCodec.SxrEvent.HeartRateComplete(81),
            SxrKeepFitCodec.parseIncomingPacket(hrCompleteWithBpm)
        )
        assertEquals(
            SxrKeepFitCodec.SxrEvent.HeartRateComplete(0),
            SxrKeepFitCodec.parseIncomingPacket(ByteArray(20).apply { this[0] = 0x27 })
        )
    }

    private fun decodeNullTerminatedUtf8(frame: ByteArray, offset: Int): String {
        var end = frame.size
        for (i in offset until frame.size) {
            if (frame[i] == 0.toByte()) {
                end = i
                break
            }
        }
        return String(frame, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun putU32Le(target: ByteArray, offset: Int, value: Int) {
        val v = value.toLong() and 0xFFFFFFFFL
        target[offset] = (v and 0xFFL).toByte()
        target[offset + 1] = ((v ushr 8) and 0xFFL).toByte()
        target[offset + 2] = ((v ushr 16) and 0xFFL).toByte()
        target[offset + 3] = ((v ushr 24) and 0xFFL).toByte()
    }
}
