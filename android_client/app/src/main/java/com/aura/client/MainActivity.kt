package com.aura.client

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private lateinit var statusTextView: TextView
    private lateinit var ipInput: EditText
    private lateinit var slotSpinner: Spinner

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(AuraService.EXTRA_STATUS) ?: "Unknown"
            updateStatusUi(status)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request runtime permissions
        requestPermissionsIfNeeded()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 60)
            setBackgroundColor(Color.parseColor("#0a0f1d"))
        }

        // Title
        val titleText = TextView(this).apply {
            text = "⚡ AURA DEVICE NODE"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00e5ff"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        rootLayout.addView(titleText)

        // Status Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#151d30"))
        }

        val statusLabel = TextView(this).apply {
            text = "CONNECTION STATUS"
            textSize = 12f
            setTextColor(Color.parseColor("#8892b0"))
        }
        statusCard.addView(statusLabel)

        statusTextView = TextView(this).apply {
            text = AuraService.currentStatusMessage
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (AuraService.isConnected) Color.parseColor("#00ff88") else Color.parseColor("#ff5555"))
            setPadding(0, 10, 0, 0)
        }
        statusCard.addView(statusTextView)
        rootLayout.addView(statusCard)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 30) })

        // Server URL Input Label
        val ipLabel = TextView(this).apply {
            text = "Central Hub WebSocket URL:"
            textSize = 13f
            setTextColor(Color.parseColor("#ccd6f6"))
            setPadding(0, 10, 0, 10)
        }
        rootLayout.addView(ipLabel)

        ipInput = EditText(this).apply {
            hint = "ws://10.246.8.197:3000"
            setText("ws://10.246.8.197:3000")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#555f7d"))
            setBackgroundColor(Color.parseColor("#151d30"))
            setPadding(30, 30, 30, 30)
        }
        rootLayout.addView(ipInput)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })

        // Device Slot Selector
        val slotLabel = TextView(this).apply {
            text = "Device Slot Identity:"
            textSize = 13f
            setTextColor(Color.parseColor("#ccd6f6"))
            setPadding(0, 10, 0, 10)
        }
        rootLayout.addView(slotLabel)

        val slots = arrayOf("phone_1 (First Phone)", "phone_2 (Second Phone)")
        val slotAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, slots)
        slotSpinner = Spinner(this).apply {
            adapter = slotAdapter
            setBackgroundColor(Color.parseColor("#151d30"))
            setPadding(20, 20, 20, 20)
        }
        rootLayout.addView(slotSpinner)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        // Start Service Button
        val btnStart = Button(this).apply {
            text = "▶ START 24/7 BACKGROUND SERVICE"
            setBackgroundColor(Color.parseColor("#00e5ff"))
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 30, 0, 30)
            setOnClickListener {
                val selectedSlot = if (slotSpinner.selectedItemPosition == 1) "phone_2" else "phone_1"
                val serviceIntent = Intent(this@MainActivity, AuraService::class.java).apply {
                    putExtra("SERVER_URL", ipInput.text.toString().trim())
                    putExtra("DEVICE_SLOT", selectedSlot)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this@MainActivity, "Aura Service Started!", Toast.LENGTH_SHORT).show()
                updateStatusUi("Connecting to ${ipInput.text}...")
            }
        }
        rootLayout.addView(btnStart)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })

        // Stop Service Button
        val btnStop = Button(this).apply {
            text = "⏹ STOP SERVICE"
            setBackgroundColor(Color.parseColor("#2a3550"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val serviceIntent = Intent(this@MainActivity, AuraService::class.java)
                stopService(serviceIntent)
                updateStatusUi("Service Stopped")
                Toast.makeText(this@MainActivity, "Aura Service Stopped", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(btnStop)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 30) })

        // Accessibility Permission Button
        val btnAccessibility = Button(this).apply {
            text = "⚙️ ENABLE SCREEN LOCK PERMISSION"
            setBackgroundColor(Color.parseColor("#3b2d54"))
            setTextColor(Color.parseColor("#e0b0ff"))
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Turn ON 'Aura Node' in Accessibility Services", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }
        rootLayout.addView(btnAccessibility)

        setContentView(rootLayout)
    }

    private fun updateStatusUi(status: String) {
        statusTextView.text = status
        if (status.contains("Connected", ignoreCase = true)) {
            statusTextView.setTextColor(Color.parseColor("#00ff88"))
        } else if (status.contains("Connecting", ignoreCase = true)) {
            statusTextView.setTextColor(Color.parseColor("#ffaa00"))
        } else {
            statusTextView.setTextColor(Color.parseColor("#ff5555"))
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, IntentFilter(AuraService.ACTION_STATUS_UPDATE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, IntentFilter(AuraService.ACTION_STATUS_UPDATE))
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
