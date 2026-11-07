package com.kitt.app

import android.content.Context
import android.graphics.Color

data class ThemePreset(val name: String, val scanner: Int, val bg: Int)

object KittTheme {
    val presets = listOf(
        ThemePreset("KITT Classico", Color.parseColor("#FF1414"), Color.parseColor("#000000")),
        ThemePreset("Knight Blu", Color.parseColor("#1E90FF"), Color.parseColor("#020812")),
        ThemePreset("Verde Matrix", Color.parseColor("#00E676"), Color.parseColor("#000A04")),
        ThemePreset("Ambra Retro", Color.parseColor("#FFB300"), Color.parseColor("#0A0600")),
        ThemePreset("Viola Neon", Color.parseColor("#B388FF"), Color.parseColor("#08020F")),
        ThemePreset("Ghiaccio", Color.parseColor("#E0F7FA"), Color.parseColor("#05080A"))
    )

    private fun p(c: Context) = c.getSharedPreferences("kitt", Context.MODE_PRIVATE)

    fun scanner(c: Context): Int = p(c).getInt("scanner_color", presets[0].scanner)
    fun bg(c: Context): Int = p(c).getInt("bg_color", presets[0].bg)
    fun userName(c: Context): String = p(c).getString("user_name", "Simone") ?: "Simone"
    fun greet(c: Context): Boolean = p(c).getBoolean("greet", true)
    fun speakTyped(c: Context): Boolean = p(c).getBoolean("speak_typed", false)

    fun setScanner(c: Context, v: Int) {
        p(c).edit().putInt("scanner_color", v).apply()
    }

    fun setBg(c: Context, v: Int) {
        p(c).edit().putInt("bg_color", v).apply()
    }

    fun setUserName(c: Context, v: String) {
        p(c).edit().putString("user_name", v).apply()
    }

    fun setGreet(c: Context, v: Boolean) {
        p(c).edit().putBoolean("greet", v).apply()
    }

    fun setSpeakTyped(c: Context, v: Boolean) {
        p(c).edit().putBoolean("speak_typed", v).apply()
    }
}