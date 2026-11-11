package com.demo.imsdc.call

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.demo.imsdc.R
import com.demo.imsdc.sip.SipManager
import com.demo.imsdc.ui.CallActivity
import com.demo.imsdc.ui.CallHistory
import com.demo.imsdc.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "StandbyService"

/**
 * Keeps the phone reachable for incoming calls while the app is closed or the screen is off, like a phone app:
 * a foreground service (permanent low-priority notification) that owns the incoming-call handler, restarts
 * SIP if the process was restarted, and wakes the phone every few minutes to REGISTER again. That keeps the
 * NAT mapping of the SIP TCP connection alive and, through the REGISTER watchdog in [SipManager], reopens the
 * connection when it died while the phone slept.
 */
class StandbyService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!runForeground()) { stopSelf(); return START_NOT_STICKY }
        val tick = intent?.action == ACTION_TICK
        if (tick) wakeFor(15_000)
        val prefs = getSharedPreferences("ims_dc_prefs", Context.MODE_PRIVATE)
        val server = prefs.getString("sip_server", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val sm = SipManager.shared
        when {
            server.isEmpty() || user.isEmpty() -> Log.w(TAG, "No saved account: nothing to keep registered")
            sm == null || !sm.isRunning -> {
                // The process was restarted (killed, reboot): set SIP up again without the app on screen.
                Log.i(TAG, "SIP not running: registering $user on $server")
                scope.launch {
                    try {
                        val fresh = SipManager.create(server, user)
                        SipManager.shared = fresh
                        installIncomingHandler(applicationContext, fresh)
                        fresh.start(applicationContext)
                        fresh.register(fresh.getLocalIp())
                    } catch (e: Exception) {
                        Log.w(TAG, "Standby registration failed: ${e.message}")
                    }
                }
            }
            else -> {
                if (sm.onIncomingInvite == null) installIncomingHandler(applicationContext, sm)
                // During a call the call keeps the connection busy; a REGISTER that times out there would reconnect mid-call.
                if (tick && !CallActivity.isCallActive) sm.ensureConnected("standby tick")
            }
        }
        scheduleTick(this)
        return START_STICKY
    }

    private fun runForeground(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot run standby in the foreground: ${e.message}")
        false
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Chế độ chờ cuộc gọi", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            })
        }
        val user = getSharedPreferences("ims_dc_prefs", Context.MODE_PRIVATE).getString("username", "") ?: ""
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_incoming)
            .setContentTitle("5G New Calling")
            .setContentText("Sẵn sàng nhận cuộc gọi" + if (user.isNotEmpty()) " ($user)" else "")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun wakeFor(ms: Long) {
        try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "imsdc:standby").acquire(ms)
        } catch (e: Exception) { Log.w(TAG, "Wake lock: ${e.message}") }
    }

    companion object {
        const val ACTION_TICK = "com.demo.imsdc.standby.TICK"
        private const val CHANNEL_ID = "standby"
        private const val NOTIFICATION_ID = 7301
        /** How often the phone wakes to REGISTER again. Doze may stretch it (allow-while-idle alarms: ~9 min). */
        private const val TICK_MS = 4 * 60 * 1000L

        /** Starts standby. Call while the app is on screen (Android refuses a foreground service started from the background). */
        fun start(context: Context) {
            context.getSharedPreferences("ims_dc_prefs", Context.MODE_PRIVATE).edit().putBoolean("standby_off", false).apply()
            try {
                ContextCompat.startForegroundService(context, Intent(context, StandbyService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start standby: ${e.message}")
            }
        }

        /** Stops standby (logout): no more ticks, and it does not come back at boot. */
        fun stop(context: Context) {
            context.getSharedPreferences("ims_dc_prefs", Context.MODE_PRIVATE).edit().putBoolean("standby_off", true).apply()
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(tickIntent(context))
            context.stopService(Intent(context, StandbyService::class.java))
        }

        fun isOff(context: Context) =
            context.getSharedPreferences("ims_dc_prefs", Context.MODE_PRIVATE).getBoolean("standby_off", false)

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, StandbyAlarmReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun scheduleTick(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + TICK_MS, tickIntent(context))
        }

        /**
         * Rings for an incoming call whether or not the app's screens exist: the call screen comes up through the
         * full-screen incoming-call notification. Also handed back by MainActivity when it is destroyed.
         */
        fun installIncomingHandler(context: Context, sm: SipManager) {
            val app = context.applicationContext
            sm.onIncomingInvite = { fromUser, sdpBody, callId, rawMsg, rawSdp ->
                try {
                    (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "imsdc:incoming").acquire(30_000)
                } catch (_: Exception) {}
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Log.i(TAG, "Incoming call from $fromUser while the app is in the background")
                    CallHistory.save(app, fromUser, isIncoming = true)
                    val prefs = app.getSharedPreferences("ims_dc_prefs", Context.MODE_PRIVATE)
                    CallActivity.launchIncoming(
                        context = app,
                        sipServer = prefs.getString("sip_server", "") ?: "",
                        localUser = prefs.getString("username", "") ?: "",
                        remoteUser = fromUser,
                        localIp = sm.getLocalIp(),
                        localPort = sm.getLocalPort(),
                        sipPort = 5060,
                        incomingInvite = rawMsg,
                        incomingSdp = sdpBody,
                        incomingCallId = callId,
                        incomingRawSdp = rawSdp
                    )
                }
            }
        }
    }
}
