package com.tropicalstream.taprescue

import android.content.Context

/** Persisted bits: sound toggle + the all-time high score (ratchets up only). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("taprescue_settings", Context.MODE_PRIVATE)

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(value) { prefs.edit().putBoolean("sound", value).apply() }

    var highScore: Int
        get() = prefs.getInt("high_score", 0)
        set(value) { if (value > highScore) prefs.edit().putInt("high_score", value).apply() }
}
