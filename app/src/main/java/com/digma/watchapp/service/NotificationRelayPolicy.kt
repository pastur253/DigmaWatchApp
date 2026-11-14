package com.digma.watchapp.service

import com.digma.watchapp.model.WatchSettings
import com.digma.watchapp.protocol.DigmaProtocolCodec
import java.util.Calendar
import java.util.Locale

data class NotificationPresetApp(
    val packageName: String,
    val displayName: String
)

object NotificationRelayPolicy {

    const val CATEGORY_CALL = "call"

    private const val MAX_TITLE_CHARS = 64
    private const val MAX_BODY_CHARS = 220

    val POPULAR_MESSENGERS = listOf(
        NotificationPresetApp("com.exteragram.messenger", "exteraGram"),
        NotificationPresetApp("org.telegram.messenger", "Telegram"),
        NotificationPresetApp("org.telegram.plus", "Plus Messenger"),
        NotificationPresetApp("org.thunderdog.challegram", "Telegram X"),
        NotificationPresetApp("com.whatsapp", "WhatsApp"),
        NotificationPresetApp("com.viber.voip", "Viber"),
        NotificationPresetApp("com.vkontakte.android", "VK"),
        NotificationPresetApp("com.vk.im", "VK Мессенджер"),
        NotificationPresetApp("ru.oneme.app", "Max"),
        NotificationPresetApp("com.discord", "Discord")
    )

    data class ExtractedNotification(
        val title: String,
        val text: String
    )

    sealed class Decision {
        data class Forward(val type: DigmaProtocolCodec.NotificationType) : Decision()
        data class Skip(val reason: String, val logToDiagnostics: Boolean) : Decision()
    }

    fun evaluateDecision(
        packageName: String?,
        ownPackageName: String,
        category: String?,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        settings: WatchSettings,
        currentTimestampMs: Long = System.currentTimeMillis()
    ): Decision {
        if (!settings.notificationsEnabled) {
            return Decision.Skip("уведомления выключены", logToDiagnostics = false)
        }

        if (settings.quietHoursEnabled) {
            val cal = Calendar.getInstance().apply { timeInMillis = currentTimestampMs }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)
            if (isQuietHoursActive(
                    currentHour = hour,
                    currentMinute = minute,
                    startHour = settings.quietHoursStartHour,
                    startMinute = settings.quietHoursStartMinute,
                    endHour = settings.quietHoursEndHour,
                    endMinute = settings.quietHoursEndMinute
                )
            ) {
                val startStr = String.format(Locale.US, "%02d:%02d", settings.quietHoursStartHour, settings.quietHoursStartMinute)
                val endStr = String.format(Locale.US, "%02d:%02d", settings.quietHoursEndHour, settings.quietHoursEndMinute)
                return Decision.Skip("тихие часы по графику ($startStr–$endStr)", logToDiagnostics = true)
            }
        }

        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == ownPackageName) {
            return Decision.Skip("собственное уведомление", logToDiagnostics = false)
        }

        val type = classifyNotificationType(pkg, category)
        // Incoming call notifications (Dialer/Telecom/VoIP) typically carry FLAG_ONGOING_EVENT.
        if (isOngoing && type != DigmaProtocolCodec.NotificationType.CALL) {
            return Decision.Skip("постоянное фоновое уведомление", logToDiagnostics = false)
        }
        val typeAllowed = when (type) {
            DigmaProtocolCodec.NotificationType.CALL -> {
                if (!settings.forwardCalls) {
                    return Decision.Skip("пересылка звонков выключена", logToDiagnostics = true)
                }
                true
            }
            DigmaProtocolCodec.NotificationType.SMS -> {
                if (!settings.forwardSms) {
                    return Decision.Skip("пересылка SMS выключена", logToDiagnostics = true)
                }
                true
            }
            DigmaProtocolCodec.NotificationType.APP_MESSAGE -> {
                if (!settings.forwardApps) {
                    return Decision.Skip("пересылка приложений выключена", logToDiagnostics = true)
                }
                if (settings.forwardAllApps || settings.allowedPackages.contains(pkg)) {
                    true
                } else {
                    return Decision.Skip("пакет $pkg не в белом списке", logToDiagnostics = true)
                }
            }
            else -> false
        }

        if (isGroupSummary && typeAllowed) {
            return Decision.Skip("групповая сводка ($pkg)", logToDiagnostics = true)
        }
        return if (typeAllowed) Decision.Forward(type) else Decision.Skip("неподдерживаемый тип", logToDiagnostics = false)
    }

    fun isQuietHoursActive(
        currentHour: Int,
        currentMinute: Int,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ): Boolean {
        val currentMins = currentHour * 60 + currentMinute
        val startMins = startHour * 60 + startMinute
        val endMins = endHour * 60 + endMinute

        if (startMins == endMins) return false

        return if (startMins < endMins) {
            currentMins in startMins until endMins
        } else {
            currentMins >= startMins || currentMins < endMins
        }
    }

    fun classifyNotificationType(
        packageName: String,
        category: String?
    ): DigmaProtocolCodec.NotificationType {
        val pkgLower = packageName.lowercase()
        return when {
            category == CATEGORY_CALL ||
                pkgLower.contains("dialer") ||
                pkgLower.contains("telecom") ||
                pkgLower.contains("incallui") -> DigmaProtocolCodec.NotificationType.CALL

            pkgLower.contains("mms") ||
                pkgLower.contains("messaging") -> DigmaProtocolCodec.NotificationType.SMS

            else -> DigmaProtocolCodec.NotificationType.APP_MESSAGE
        }
    }

    fun extractContent(
        title: CharSequence?,
        conversationTitle: CharSequence?,
        bigText: CharSequence?,
        text: CharSequence?,
        textLines: List<CharSequence>?,
        subText: CharSequence?,
        messagingSender: CharSequence? = null,
        messagingText: CharSequence? = null,
        fallbackTitle: String = ""
    ): ExtractedNotification? {
        val normTitle = normalize(title)
        val normConv = normalize(conversationTitle)
        val normMsgSender = normalize(messagingSender)
        val normMsgText = normalize(messagingText)

        val resolvedTitle = when {
            normConv.isNotEmpty() && normTitle.isNotEmpty() && normConv != normTitle -> "$normConv: $normTitle"
            normTitle.isNotEmpty() -> normTitle
            normConv.isNotEmpty() -> normConv
            normMsgSender.isNotEmpty() -> normMsgSender
            else -> normalize(fallbackTitle)
        }.take(MAX_TITLE_CHARS)

        val linesJoined = textLines
            ?.map { normalize(it) }
            ?.filter { it.isNotEmpty() }
            ?.takeLast(3)
            ?.joinToString(" | ")
            .orEmpty()

        val resolvedBody = normalize(bigText)
            .ifEmpty { normMsgText }
            .ifEmpty { linesJoined }
            .ifEmpty { normalize(text) }
            .ifEmpty { normalize(subText) }
            .ifEmpty { resolvedTitle }
            .take(MAX_BODY_CHARS)

        if (resolvedTitle.isEmpty() && resolvedBody.isEmpty()) return null
        return ExtractedNotification(
            title = resolvedTitle.ifEmpty { resolvedBody.take(MAX_TITLE_CHARS) },
            text = resolvedBody
        )
    }

    private fun normalize(value: CharSequence?): String {
        if (value == null) return ""
        return value.toString()
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
