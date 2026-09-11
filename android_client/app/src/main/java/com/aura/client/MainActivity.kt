package com.aura.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusTextView: TextView
    private lateinit var ipInput: EditText
    private lateinit var slotSpinner: Spinner
    private lateinit var deviceNameInput: EditText
    private lateinit var logConsole: TextView
    private val logMessages = mutableListOf<String>()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AuraService.ACTION_STATUS_UPDATE) {
                val status = intent.getStringExtra(AuraService.EXTRA_STATUS) ?: "Unknown"
                updateStatusUi(status)
            } else if (intent?.action == AuraService.ACTION_LOG_UPDATE) {
                val log = intent.getStringExtra(AuraService.EXTRA_LOG) ?: ""
                appendLog(log)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("AuraPrefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "ws://10.246.8.197:3000") ?: "ws://10.246.8.197:3000"
        val savedSlot = prefs.getString("device_slot", "phone_1") ?: "phone_1"
        val savedName = prefs.getString("device_name", "${Build.MANUFACTURER.capitalize()} ${Build.MODEL}") ?: "My Android Device"

        requestPermissionsIfNeeded()

        val scrollView = ScrollView(this)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 45, 40, 50)
            setBackgroundColor(Color.parseColor("#07090e"))
        }

        // Header Title
        val titleText = TextView(this).apply {
            text = "⚡ AURA // DEVICE NODE"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00f3ff"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 15)
        }
        rootLayout.addView(titleText)

        // Live Status Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(25, 20, 25, 20)
            setBackgroundColor(Color.parseColor("#0f172a"))
        }

        val statusLabel = TextView(this).apply {
            text = "LIVE SYSTEM STATUS"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94a3b8"))
        }
        statusCard.addView(statusLabel)

        statusTextView = TextView(this).apply {
            text = AuraService.currentStatusMessage
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (AuraService.isConnected) Color.parseColor("#10b981") else Color.parseColor("#f43f5e"))
            setPadding(0, 6, 0, 0)
        }
        statusCard.addView(statusTextView)
        rootLayout.addView(statusCard)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })

        // Config Header
        val configLabel = TextView(this).apply {
            text = "CONFIGURATION & PAIRING"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00f3ff"))
            setPadding(0, 0, 0, 10)
        }
        rootLayout.addView(configLabel)

        // Server URL
        rootLayout.addView(createSmallLabel("Central Hub WebSocket URL:"))
        ipInput = EditText(this).apply {
            setText(savedUrl)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#475569"))
            setBackgroundColor(Color.parseColor("#0f172a"))
            setPadding(25, 25, 25, 25)
            textSize = 14f
        }
        rootLayout.addView(ipInput)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 15) })

        // Device Slot Selector
        rootLayout.addView(createSmallLabel("Pair Device Slot Identity:"))
        val slots = arrayOf("phone_1 (Primary Phone)", "phone_2 (Secondary Phone)")
        val slotAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, slots)
        slotSpinner = Spinner(this).apply {
            adapter = slotAdapter
            setSelection(if (savedSlot == "phone_2") 1 else 0)
            setBackgroundColor(Color.parseColor("#0f172a"))
            setPadding(20, 20, 20, 20)
        }
        rootLayout.addView(slotSpinner)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 15) })

        // Device Custom Name
        rootLayout.addView(createSmallLabel("Device Display Name:"))
        deviceNameInput = EditText(this).apply {
            setText(savedName)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0f172a"))
            setPadding(25, 25, 25, 25)
            textSize = 14f
        }
        rootLayout.addView(deviceNameInput)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 25) })

        // Start Service Button
        val btnStart = Button(this).apply {
            text = "▶ START 24/7 BACKGROUND SERVICE"
            setBackgroundColor(Color.parseColor("#00f3ff"))
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            setPadding(0, 25, 0, 25)
            setOnClickListener {
                startAuraService()
            }
        }
        rootLayout.addView(btnStart)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 12) })

        // Stop Service Button
        val btnStop = Button(this).apply {
            text = "⏹ STOP SERVICE"
            setBackgroundColor(Color.parseColor("#1e293b"))
            setTextColor(Color.WHITE)
            textSize = 13f
            setOnClickListener {
                val serviceIntent = Intent(this@MainActivity, AuraService::class.java)
                stopService(serviceIntent)
                updateStatusUi("Service Stopped")
                appendLog("Aura Service Stopped manually")
                Toast.makeText(this@MainActivity, "Aura Service Stopped", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(btnStop)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 25) })

        // Permissions Section
        val permLabel = TextView(this).apply {
            text = "DEVICE PERMISSIONS (Tap to enable)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94a3b8"))
            setPadding(0, 0, 0, 10)
        }
        rootLayout.addView(permLabel)

        // Battery Optimization Exemption
        val btnBatteryOpt = Button(this).apply {
            text = "🔋 DISABLE BATTERY RESTRICTIONS (24/7 Background)"
            setBackgroundColor(Color.parseColor("#1e3a5f"))
            setTextColor(Color.parseColor("#60a5fa"))
            textSize = 12f
            setOnClickListener {
                requestIgnoreBatteryOptimization()
            }
        }
        rootLayout.addView(btnBatteryOpt)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 10) })

        // Accessibility Button
        val btnAccessibility = Button(this).apply {
            text = "⚙️ ENABLE REMOTE LOCK (Accessibility Service)"
            setBackgroundColor(Color.parseColor("#3b2d54"))
            setTextColor(Color.parseColor("#e0b0ff"))
            textSize = 12f
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Turn ON 'Aura Node' in Accessibility Services", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }
        rootLayout.addView(btnAccessibility)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 25) })

        // Diagnostic Console
        val consoleLabel = TextView(this).apply {
            text = "DIAGNOSTIC LOG CONSOLE"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#64748b"))
            setPadding(0, 0, 0, 6)
        }
        rootLayout.addView(consoleLabel)

        logConsole = TextView(this).apply {
            text = "Console ready. Waiting for events..."
            textSize = 11f
            setTextColor(Color.parseColor("#10b981"))
            setBackgroundColor(Color.parseColor("#050811"))
            setPadding(20, 20, 20, 20)
            setTypeface(Typeface.MONOSPACE)
        }
        rootLayout.addView(logConsole)

        scrollView.addView(rootLayout)
        setContentView(scrollView)
    }

    private fun createSmallLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#cbd5e1"))
            setPadding(0, 4, 0, 4)
        }
    }

    private fun startAuraService() {
        val selectedSlot = if (slotSpinner.selectedItemPosition == 1) "phone_2" else "phone_1"
        val url = ipInput.text.toString().trim()
        val name = deviceNameInput.text.toString().trim()

        val serviceIntent = Intent(this, AuraService::class.java).apply {
            putExtra("SERVER_URL", url)
            putExtra("DEVICE_SLOT", selectedSlot)
            putExtra("DEVICE_NAME", name)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "Aura 24/7 Service Started!", Toast.LENGTH_SHORT).show()
        updateStatusUi("Connecting to $url ...")
        appendLog("Started service with URL: $url ($selectedSlot)")
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Battery Restrictions already Disabled!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatusUi(status: String) {
        statusTextView.text = status
        if (status.contains("Connected", ignoreCase = true)) {
            statusTextView.setTextColor(Color.parseColor("#10b981"))
        } else if (status.contains("Connecting", ignoreCase = true)) {
            statusTextView.setTextColor(Color.parseColor("#f59e0b"))
        } else {
            statusTextView.setTextColor(Color.parseColor("#f43f5e"))
        }
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $msg"
        logMessages.add(entry)
        if (logMessages.size > 8) logMessages.removeAt(0)
        logConsole.text = logMessages.joinToString("\n")
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.CALL_PHONE)
            }
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_PHONE_STATE)
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.CAMERA)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (permissions.isNotEmpty()) {
                requestPermissions(permissions.toTypedArray(), 100)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(AuraService.ACTION_STATUS_UPDATE)
            addAction(AuraService.ACTION_LOG_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        updateStatusUi(AuraService.currentStatusMessage)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {}
    }
}
