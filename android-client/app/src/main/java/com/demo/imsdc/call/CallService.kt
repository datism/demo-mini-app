package com.demo.imsdc.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.demo.imsdc.R
import com.demo.imsdc.ui.CallActivity

private const val TAG = "CallService"

/**
 * Keeps an established call running while the user is on the home screen or in another app:
 * a foreground service of type microphone (Android cuts the mic of a background app otherwise),
 * plus a Wi-Fi lock and a partial wake lock so RTP and the data channels keep flowing.
 *
 * During a screen share the same service also carries the mediaProjection type, which Android 14
 * requires before MediaProjection can be obtained.
 */
class CallService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_END -> { onUserAction?.invoke(ACTION_END); return START_NOT_STICKY }
            ACTION_STOP_SHARE -> { onUserAction?.invoke(ACTION_STOP_SHARE); return START_NOT_STICKY }
            ACTION_DECLINE_REQUEST -> { onUserAction?.invoke(ACTION_DECLINE_REQUEST); return START_NOT_STICKY }
            ACTION_UNSHARE -> {
                // The call already ended (the share is torn down after CallService.stop): this intent started a
                // fresh, non-foreground service. Re-posting would leave "Đang gọi · Với …" up for good.
                if (!active) {
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Only the notification changes: calling startForeground again from the background (the share
                // can end while the user is in another app) may be refused, and the extra type does no harm.
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(lastPeer, false))
                return START_NOT_STICKY
            }
        }
        val peer = intent?.getStringExtra(EXTRA_PEER) ?: lastPeer
        val sharing = intent?.getBooleanExtra(EXTRA_SHARING, false) ?: false
        val camera = intent?.takeIf { it.hasExtra(EXTRA_CAMERA) }?.getBooleanExtra(EXTRA_CAMERA, false) ?: lastCamera
        lastPeer = peer
        lastCamera = camera
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (sharing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // The camera keeps sending while the user is in another app only from a camera-type service, which
        // Android grants only while the app is in the foreground; refused, the call runs without it.
        val cameraType = if (camera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        fun runForeground(t: Int): Boolean = try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(peer, sharing), t)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot run in the foreground (type=$t, sharing=$sharing): ${e.message}", e)
            false
        }
        val withCamera = cameraType != 0 && runForeground(type or cameraType)
        val ok = withCamera || runForeground(type)
        cameraInBackground = withCamera
        if (sharing) { sharingReady?.complete(ok); sharingReady = null }
        if (!ok && !sharing) { stopSelf(); return START_NOT_STICKY }
        if (ok) active = true
        acquireLocks()
        return START_NOT_STICKY
    }

    private fun notification(peer: String, sharing: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Cuộc gọi đang diễn ra", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, CallActivity::class.java).putExtra(CallActivity.EXTRA_FROM_ONGOING, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, CallService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_incoming)
            .setContentTitle(if (sharing) "Đang chia sẻ màn hình" else "Đang gọi")
            .setContentText(if (peer.isNotEmpty()) "Với $peer" else "Chạm để quay lại cuộc gọi")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (sharing) b.addAction(0, "Dừng chia sẻ", action(ACTION_STOP_SHARE, 2))
        b.addAction(0, "Kết thúc", action(ACTION_END, 1))
        return b.build()
    }

    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "imsdc:call").apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "imsdc:call").apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        }
    }

    override fun onDestroy() {
        active = false
        cameraInBackground = false
        try { wifiLock?.release() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        wifiLock = null; wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_END = "com.demo.imsdc.call.END"
        const val ACTION_STOP_SHARE = "com.demo.imsdc.call.STOP_SHARE"
        /** "Từ chối" on a request notification (the other phone opens an app or shares). */
        const val ACTION_DECLINE_REQUEST = "com.demo.imsdc.call.DECLINE_REQUEST"
        private const val ACTION_UNSHARE = "com.demo.imsdc.call.UNSHARE"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_SHARING = "sharing"
        private const val EXTRA_CAMERA = "camera"
        private var lastCamera = false

        /** The service holds the camera type: the camera may keep capturing while the call screen is hidden. */
        @Volatile var cameraInBackground = false
            private set
        private const val CHANNEL_ID = "ongoing_call"
        private const val NOTIFICATION_ID = 4201
        private var lastPeer = ""

        /** The call's foreground service is running (between start and stop). */
        @Volatile private var active = false

        /** Set by CallActivity: runs the notification's buttons (ACTION_END / ACTION_STOP_SHARE). */
        @Volatile var onUserAction: ((String) -> Unit)? = null

        /** Call connected (app in the foreground): keep it running in the background. */
        fun start(context: Context, peer: String, camera: Boolean) = update(context, peer, sharing = false, camera = camera)

        @Volatile private var sharingReady: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

        /**
         * Screen share on: adds the mediaProjection type and the "Dừng chia sẻ" button. Returns once the service
         * runs with that type (Android 14 refuses getMediaProjection before), false if it could not.
         */
        suspend fun startSharing(context: Context): Boolean {
            val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
            sharingReady = ready
            update(context, null, sharing = true)
            return kotlinx.coroutines.withTimeoutOrNull(3000) { ready.await() } ?: false
        }

        /** Screen share off: back to the plain call notification. */
        fun stopSharing(context: Context) {
            if (!active) return // call over: nothing to switch back to
            try { context.startService(Intent(context, CallService::class.java).setAction(ACTION_UNSHARE)) } catch (e: Exception) {
                Log.w(TAG, "Cannot update the call notification: ${e.message}")
            }
        }

        private fun update(context: Context, peer: String?, sharing: Boolean, camera: Boolean? = null) {
            val i = Intent(context, CallService::class.java).putExtra(EXTRA_SHARING, sharing)
            if (camera != null) i.putExtra(EXTRA_CAMERA, camera)
            if (peer != null) i.putExtra(EXTRA_PEER, peer)
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot start the call service: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            onUserAction = null
            active = false
            context.stopService(Intent(context, CallService::class.java))
            // Also when the notification was last re-posted by notify() (ACTION_UNSHARE) rather than startForeground.
            try { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) } catch (_: Exception) {}
        }
    }
}
