package com.aura.client

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import android.app.Activity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple UI Layout
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val ipInput = EditText(this).apply {
            hint = "Server URL (e.g. ws://192.168.31.33:3000)"
            setText("ws://192.168.31.33:3000")
        }

        val btnStart = Button(this).apply {
            text = "START AURA BACKGROUND SERVICE"
            setOnClickListener {
                val serviceIntent = Intent(this@MainActivity, AuraService::class.java).apply {
                    putExtra("SERVER_URL", ipInput.text.toString())
                    putExtra("DEVICE_SLOT", "phone_1")
                }
                startService(serviceIntent)
                Toast.makeText(this@MainActivity, "Aura Connected & Running in Background!", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(ipInput)
        layout.addView(btnStart)
        setContentView(layout)
    }
}
