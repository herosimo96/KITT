package com.kitt.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), KittUi {
    private lateinit var scanner: ScannerView
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "K.I.T.T."
            setTextColor(Color.RED)
            textSize = 40f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }

        scanner = ScannerView(this)
        val scannerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 120
        ).apply { topMargin = 60 }

        hint = TextView(this).apply {
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 0)
            text = KittService.lastStatus
        }

        root.addView(title)
        root.addView(scanner, scannerParams)
        root.addView(hint)
        setContentView(root)

        root.setOnClickListener { startKitt(KittService.ACTION_WAKE) }
        root.setOnLongClickListener {
            askKey()
            true
        }

        requestPerms()
    }

    override fun onStart() {
        super.onStart()
        KittService.ui = this
        KittService.appVisible = true
        KittService.visibilityCallback?.invoke()
        hint.text = KittService.lastStatus
    }

    override fun onStop() {
        if (KittService.ui === this) KittService.ui = null
        KittService.appVisible = false
        KittService.visibilityCallback?.invoke()
        super.onStop()
    }

    override fun status(text: String) {
        hint.text = text
    }

    override fun scan(on: Boolean) {
        if (on) scanner.start() else scanner.stop()
    }

    private fun requestPerms() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            afterPermissions()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        afterPermissions()
    }

    private fun afterPermissions() {
        startKitt(null)
        askOverlayOnce()
    }

    private fun askOverlayOnce() {
        val prefs = getSharedPreferences("kitt", MODE_PRIVATE)
        if (!Settings.canDrawOverlays(this) && !prefs.getBoolean("overlay_asked", false)) {
            prefs.edit().putBoolean("overlay_asked", true).apply()
            Toast.makeText(
                this,
                "Attiva 'Mostra sopra altre app' per KITT, poi torna indietro",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        }
    }

    private fun startKitt(action: String?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            hint.text = "Concedi il permesso del microfono a KITT"
            return
        }
        val i = Intent(this, KittService::class.java)
        if (action != null) i.action = action
        ContextCompat.startForegroundService(this, i)
    }

    private fun askKey() {
        val input = EditText(this)
        input.hint = "Incolla qui la chiave Groq"
        AlertDialog.Builder(this)
            .setTitle("Chiave Groq")
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                val k = input.text.toString().trim()
                getSharedPreferences("kitt", MODE_PRIVATE)
                    .edit().putString("groq_key", k).apply()
                Toast.makeText(this, "Chiave salvata", Toast.LENGTH_SHORT).show()
                startKitt(null)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}