package com.demo.imsdc.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** The standby alarm went off: let [StandbyService] REGISTER again (it is in the foreground, so it may be started). */
class StandbyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (StandbyService.isOff(context)) return
        try {
            context.startService(Intent(context, StandbyService::class.java).setAction(StandbyService.ACTION_TICK))
        } catch (e: Exception) {
            // The service was killed and Android refuses to start it from the background: try as foreground.
            Log.w("StandbyAlarm", "Tick start failed (${e.message}), starting standby again")
            StandbyService.start(context)
        }
    }
}
