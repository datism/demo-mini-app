package com.demo.imsdc.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Foreground-only live GPS. No location service, background permission, storage or ADC here. */
class LiveMapLocationController(
    private val activity: Activity,
    private val webView: WebView,
    private val ownerPage: () -> Boolean,
    private val foregroundAllowed: () -> Boolean
) {
    companion object { const val PERMISSION_REQUEST = 7311 }
    private val handler = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var id: String? = null
    private var url: String? = null
    private var endAt = 0L
    private var listener: LocationListener? = null
    private var tick: Runnable? = null
    private var expiry: Runnable? = null
    private var permissionTimeout: Runnable? = null
    private var permissionInFlight = false
    private var paused = false
    private var lastSentAt = -5_000L
    private var lastSentSample = -1L
    private var gpsFix: Location? = null
    private var networkFix: Location? = null
    private var nativeSeq = 0

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    private fun hasPermission() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun pageValid() = !activity.isFinishing && !activity.isDestroyed && ownerPage() && webView.url == url
    private fun expired() = endAt > 0 && SystemClock.elapsedRealtime() >= endAt

    fun start(payload: String) {
        val input = try { JSONObject(payload) } catch (_: Exception) { return }
        val token = input.optString("id")
        val duration = input.optLong("durationMs", -1)
        if (!token.matches(Regex("[A-Za-z0-9_-]{1,100}")) || duration !in 0L..3_600_000L ||
            !ownerPage() || !foregroundAllowed()) return
        if (id == token) return
        stop()
        id = token; url = webView.url; nativeSeq = 0; paused = false
        endAt = if (duration == 0L) 0L else SystemClock.elapsedRealtime() + duration
        lastSentAt = -5_000L; lastSentSample = -1L
        if (endAt > 0) {
            val task = Runnable { stop("expired") }
            expiry = task; handler.postDelayed(task, duration)
        }
        if (hasPermission()) beginListening() else {
            if (permissionInFlight) { stop("permission_pending"); return }
            permissionInFlight = true
            emit("permission")
            val task = Runnable { stop("permission_timeout") }
            permissionTimeout = task; handler.postDelayed(task, 90_000L)
            try {
                ActivityCompat.requestPermissions(activity, arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
                ), PERMISSION_REQUEST)
            } catch (_: RuntimeException) { permissionInFlight = false; stop("unavailable") }
        }
    }

    fun onPermissionResult(requestCode: Int): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        permissionInFlight = false
        permissionTimeout?.let { handler.removeCallbacks(it) }; permissionTimeout = null
        if (id == null) return true
        if (!pageValid()) { stop(); return true }
        if (!hasPermission()) stop("permission_denied")
        else if (expired()) stop("expired")
        else if (!paused && foregroundAllowed()) beginListening()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun beginListening() {
        if (id == null || listener != null || paused) return
        if (!pageValid()) { stop(); return }
        if (expired()) { stop("expired"); return }
        if (!foregroundAllowed()) { pause(); return }
        if (!hasPermission()) { stop("permission_denied"); return }
        val token = id
        val providers = mutableListOf<String>()
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers += LocationManager.GPS_PROVIDER
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers += LocationManager.NETWORK_PROVIDER
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (id != token || paused) return
                if (!pageValid()) { stop(); return }
                if (!foregroundAllowed()) { pause(); return }
                if (expired()) { stop("expired"); return }
                val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
                if (age !in 0L..30_000_000_000L || !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy <= 0f ||
                    !location.latitude.isFinite() || !location.longitude.isFinite() ||
                    location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
                if (location.provider == LocationManager.GPS_PROVIDER) gpsFix = Location(location)
                else networkFix = Location(location)
                emitLatest()
            }
            override fun onProviderDisabled(provider: String) { if (id == token) emit("waiting", "location_disabled") }
            override fun onProviderEnabled(provider: String) { if (id == token && !paused) emit("active") }
            @Deprecated("Legacy callback required on Android 8-10")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        if (providers.isNotEmpty()) {
            listener = callback
            var registered = false
            for (provider in providers) {
                try { manager.requestLocationUpdates(provider, 1_000L, 0f, callback, Looper.getMainLooper()); registered = true }
                catch (_: SecurityException) {} catch (_: IllegalArgumentException) {}
            }
            if (!registered) { releaseListener(); stop("unavailable"); return }
        }
        emit(if (providers.isEmpty()) "waiting" else "active", if (providers.isEmpty()) "location_disabled" else null)
        scheduleTick()
        Log.i("LiveMapLocation", "listening providers=${providers.joinToString()}")
    }

    private fun scheduleTick() {
        tick?.let { handler.removeCallbacks(it) }
        val token = id
        val task = Runnable {
            tick = null
            if (token == null || id != token || paused) return@Runnable
            if (!pageValid()) { stop(); return@Runnable }
            if (expired()) { stop("expired"); return@Runnable }
            if (!foregroundAllowed()) { pause(); return@Runnable }
            if (!hasPermission()) { stop("permission_denied"); return@Runnable }
            if (listener == null) beginListening() else { emitLatest(); scheduleTick() }
        }
        tick = task; handler.postDelayed(task, 5_000L)
    }

    private fun emitLatest() {
        // Prefer the most accurate sample from the current five-second window.
        val location = listOfNotNull(gpsFix, networkFix).filter {
            val sampleAge = (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L
            sampleAge in 0L..5_000L
        }.minByOrNull { it.accuracy } ?: return
        val now = SystemClock.elapsedRealtime()
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        if (age !in 0L..30_000L || location.elapsedRealtimeNanos <= lastSentSample || now - lastSentAt < 5_000L) return
        if (paused || !foregroundAllowed() || !pageValid() || expired()) return
        lastSentAt = now; lastSentSample = location.elapsedRealtimeNanos
        val fix = JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
            .put("accuracy", location.accuracy.toDouble()).put("timestamp", location.time).put("ageMs", age)
            .put("approximate", !granted(Manifest.permission.ACCESS_FINE_LOCATION))
        emit("fix", fix = fix)
    }

    fun pauseFromJs(token: String) { if (id == token) pause() }
    fun resumeFromJs(token: String) { if (id == token) resume() }
    fun stopFromJs(token: String) { if (id == token) stop() }

    fun pause() {
        if (id == null || paused) return
        paused = true; gpsFix = null; networkFix = null
        tick?.let { handler.removeCallbacks(it) }; tick = null
        releaseListener(); emit("paused")
    }

    fun resume() {
        if (id == null || permissionInFlight || !paused && listener != null) return
        if (!pageValid()) { stop(); return }
        if (expired()) { stop("expired"); return }
        if (!foregroundAllowed()) return
        if (!hasPermission()) { stop("permission_denied"); return }
        paused = false; gpsFix = null; networkFix = null; lastSentAt = -5_000L; lastSentSample = -1L
        beginListening()
    }

    private fun releaseListener() {
        listener?.let { try { manager.removeUpdates(it) } catch (_: SecurityException) {} }
        listener = null
    }

    fun stop(reason: String? = null) {
        if (reason != null && id != null) emit("stopped", reason, terminal = true)
        tick?.let { handler.removeCallbacks(it) }; tick = null
        expiry?.let { handler.removeCallbacks(it) }; expiry = null
        permissionTimeout?.let { handler.removeCallbacks(it) }; permissionTimeout = null
        releaseListener(); id = null; url = null; gpsFix = null; networkFix = null; endAt = 0L; paused = false
    }

    private fun emit(event: String, reason: String? = null, fix: JSONObject? = null, terminal: Boolean = false) {
        val token = id ?: return
        val page = url
        val payload = JSONObject().put("id", token).put("event", event).put("seq", ++nativeSeq)
        if (reason != null) payload.put("reason", reason)
        if (fix != null) payload.put("fix", fix)
        val script = "window.HanoiLiveShare && window.HanoiLiveShare.onNativeResult($payload);"
        webView.post {
            if (!activity.isFinishing && !activity.isDestroyed && ownerPage() && webView.url == page &&
                (id == token || terminal && id == null)) webView.evaluateJavascript(script, null)
        }
    }
}
