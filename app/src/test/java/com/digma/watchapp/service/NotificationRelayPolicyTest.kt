package com.digma.watchapp.service

import com.digma.watchapp.model.WatchSettings
import com.digma.watchapp.protocol.DigmaProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRelayPolicyTest {

    @Test
    fun `default settings forward exteraGram and other messengers`() {
        val decision = NotificationRelayPolicy.evaluateDecision(
            packageName = "com.exteragram.messenger",
            ownPackageName = "com.digma.watchapp",
            category = "msg",
            isOngoing = false,
            isGroupSummary = false,
            settings = WatchSettings()
        )
        assertEquals(
            NotificationRelayPolicy.Decision.Forward(DigmaProtocolCodec.NotificationType.APP_MESSAGE),
            decision
        )
    }

    @Test
    fun `exteraGram is allowed by default preset even when forwardAllApps is false`() {
        val whitelistSettings = WatchSettings(forwardAllApps = false)
        val decision = NotificationRelayPolicy.evaluateDecision(
            packageName = "com.exteragram.messenger",
            ownPackageName = "com.digma.watchapp",
            category = "msg",
            isOngoing = false,
            isGroupSummary = false,
            settings = whitelistSettings
        )
        assertEquals(
            NotificationRelayPolicy.Decision.Forward(DigmaProtocolCodec.NotificationType.APP_MESSAGE),
            decision
        )
    }

    @Test
    fun `whitelist blocks unlisted packages when forwardAllApps is false`() {
        val whitelistSettings = WatchSettings(
            forwardAllApps = false,
            allowedPackages = setOf("org.telegram.messenger")
        )
        val decision = NotificationRelayPolicy.evaluateDecision(
            packageName = "com.exteragram.messenger",
            ownPackageName = "com.digma.watchapp",
            category = "msg",
            isOngoing = false,
            isGroupSummary = false,
            settings = whitelistSettings
        )
        assertTrue(decision is NotificationRelayPolicy.Decision.Skip)
    }

    @Test
    fun `group summary and ongoing notifications are skipped`() {
        val summary = NotificationRelayPolicy.evaluateDecision(
            packageName = "com.exteragram.messenger",
            ownPackageName = "com.digma.watchapp",
            category = "msg",
            isOngoing = false,
            isGroupSummary = true,
            settings = WatchSettings()
        )
        assertTrue(summary is NotificationRelayPolicy.Decision.Skip)

        val ongoing = NotificationRelayPolicy.evaluateDecision(
            packageName = "com.exteragram.messenger",
            ownPackageName = "com.digma.watchapp",
            category = "service",
            isOngoing = true,
            isGroupSummary = false,
            settings = WatchSettings()
        )
        assertTrue(ongoing is NotificationRelayPolicy.Decision.Skip)

        val ongoingCall = NotificationRelayPolicy.evaluateDecision(
            packageName = "com.android.dialer",
            ownPackageName = "com.digma.watchapp",
            category = NotificationRelayPolicy.CATEGORY_CALL,
            isOngoing = true,
            isGroupSummary = false,
            settings = WatchSettings()
        )
        assertEquals(
            NotificationRelayPolicy.Decision.Forward(DigmaProtocolCodec.NotificationType.CALL),
            ongoingCall
        )
    }

    @Test
    fun `extractContent prefers bigText and messagingStyle fields cleanly`() {
        val fromMessagingStyle = NotificationRelayPolicy.extractContent(
            title = "Алексей",
            conversationTitle = "Рабочий чат",
            bigText = null,
            text = null,
            textLines = null,
            subText = null,
            messagingSender = "Алексей",
            messagingText = "Привет!\nКак дела?"
        )
        assertEquals("Рабочий чат: Алексей", fromMessagingStyle?.title)
        assertEquals("Привет! Как дела?", fromMessagingStyle?.text)

        val empty = NotificationRelayPolicy.extractContent(
            title = "  ",
            conversationTitle = null,
            bigText = null,
            text = "",
            textLines = emptyList(),
            subText = null,
            fallbackTitle = ""
        )
        assertNull(empty)
    }

    @Test
    fun `isQuietHoursActive detects same-day and overnight windows accurately`() {
        // Same-day window: 13:00 to 15:00
        assertTrue(NotificationRelayPolicy.isQuietHoursActive(13, 0, 13, 0, 15, 0))
        assertTrue(NotificationRelayPolicy.isQuietHoursActive(14, 30, 13, 0, 15, 0))
        org.junit.Assert.assertFalse(NotificationRelayPolicy.isQuietHoursActive(12, 59, 13, 0, 15, 0))
        org.junit.Assert.assertFalse(NotificationRelayPolicy.isQuietHoursActive(15, 0, 13, 0, 15, 0))

        // Overnight window: 22:00 to 07:00
        assertTrue(NotificationRelayPolicy.isQuietHoursActive(22, 0, 22, 0, 7, 0))
        assertTrue(NotificationRelayPolicy.isQuietHoursActive(23, 45, 22, 0, 7, 0))
        assertTrue(NotificationRelayPolicy.isQuietHoursActive(3, 15, 22, 0, 7, 0))
        assertTrue(NotificationRelayPolicy.isQuietHoursActive(6, 59, 22, 0, 7, 0))
        org.junit.Assert.assertFalse(NotificationRelayPolicy.isQuietHoursActive(7, 0, 22, 0, 7, 0))
        org.junit.Assert.assertFalse(NotificationRelayPolicy.isQuietHoursActive(14, 0, 22, 0, 7, 0))
        org.junit.Assert.assertFalse(NotificationRelayPolicy.isQuietHoursActive(21, 59, 22, 0, 7, 0))
    }

    @Test
    fun `evaluateDecision respects quiet hours schedule`() {
        val quietSettings = WatchSettings(
            quietHoursEnabled = true,
            quietHoursStartHour = 22,
            quietHoursStartMinute = 0,
            quietHoursEndHour = 7,
            quietHoursEndMinute = 0
        )

        // 23:30 local time
        val calendarDuringQuiet = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 30)
        }
        val duringQuiet = NotificationRelayPolicy.evaluateDecision(
            packageName = "org.telegram.messenger",
            ownPackageName = "com.digma.watchapp",
            category = "msg",
            isOngoing = false,
            isGroupSummary = false,
            settings = quietSettings,
            currentTimestampMs = calendarDuringQuiet.timeInMillis
        )
        assertTrue(duringQuiet is NotificationRelayPolicy.Decision.Skip)
        assertTrue((duringQuiet as NotificationRelayPolicy.Decision.Skip).reason.contains("тихие часы"))

        // 14:00 local time (outside quiet hours)
        val calendarDaytime = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 14)
            set(java.util.Calendar.MINUTE, 0)
        }
        val daytime = NotificationRelayPolicy.evaluateDecision(
            packageName = "org.telegram.messenger",
            ownPackageName = "com.digma.watchapp",
            category = "msg",
            isOngoing = false,
            isGroupSummary = false,
            settings = quietSettings,
            currentTimestampMs = calendarDaytime.timeInMillis
        )
        assertEquals(
            NotificationRelayPolicy.Decision.Forward(DigmaProtocolCodec.NotificationType.APP_MESSAGE),
            daytime
        )
    }
}
