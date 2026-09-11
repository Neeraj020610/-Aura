package com.aura.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

import androidx.core.app.NotificationCompat

class AuraService : Service() {

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private var deviceSlot = "phone_1"
    private var serverUrl = "ws://192.168.31.33:3000"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        setupCallListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("SERVER_URL")?.let { serverUrl = it }
        intent?.getStringExtra("DEVICE_SLOT")?.let { deviceSlot = it }
        connectWebSocket()
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "AuraServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Aura Background Hub", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Aura Device Node")
            .setContentText("Connected to Central Command Hub")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(101, notification)
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("Aura", "Connected to Aura Hub!")
                // Register Device
                val regJson = JSONObject().apply {
                    put("type", "REGISTER")
                    put("deviceId", deviceSlot)
                    put("name", "Android Device (${Build.MODEL})")
                    put("type", "mobile")
                }
                ws.send(regJson.toString())
                sendTelemetry()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleCommand(text)
            }
        })
    }

    private fun handleCommand(messageJson: String) {
        try {
            val json = JSONObject(messageJson)
            if (json.optString("type") == "COMMAND") {
                val action = json.optString("action")
                val params = json.optJSONObject("params")

                when (action) {
                    "CALL" -> {
                        val number = params?.optString("contact") ?: "9876543210"
                        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(callIntent)
                    }
                    "LOCK" -> {
                        // Triggers lock via Accessibility Service
                        AuraAccessibilityService.instance?.lockScreen()
                    }
                    "RING_ALARM" -> {
                        // Play alarm sound
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Aura", "Error handling command", e)
        }
    }

    private fun setupCallListener() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_RINGING) {
                    val alertJson = JSONObject().apply {
                        put("type", "TRIGGER_INCOMING_CALL")
                        put("targetDevice", deviceSlot)
                        put("caller", "Incoming Contact")
                        put("number", incomingNumber ?: "Private Number")
                    }
                    webSocket?.send(alertJson.toString())
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun sendTelemetry() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        val ifilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, ifilter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val telemetryJson = JSONObject().apply {
            put("type", "TELEMETRY")
            put("deviceId", deviceSlot)
            put("battery", batteryPct)
            put("isCharging", isCharging)
            put("screen", "ON")
        }
        webSocket?.send(telemetryJson.toString())
    }
}
