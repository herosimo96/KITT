package com.kitt.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED &&
            a != "android.intent.action.QUICKBOOT_POWERON" &&
            a != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return
        try {
            val s = Intent(context, KittService::class.java)
            s.action = KittService.ACTION_BOOT
            ContextCompat.startForegroundService(context, s)
        } catch (e: Exception) {
        }
    }
}