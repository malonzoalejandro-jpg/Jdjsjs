package com.example.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_PERMISSIONS = 1001

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        if (hasEssentialPermissions()) {
            startWakeWordService()
            statusText.text = "Listening"
        } else {
            statusText.text = "Waiting for microphone permission…"
            requestAllPermissions()
        }

        // Optional but recommended: ask the user to exclude this app from
        // battery optimization so Android doesn't kill the background listener.
        requestIgnoreBatteryOptimizations()
    }

    override fun onResume() {
        super.onResume()
        // Show what's already happened, then keep updating live while visible.
        renderLog(JarvisEventLog.snapshot())
        JarvisEventLog.setListener { lines -> renderLog(lines) }
    }

    override fun onPause() {
        super.onPause()
        JarvisEventLog.setListener(null)
    }

    private fun renderLog(lines: List<String>) {
        logText.text = if (lines.isEmpty()) {
            "Nothing yet — say \"Jarvis\" to get started."
        } else {
            lines.joinToString("\n")
        }
        logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    // Only these gate whether the background listener starts at all.
    private val essentialPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Nice to have — each only affects one specific command (call/text) if
    // denied, so they're requested but never block the service from
    // starting.
    private val optionalPermissions = listOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    private fun hasEssentialPermissions(): Boolean {
        return essentialPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        val all = (essentialPermissions + optionalPermissions).toTypedArray()
        ActivityCompat.requestPermissions(this, all, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (hasEssentialPermissions()) {
                startWakeWordService()
                statusText.text = "Listening"
            } else {
                statusText.text = "Microphone permission needed"
                Toast.makeText(
                    this,
                    "Microphone permission is required for the wake word to work.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startWakeWordService() {
        val serviceIntent = Intent(this, WakeWordService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val packageName = packageName
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                // Some OEMs restrict this intent; safe to ignore if it fails.
            }
        }
    }
}
