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
}
