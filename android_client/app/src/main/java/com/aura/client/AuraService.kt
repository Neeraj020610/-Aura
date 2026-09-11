package com.aura.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
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

class AuraService : Service() {

    companion object {
        const val TAG = "AuraService"
        const val ACTION_STATUS_UPDATE = "com.aura.client.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"
        var isConnected = false
        var currentStatusMessage = "Disconnected"
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var deviceSlot = "phone_1"
    private var serverUrl = "ws://10.246.8.197:3000"
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isServiceRunning = true
    private var mediaPlayer: MediaPlayer? = null

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (isServiceRunning && !isConnected) {
                Log.d(TAG, "Attempting WebSocket reconnection to $serverUrl ...")
                connectWebSocket()
            }
        }
    }

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            if (isServiceRunning && isConnected) {
                sendTelemetry()
            }
            handler.postDelayed(this, 5000) // Send telemetry every 5 seconds
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        acquireWakeLock()
        startForegroundService()
        setupCallListener()
        handler.post(telemetryRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("SERVER_URL")?.let { 
            if (it.isNotBlank()) serverUrl = it 
        }
        intent?.getStringExtra("DEVICE_SLOT")?.let { 
            if (it.isNotBlank()) deviceSlot = it 
        }
        
        Log.d(TAG, "Starting Aura Service with URL: $serverUrl, Slot: $deviceSlot")
        connectWebSocket()
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraClient:BackgroundService")
            wakeLock?.acquire(10 * 60 * 1000L /* 10 minutes, refreshed */)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun startForegroundService() {
        val channelId = "AuraServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Aura Background Hub", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("Aura Device Node")
            .setContentText("Connected to Central Command Hub")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(101, notification)
    }

    private fun updateStatus(status: String, connected: Boolean) {
        isConnected = connected
        currentStatusMessage = status
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun connectWebSocket() {
        try {
            webSocket?.cancel()
            updateStatus("Connecting to $serverUrl ...", false)

            val request = Request.Builder().url(serverUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "Connected to Aura Hub!")
                    updateStatus("Connected to Hub ($deviceSlot)", true)

                    // Register this device with the hub
                    val regJson = JSONObject().apply {
                        put("type", "REGISTER")
                        put("deviceId", deviceSlot)
                        put("name", "${Build.MANUFACTURER.capitalize()} ${Build.MODEL}")
                        put("deviceType", "mobile")
                    }
                    ws.send(regJson.toString())
                    sendTelemetry()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(TAG, "Received message: $text")
                    handleCommand(text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WebSocket closing: $code / $reason")
                    updateStatus("Disconnected ($reason)", false)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WebSocket closed: $code / $reason")
                    updateStatus("Disconnected", false)
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure: ${t.message}", t)
                    updateStatus("Connection Failed: ${t.localizedMessage ?: "Unreachable"}", false)
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating WebSocket", e)
            updateStatus("Error: ${e.message}", false)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (isServiceRunning) {
            handler.removeCallbacks(reconnectRunnable)
            handler.postDelayed(reconnectRunnable, 4000) // retry after 4 seconds
        }
    }

    private fun handleCommand(messageJson: String) {
        try {
            val json = JSONObject(messageJson)
            if (json.optString("type") == "COMMAND") {
                val action = json.optString("action")
                val params = json.optJSONObject("params")

                Log.d(TAG, "Executing Action: $action")

                when (action) {
                    "CALL" -> {
                        val number = params?.optString("contact") ?: "9876543210"
                        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(callIntent)
                    }
                    "LOCK" -> {
                        AuraAccessibilityService.instance?.lockScreen()
                    }
                    "RING_ALARM" -> {
                        playAlarmSound()
                    }
                    "STOP_ALARM" -> {
                        stopAlarmSound()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling command", e)
        }
    }

    private fun playAlarmSound() {
        try {
            stopAlarmSound()
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alertUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }

            // Vibrate
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 200, 500), 0)
            }

            // Auto stop after 20 seconds
            handler.postDelayed({
                stopAlarmSound()
            }, 20000)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing alarm", e)
        }
    }

    private fun stopAlarmSound() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null

            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alarm", e)
        }
    }

    private fun setupCallListener() {
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            telephonyManager.listen(object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
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
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up call listener", e)
        }
    }

    private fun sendTelemetry() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, ifilter)
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            val telemetryJson = JSONObject().apply {
                put("type", "TELEMETRY")
                put("deviceId", deviceSlot)
                put("name", "${Build.MANUFACTURER.capitalize()} ${Build.MODEL}")
                put("battery", batteryPct)
                put("isCharging", isCharging)
                put("screen", "ON")
            }
            webSocket?.send(telemetryJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending telemetry", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        handler.removeCallbacksAndMessages(null)
        stopAlarmSound()
        try {
            webSocket?.close(1000, "Service Destroyed")
        } catch (e: Exception) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {}
        updateStatus("Service Stopped", false)
    }
}
