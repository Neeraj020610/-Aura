package com.aura.client

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class AuraService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val TAG = "AuraService"
        const val ACTION_STATUS_UPDATE = "com.aura.client.STATUS_UPDATE"
        const val ACTION_LOG_UPDATE = "com.aura.client.LOG_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LOG = "log_message"
        var isConnected = false
        var currentStatusMessage = "Disconnected"
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    private var deviceSlot = "phone_1"
    private var serverUrl = "ws://10.246.8.197:3000"
    private var customDeviceName = "Android Phone"
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isServiceRunning = true

    // Hardware controllers
    private var mediaPlayer: MediaPlayer? = null
    private var originalVolume: Int = -1
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isTorchOn = false

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (isServiceRunning && !isConnected) {
                logUi("Reconnecting to $serverUrl ...")
                connectWebSocket()
            }
        }
    }

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            if (isServiceRunning && isConnected) {
                sendTelemetry()
            }
            handler.postDelayed(this, 3000) // Send telemetry every 3 seconds
        }
    }

    private val notificationReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.aura.client.NEW_NOTIFICATION") {
                val dataStr = intent.getStringExtra("NOTIFICATION_DATA") ?: return
                try {
                    val notifJson = JSONObject(dataStr).apply {
                        put("deviceId", deviceSlot)
                    }
                    logUi("📩 Notif Forwarded: [${notifJson.optString("appName")}] ${notifJson.optString("title")}")
                    webSocket?.send(notifJson.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Error forwarding notification", e)
                }
            }
        }
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    val isLocked = km?.isKeyguardLocked ?: true
                    logUi("📱 Screen ON (Locked: $isLocked)")
                    sendTelemetry()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    logUi("🌑 Screen OFF (Locked)")
                    sendTelemetry()
                }
                Intent.ACTION_USER_PRESENT -> {
                    logUi("🔓 Device Unlocked by User")
                    sendTelemetry()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        acquireLocks()
        startForegroundService()
        setupCallListener()
        initTts()
        
        val notifFilter = IntentFilter("com.aura.client.NEW_NOTIFICATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationReceiver, notifFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notificationReceiver, notifFilter)
        }

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, screenFilter)

        handler.post(telemetryRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("SERVER_URL")?.let { 
            if (it.isNotBlank()) serverUrl = it 
        }
        intent?.getStringExtra("DEVICE_SLOT")?.let { 
            if (it.isNotBlank()) deviceSlot = it 
        }
        intent?.getStringExtra("DEVICE_NAME")?.let { 
            if (it.isNotBlank()) customDeviceName = it 
        }

        // Save preferences
        val prefs = getSharedPreferences("AuraPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("server_url", serverUrl)
            .putString("device_slot", deviceSlot)
            .putString("device_name", customDeviceName)
            .apply()

        logUi("Aura 24/7 Service Started (Slot: $deviceSlot, Hub: $serverUrl)")
        connectWebSocket()
        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraClient:WakeLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AuraClient:WifiLock")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
            Log.d(TAG, "WakeLock & WifiLock acquired successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire locks", e)
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
            .setContentText("24/7 Active Connection to Central Command Hub")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
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

    private fun logUi(message: String) {
        Log.d(TAG, message)
        val intent = Intent(ACTION_LOG_UPDATE).apply {
            putExtra(EXTRA_LOG, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(this, this)
        } catch (e: Exception) {
            Log.e(TAG, "TTS init error", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.ENGLISH
            isTtsReady = true
            Log.d(TAG, "TTS Initialized successfully")
        }
    }

    private fun speak(text: String) {
        if (isTtsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AuraTTS")
        }
    }

    private fun connectWebSocket() {
        try {
            webSocket?.cancel()
            updateStatus("Connecting to $serverUrl ...", false)

            val request = Request.Builder().url(serverUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    logUi("🟢 Connected to Aura Master Hub!")
                    updateStatus("Connected to Hub ($deviceSlot)", true)

                    val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                    val regJson = JSONObject().apply {
                        put("type", "REGISTER")
                        put("deviceId", deviceSlot)
                        put("name", deviceName)
                        put("deviceType", "mobile")
                    }
                    ws.send(regJson.toString())
                    sendTelemetry()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    logUi("📩 Received: $text")
                    handleCommand(text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    logUi("⚠️ WebSocket closing: $code / $reason")
                    updateStatus("Disconnected ($reason)", false)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    logUi("🔴 WebSocket closed: $code / $reason")
                    updateStatus("Disconnected", false)
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    logUi("❌ WebSocket failure: ${t.localizedMessage ?: "Unreachable"}")
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
            handler.postDelayed(reconnectRunnable, 2000)
        }
    }

    private fun handleCommand(messageJson: String) {
        try {
            val json = JSONObject(messageJson)
            if (json.optString("type") == "COMMAND") {
                val action = json.optString("action")
                val params = json.optJSONObject("params")

                logUi("⚡ Executing Action: $action")

                when (action) {
                    "CALL" -> {
                        val number = params?.optString("contact") ?: "9876543210"
                        makePhoneCall(number)
                    }
                    "LOCK" -> {
                        val locked = AuraAccessibilityService.instance?.lockScreen() ?: false
                        if (!locked) {
                            // Fallback to screen off
                            wakeLockOff()
                        }
                    }
                    "UNLOCK", "SCREEN_ON" -> {
                        wakeScreenOn()
                    }
                    "SCREEN_OFF" -> {
                        AuraAccessibilityService.instance?.lockScreen()
                    }
                    "RING_ALARM" -> {
                        playAlarmSound()
                    }
                    "STOP_ALARM" -> {
                        stopAlarmSound()
                    }
                    "SPEAK", "TTS" -> {
                        val speechText = params?.optString("text") ?: "Command executed, Sir."
                        speak(speechText)
                    }
                    "TORCH_ON", "FLASHLIGHT_ON" -> {
                        toggleTorch(true)
                    }
                    "TORCH_OFF", "FLASHLIGHT_OFF" -> {
                        toggleTorch(false)
                    }
                    "HOME" -> {
                        AuraAccessibilityService.instance?.pressHome()
                    }
                    "BACK" -> {
                        AuraAccessibilityService.instance?.pressBack()
                    }
                    "NOTIFICATIONS" -> {
                        AuraAccessibilityService.instance?.openNotifications()
                    }
                    "QUICK_SETTINGS" -> {
                        AuraAccessibilityService.instance?.openQuickSettings()
                    }
                    "SCREENSHOT" -> {
                        AuraAccessibilityService.instance?.takeScreenshot()
                    }
                    "GET_LOCATION", "LOCATE" -> {
                        fetchAndSendLocation()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling command", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchAndSendLocation() {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager == null) {
                logUi("LocationManager not available")
                return
            }

            var bestLocation: Location? = null

            // Check GPS Provider
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    if (loc != null) bestLocation = loc
                }
            } catch (e: Exception) {}

            // Check Network Provider if GPS not available
            try {
                if (bestLocation == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    val loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (loc != null) bestLocation = loc
                }
            } catch (e: Exception) {}

            // Check Passive Provider
            try {
                if (bestLocation == null && locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                    val loc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    if (loc != null) bestLocation = loc
                }
            } catch (e: Exception) {}

            if (bestLocation != null) {
                val locJson = JSONObject().apply {
                    put("type", "DEVICE_LOCATION")
                    put("deviceId", deviceSlot)
                    put("name", customDeviceName)
                    put("latitude", bestLocation.latitude)
                    put("longitude", bestLocation.longitude)
                    put("accuracy", bestLocation.accuracy)
                    put("timestamp", bestLocation.time)
                }
                logUi("📍 GPS Location Found: ${bestLocation.latitude}, ${bestLocation.longitude} (±${bestLocation.accuracy}m)")
                webSocket?.send(locJson.toString())
            } else {
                logUi("⚠️ Requesting fresh GPS coordinates...")
                val locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val locJson = JSONObject().apply {
                            put("type", "DEVICE_LOCATION")
                            put("deviceId", deviceSlot)
                            put("name", customDeviceName)
                            put("latitude", location.latitude)
                            put("longitude", location.longitude)
                            put("accuracy", location.accuracy)
                            put("timestamp", location.time)
                        }
                        logUi("📍 GPS Location Acquired: ${location.latitude}, ${location.longitude}")
                        webSocket?.send(locJson.toString())
                        try {
                            locationManager.removeUpdates(this)
                        } catch (e: Exception) {}
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    LocationManager.GPS_PROVIDER
                } else {
                    LocationManager.NETWORK_PROVIDER
                }

                locationManager.requestSingleUpdate(provider, locationListener, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            logUi("Error fetching location: ${e.message}")
        }
    }

    private fun makePhoneCall(number: String) {
        try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(callIntent)
            speak("Calling $number")
        } catch (e: Exception) {
            logUi("Call failed: ${e.message}")
        }
    }

    private fun wakeScreenOn() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "AuraClient:ScreenWake"
            )
            screenWakeLock.acquire(3000)

            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                keyguardManager?.requestDismissKeyguard(null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wake screen error", e)
        }
    }

    private fun wakeLockOff() {
        AuraAccessibilityService.instance?.lockScreen()
    }

    private fun toggleTorch(enable: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraId = cameraManager?.cameraIdList?.firstOrNull()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enable)
                isTorchOn = enable
                logUi("Torch toggled: $enable")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Torch error", e)
        }
    }

    private fun playAlarmSound() {
        try {
            stopAlarmSound()

            // Maximize alarm volume
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

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

            // Continuous Vibration
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 200, 600), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 600, 200, 600), 0)
            }

            // Auto stop after 25 seconds
            handler.postDelayed({
                stopAlarmSound()
            }, 25000)
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

            // Restore volume if changed
            if (originalVolume != -1) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
                originalVolume = -1
            }
        } catch (e: Exception) {}
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
        } catch (e: Exception) {}
    }

    private fun sendTelemetry() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, ifilter)
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                powerManager.isInteractive
            } else {
                @Suppress("DEPRECATION")
                powerManager.isScreenOn
            }

            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: false

            val screenState = when {
                !isScreenOn -> "OFF"
                isKeyguardLocked -> "LOCKED"
                else -> "UNLOCKED"
            }

            val telemetryJson = JSONObject().apply {
                put("type", "TELEMETRY")
                put("deviceId", deviceSlot)
                put("name", customDeviceName)
                put("battery", batteryPct)
                put("isCharging", isCharging)
                put("screen", screenState)
                put("accessibilityActive", AuraAccessibilityService.isServiceActive)
                put("torch", isTorchOn)
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
            unregisterReceiver(notificationReceiver)
        } catch (e: Exception) {}
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {}
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {}
        try {
            webSocket?.close(1000, "Service Destroyed")
        } catch (e: Exception) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {}
        updateStatus("Service Stopped", false)
    }
}
