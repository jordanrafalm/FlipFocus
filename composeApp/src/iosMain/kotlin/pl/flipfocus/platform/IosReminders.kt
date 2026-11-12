@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pl.flipfocus.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSUserDefaults
import platform.Foundation.compare
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

internal object IosReminders {
    private val center = UNUserNotificationCenter.currentNotificationCenter()
    private val preferences = NSUserDefaults.standardUserDefaults
    private const val ENABLED = "flipfocus.reminders.enabled"
    private val identifiers = (0 until 32).map { "flipfocus.reminder.$it" }
    private const val HOUR = "flipfocus.reminders.hour"

    suspend fun setEnabled(enabled: Boolean, hour: Int): ReminderPermission {
        if (!enabled) {
            preferences.setBool(false, ENABLED)
            center.removePendingNotificationRequestsWithIdentifiers(identifiers)
            center.removeDeliveredNotificationsWithIdentifiers(identifiers)
            return ReminderPermission.GRANTED
        }
        if (hour !in 0..23) return ReminderPermission.UNAVAILABLE
        val granted = suspendCancellableCoroutine { continuation ->
            center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, error ->
                if (continuation.isActive) continuation.resume(granted && error == null)
            }
        }
        if (!granted) return ReminderPermission.DENIED
        preferences.setInteger(hour.toLong(), HOUR)
        val scheduled = schedule()
        preferences.setBool(scheduled, ENABLED)
        return if (scheduled) ReminderPermission.GRANTED else ReminderPermission.UNAVAILABLE
    }

    fun refreshIfEnabled() {
        if (!preferences.boolForKey(ENABLED)) return
        center.getNotificationSettingsWithCompletionHandler { settings ->
            if (settings?.authorizationStatus == UNAuthorizationStatusAuthorized ||
                settings?.authorizationStatus == UNAuthorizationStatusProvisional
            ) scheduleRequests()
        }
    }

    private suspend fun schedule(): Boolean {
        center.removePendingNotificationRequestsWithIdentifiers(identifiers)
        for (request in requests()) {
            val saved = suspendCancellableCoroutine { continuation ->
                center.addNotificationRequest(request) { error ->
                    if (continuation.isActive) continuation.resume(error == null)
                }
            }
            if (!saved) {
                center.removePendingNotificationRequestsWithIdentifiers(identifiers)
                return false
            }
        }
        return true
    }

    private fun scheduleRequests() {
        center.removePendingNotificationRequestsWithIdentifiers(identifiers)
        requests().forEach { center.addNotificationRequest(it, null) }
    }

    private fun requests(): List<UNNotificationRequest> {
        val hour = preferences.integerForKey(HOUR).toInt().takeIf { it in 0..23 } ?: 11
        val calendar = NSCalendar.currentCalendar
        val now = NSDate()
        val startComponents = calendar.components(
            NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute,
            now,
        ).apply {
            this.hour = hour.toLong()
            minute = 0
        }
        var start = calendar.dateFromComponents(startComponents) ?: return emptyList()
        if (start.compare(now) != platform.Foundation.NSOrderedDescending) {
            start = calendar.dateByAddingUnit(NSCalendarUnitDay, 1, start, 0u) ?: return emptyList()
        }
        val requests = mutableListOf<UNNotificationRequest>()
        var offset = 0L
        while (requests.size < identifiers.size && offset < 120L) {
            val date = calendar.dateByAddingUnit(NSCalendarUnitDay, offset, start, 0u) ?: break
            val dateComponents = calendar.components(
                NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute,
                date,
            )
            val reminderDate = kotlinx.datetime.LocalDate(
                dateComponents.year.toInt(), dateComponents.month.toInt(), dateComponents.day.toInt(),
            )
            if (ReminderCalendar.isWorkday(reminderDate)) {
                val identifier = identifiers[requests.size]
            val content = UNMutableNotificationContent().apply {
                setTitle("Chwila dla Twojego skupienia")
                setBody("Odłóż telefon ekranem w dół. Zacznij spokojne 25 minut z FlipFocus.")
                setSound(UNNotificationSound.defaultSound)
            }
                requests += UNNotificationRequest.requestWithIdentifier(
                    identifier, content, UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(dateComponents, false),
                )
            }
            offset += 1
        }
        return requests
    }
}
