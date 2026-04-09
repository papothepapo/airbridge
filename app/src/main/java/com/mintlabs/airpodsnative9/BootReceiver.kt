package com.mintlabs.airpodsnative9

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mintlabs.airpodsnative9.prefs.AppSettings
import com.mintlabs.airpodsnative9.service.AirPodsForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppSettings.isServiceEnabled(context)) return
        AirPodsForegroundService.start(context)
    }
}
