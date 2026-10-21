package net.littlesavage.homevoice

import android.content.Context
import java.util.UUID

/** Plain SharedPreferences; the token is a device shared secret, not a user credential. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("home_voice", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("serverUrl", "") ?: ""
        set(v) = prefs.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v.trim()).apply()

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: ("tablet-" + UUID.randomUUID().toString().take(8)).also {
            prefs.edit().putString("deviceId", it).apply()
        }

    var wakeThreshold: Float
        get() = prefs.getFloat("wakeThreshold", 0.5f)
        set(v) = prefs.edit().putFloat("wakeThreshold", v).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    val isConfigured get() = serverUrl.isNotBlank() && token.isNotBlank()
}
