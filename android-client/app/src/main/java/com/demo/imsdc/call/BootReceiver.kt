package com.demo.imsdc.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** After a reboot, standby comes back if the user was registered and did not log out. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val user = context.getSharedPreferences("ims_dc_prefs", Context.MODE_PRIVATE).getString("username", "") ?: ""
        if (user.isEmpty() || StandbyService.isOff(context)) return
        Log.i("BootReceiver", "Boot completed: starting standby for $user")
        StandbyService.start(context)
    }
}
