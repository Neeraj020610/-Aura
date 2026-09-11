package com.aura.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("AuraBootReceiver", "Boot completed detected, auto-starting Aura Service...")
            val prefs = context.getSharedPreferences("AuraPrefs", Context.MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", "ws://10.246.8.197:3000")
            val deviceSlot = prefs.getString("device_slot", "phone_1")

            val serviceIntent = Intent(context, AuraService::class.java).apply {
                putExtra("SERVER_URL", serverUrl)
                putExtra("DEVICE_SLOT", deviceSlot)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
