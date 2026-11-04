package com.demo.imsdc.notification

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.demo.imsdc.R
import com.demo.imsdc.sip.SipManager
import com.demo.imsdc.ui.CallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "IncomingCallNotif"
private const val CHANNEL_ID = "incoming_calls"
private const val NOTIFICATION_ID = 2001
private const val ACTION_DECLINE_CALL = "com.demo.imsdc.ACTION_DECLINE_NOTIFICATION_CALL"

/**
 * IncomingCallNotificationManager — Quản lý hiển thị thông báo cuộc gọi đến mức ưu tiên cao nhất,
 * đánh thức màn hình (WakeLock), phát chuông và rung độc lập với vòng đời Activity.
 */
object IncomingCallNotificationManager {

    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var isRinging = false
    private var declineReceiver: BroadcastReceiver? = null
    private var pendingInviteRaw: String? = null
    /** The ringing call's notification, built but posted only while the app is in the background. */
    private var pendingNotification: android.app.Notification? = null

    /** A screen of this app is visible (not the home screen, another app, or the screen off / locked). */
    private fun appInForeground(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** The call screen went away while ringing (Home, another app): show the call as a notification. */
    @Synchronized
    fun onCallScreenHidden(context: Context) {
        val n = pendingNotification ?: return
        try {
            (context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
            Log.i(TAG, "Call screen hidden while ringing: incoming-call notification posted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification: ${e.message}")
        }
    }

    /** The call screen is back while ringing: it shows the call, so the notification goes (the ringtone stays). */
    @Synchronized
    fun onCallScreenShown(context: Context) {
        if (pendingNotification == null) return
        try {
            (context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    /**
     * Khởi tạo NotificationChannel cho cuộc gọi đến
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cuộc gọi đến",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo cuộc gọi thoại & video IMS DC đến"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                setSound(ringtoneUri, audioAttributes)
                setBypassDnd(true)
            }
            nm.createNotificationChannel(channel)
            Log.i(TAG, "Notification channel '$CHANNEL_ID' created/ensured")
        }
    }

    /**
     * Hiển thị thông báo cuộc gọi đến (Full Screen Intent) + Bật sáng màn hình + Đổ chuông
     */
    @Synchronized
    fun show(
        context: Context,
        sipServer: String,
        localUser: String,
        remoteUser: String,
        localIp: String,
        localPort: Int,
        sipPort: Int,
        incomingInvite: String,
        incomingSdp: String,
        incomingCallId: String,
        incomingRawSdp: String?
    ) {
        Log.i(TAG, "🔔 show() incoming call from $remoteUser (callId=$incomingCallId)")
        val appContext = context.applicationContext
        createNotificationChannel(appContext)

        pendingInviteRaw = incomingInvite

        // 1. Đánh thức màn hình tức thì (kể cả khi màn hình đang tắt / khóa)
        acquireWakeLock(appContext)

        // 2. Phát chuông & rung
        startRingtoneAndVibration(appContext)

        // 3. Tạo Full-Screen Intent trỏ tới CallActivity (hiển thị giao diện cuộc gọi đến)
        val callIntent = Intent(appContext, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CallActivity.EXTRA_SIP_SERVER, sipServer)
            putExtra(CallActivity.EXTRA_LOCAL_USER, localUser)
            putExtra(CallActivity.EXTRA_REMOTE_USER, remoteUser)
            putExtra(CallActivity.EXTRA_LOCAL_IP, localIp)
            putExtra(CallActivity.EXTRA_LOCAL_PORT, localPort)
            putExtra(CallActivity.EXTRA_SIP_PORT, sipPort)
            putExtra(CallActivity.EXTRA_INCOMING_INVITE, incomingInvite)
            putExtra(CallActivity.EXTRA_INCOMING_SDP, incomingSdp)
            putExtra(CallActivity.EXTRA_INCOMING_CALL_ID, incomingCallId)
            putExtra(CallActivity.EXTRA_INCOMING_RAW_SDP, incomingRawSdp)
            putExtra(CallActivity.EXTRA_AUTO_ANSWER, false)
        }

        val activityOptionsBundle: Bundle? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
        } else {
            null
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            activityOptionsBundle
        )

        // 4. Tạo PendingIntent cho nút "Trả lời" (tự động nhận cuộc gọi ngay)
        val answerIntent = Intent(appContext, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CallActivity.EXTRA_SIP_SERVER, sipServer)
            putExtra(CallActivity.EXTRA_LOCAL_USER, localUser)
            putExtra(CallActivity.EXTRA_REMOTE_USER, remoteUser)
            putExtra(CallActivity.EXTRA_LOCAL_IP, localIp)
            putExtra(CallActivity.EXTRA_LOCAL_PORT, localPort)
            putExtra(CallActivity.EXTRA_SIP_PORT, sipPort)
            putExtra(CallActivity.EXTRA_INCOMING_INVITE, incomingInvite)
            putExtra(CallActivity.EXTRA_INCOMING_SDP, incomingSdp)
            putExtra(CallActivity.EXTRA_INCOMING_CALL_ID, incomingCallId)
            putExtra(CallActivity.EXTRA_INCOMING_RAW_SDP, incomingRawSdp)
            putExtra(CallActivity.EXTRA_AUTO_ANSWER, true)
        }
        val answerPendingIntent = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID + 2,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            activityOptionsBundle
        )

        // 5. Đăng ký BroadcastReceiver để xử lý nút "Từ chối" / "Trả lời" trên Notification
        registerCallActionReceiver(appContext)
        lastAnswerIntent = answerIntent
        lastAnswerPendingIntent = answerPendingIntent

        // 6. Tạo PendingIntent cho nút "Từ chối"
        val declineIntent = Intent(ACTION_DECLINE_CALL).apply {
            setPackage(appContext.packageName)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            appContext,
            NOTIFICATION_ID + 1,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // 7. Build Notification
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_incoming)
            .setContentTitle("Cuộc gọi đến từ ${com.demo.imsdc.ui.Phonebook.label(appContext, remoteUser)}")
            .setContentText("IMS Data Channel • Đang đổ chuông...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(R.drawable.ic_phone_incoming, "Trả lời", answerPendingIntent)
            .addAction(R.drawable.ic_phone_decline, "Từ chối", declinePendingIntent)
            .build()

        pendingNotification = notification
        // The app on screen opens the call screen itself (below): no heads-up over it.
        if (appInForeground()) {
            Log.i(TAG, "App in the foreground: incoming call shown in the app, no notification")
        } else {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "✅ Full-Screen Notification posted for incoming call")
        }

        // 7. Thử khởi chạy trực tiếp Activity nếu hệ điều hành cho phép
        try {
            appContext.startActivity(callIntent)
            Log.i(TAG, "Direct startActivity() invoked with NEW_TASK")
        } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity from background constrained: ${e.message} (Full-Screen Intent will handle it)")
        }
    }

