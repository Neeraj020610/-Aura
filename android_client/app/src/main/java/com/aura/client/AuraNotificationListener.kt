package com.aura.client

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

class AuraNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "AuraNotifListener"
        var isListenerActive = false
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerActive = true
        Log.d(TAG, "Aura Notification Listener Connected & Listening!")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListenerActive = false
        Log.d(TAG, "Aura Notification Listener Disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        try {
            val packageName = sbn.packageName ?: return

            // Ignore system notifications and our own app's foreground notification
            if (packageName == packageName && sbn.id == 101) return
            if (packageName == "android" || packageName.contains("systemui", ignoreCase = true)) return

            val extras: Bundle? = sbn.notification?.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val messageContent = if (bigText.isNotBlank()) bigText else text

            // If there's no text or title, skip
            if (title.isBlank() && messageContent.isBlank()) return

            // Get app friendly name
            val pm = packageManager
            val appName = try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }

            Log.d(TAG, "Notification Received: [$appName] $title -> $messageContent")

            // Send notification data to AuraService
            val notifJson = JSONObject().apply {
                put("type", "PHONE_NOTIFICATION")
                put("appName", appName)
                put("packageName", packageName)
                put("title", title)
                put("text", messageContent)
                put("timestamp", sbn.postTime)
            }

            // Broadcast to AuraService
            val intent = Intent("com.aura.client.NEW_NOTIFICATION").apply {
                putExtra("NOTIFICATION_DATA", notifJson.toString())
                setPackage(packageName)
            }
            sendBroadcast(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
