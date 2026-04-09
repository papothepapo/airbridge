package com.mintlabs.airpodsnative9.prefs

import android.content.Context
import com.mintlabs.airpodsnative9.aap.DeviceProfileOverride

object AppSettings {
    private const val PREFS_NAME = "airpods_native9"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_AUTO_PAUSE = "auto_pause"
    private const val KEY_AUTO_RESUME = "auto_resume"
    private const val KEY_REQUIRE_CONNECTED = "require_connected"
    private const val KEY_PAUSE_ON_SINGLE = "pause_on_single"
    private const val KEY_LAST_CLASSIC_ADDRESS = "last_classic_address"
    private const val KEY_DEVICE_PROFILE_OVERRIDE = "device_profile_override"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED, true)

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun isAutoPauseEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_PAUSE, true)

    fun setAutoPauseEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_PAUSE, enabled).apply()
    }

    fun isAutoResumeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RESUME, true)

    fun setAutoResumeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_RESUME, enabled).apply()
    }

    fun isPauseOnSingleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PAUSE_ON_SINGLE, false)

    fun setPauseOnSingleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSE_ON_SINGLE, enabled).apply()
    }

    fun isRequireConnectedEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUIRE_CONNECTED, true)

    fun setRequireConnectedEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_CONNECTED, enabled).apply()
    }

    fun getLastClassicAddress(context: Context): String? =
        prefs(context).getString(KEY_LAST_CLASSIC_ADDRESS, null)

    fun setLastClassicAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_LAST_CLASSIC_ADDRESS, address).apply()
    }

    fun getDeviceProfileOverride(context: Context): DeviceProfileOverride {
        val raw = prefs(context).getString(KEY_DEVICE_PROFILE_OVERRIDE, DeviceProfileOverride.AUTO.name)
        return enumValues<DeviceProfileOverride>().firstOrNull { it.name == raw } ?: DeviceProfileOverride.AUTO
    }

    fun setDeviceProfileOverride(context: Context, value: DeviceProfileOverride) {
        prefs(context).edit().putString(KEY_DEVICE_PROFILE_OVERRIDE, value.name).apply()
    }
}