    /**
     * Dừng chuông, tắt rung, hủy notification và giải phóng WakeLock
     */
    @Synchronized
    fun dismiss(context: Context?) {
        if (context == null) return
        val appContext = context.applicationContext
        Log.i(TAG, "🔕 dismiss() called — canceling notification and releasing resources")

        // Hủy Notification
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel notification: ${e.message}")
        }

        // Tắt chuông
        stopRingtoneAndVibration(appContext)

        // Giải phóng WakeLock
        releaseWakeLock()

        // Hủy đăng ký receiver
        unregisterCallActionReceiver(appContext)
        lastAnswerIntent = null
        lastAnswerPendingIntent = null

        pendingInviteRaw = null
        pendingNotification = null
    }

    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "imsdc:incoming_call_wake"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(45_000L) // 45 giây khớp ringing timeout
                Log.i(TAG, "💡 WakeLock acquired (45s timeout, SCREEN_BRIGHT_WAKE_LOCK)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "💡 WakeLock released")
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock: ${e.message}")
        }
    }

    private fun startRingtoneAndVibration(context: Context) {
        if (isRinging) return
        isRinging = true

        // Ringtone
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
            }
            ringtone?.play()
            Log.i(TAG, "🎶 Ringtone playing")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringtone: ${e.message}")
        }

        // Vibration
        try {
            val pattern = longArrayOf(0, 1000, 1000, 1000, 1000, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vibration: ${e.message}")
        }
    }

    private fun stopRingtoneAndVibration(context: Context) {
        isRinging = false
        try {
            ringtone?.stop()
            ringtone = null
            Log.i(TAG, "🎶 Ringtone stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop ringtone: ${e.message}")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop vibration: ${e.message}")
        }
    }

    private var lastAnswerIntent: Intent? = null
    private var lastAnswerPendingIntent: PendingIntent? = null

    private fun registerCallActionReceiver(context: Context) {
        if (declineReceiver != null) return
        declineReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_DECLINE_CALL -> {
                        Log.i(TAG, "🚫 Decline action clicked from notification")
                        val rawInvite = pendingInviteRaw
                        val sm = SipManager.shared
                        if (rawInvite != null && sm != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    sm.sendDecline(rawInvite)
                                    Log.i(TAG, "Sent SIP Decline via SipManager")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to send SIP Decline: ${e.message}")
                                }
                            }
                        }
                        dismiss(c)
                    }
                    "com.demo.imsdc.ANSWER" -> {
                        Log.i(TAG, "📞 Answer action received via broadcast — launching CallActivity via PendingIntent")
                        val options: Bundle? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ActivityOptions.makeBasic().apply {
                                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                            }.toBundle()
                        } else {
                            null
                        }
                        try {
                            if (lastAnswerPendingIntent != null && c != null) {
                                lastAnswerPendingIntent?.send(c, 0, null, null, null, null, options)
                            } else {
                                lastAnswerIntent?.let { c?.startActivity(it, options) }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send answer pending intent: ${e.message}")
                            lastAnswerIntent?.let { c?.startActivity(it) }
                        }
                        dismiss(c)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_DECLINE_CALL)
            addAction("com.demo.imsdc.ANSWER")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(declineReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(declineReceiver, filter)
        }
    }

    private fun unregisterCallActionReceiver(context: Context) {
        if (declineReceiver != null) {
            try {
                context.unregisterReceiver(declineReceiver)
            } catch (_: Exception) {}
            declineReceiver = null
        }
    }
}
