package com.kitt.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var scanner: ScannerView
    private var active = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        val hint = TextView(this).apply {
            text = "Tocca lo schermo per testare lo scanner"
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 0)
        }

        root.addView(title)
        root.addView(scanner, scannerParams)
        root.addView(hint)
        setContentView(root)

        root.setOnClickListener {
            active = !active
            if (active) scanner.start() else scanner.stop()
        }
    }
}