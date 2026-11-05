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
import android.webkit.WebView
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** One foreground fix per explicit tap. No Google SDK, cached positions, ADC or background service. */
class MapLocationController(
    private val activity: Activity,
    private val webView: WebView,
    private val ownerAllowed: () -> Boolean
) {
    companion object { const val PERMISSION_REQUEST = 7310 }

    private val handler = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var requestId: String? = null
    private var requestUrl: String? = null
    private var listener: LocationListener? = null
    private var timeout: Runnable? = null
    private var best: Location? = null
    private var latest: Location? = null
    private var fallback: Runnable? = null
    private var receivedSamples = 0
    private var rejectedSamples = 0

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L

    private fun isFresh(location: Location): Boolean {
        val age = ageMs(location)
        return age in 0L..30_000L
    }

    // Prefer accuracy only among fresh fixes; a stale winner must never hide a fresh update.
    private fun bestFresh(): Location? = listOfNotNull(best, latest)
        .filter { isFresh(it) }.minByOrNull { it.accuracy }

    private fun scheduleFallback() {
        if (fallback != null) return
        val candidate = bestFresh() ?: return
        // Allow a short improvement window, but do not let this sample expire while waiting.
        val delayMs = minOf(8_000L, (29_000L - ageMs(candidate)).coerceAtLeast(0L))
        val task = Runnable {
            fallback = null
            if (!validOwner()) { cancel(); return@Runnable }
            bestFresh()?.let { succeed(it) }
            // If everything became stale, continue listening until the original 45s deadline.
        }
        fallback = task
        handler.postDelayed(task, delayMs)
    }
    private var permissionInFlight = false
    private var precise = false

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun allowed() = !activity.isFinishing && !activity.isDestroyed && ownerAllowed()
    private fun validOwner() = allowed() && webView.url == requestUrl

    // Called on Main by CallActivity; JavascriptInterface itself runs on a bridge thread.
    fun request(id: String) {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}")) || !allowed()) return
        cancel()
        requestId = id
        requestUrl = webView.url
        precise = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!precise && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            if (permissionInFlight) { fail("permission_pending"); return }
            permissionInFlight = true
            armTimeout(90_000L) { fail("permission_timeout") }
            try {
                // Android 12+: ask together, and accept approximate access if the user chooses it.
                ActivityCompat.requestPermissions(activity, arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ), PERMISSION_REQUEST)
            } catch (_: RuntimeException) {
                permissionInFlight = false
                fail("unavailable")
            }
        } else startFix()
    }

    fun onPermissionResult(requestCode: Int): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        permissionInFlight = false
        if (requestId == null) return true
        if (!validOwner()) { cancel(); return true }
        precise = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (precise || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) startFix()
        else fail("permission_denied")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startFix() {
        if (!validOwner()) { cancel(); return }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) { fail("permission_denied"); return }
        val providers = mutableListOf<String>()
        if (precise && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers += LocationManager.GPS_PROVIDER
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers += LocationManager.NETWORK_PROVIDER
        Log.i("MapLocation", "start: precise=$precise providers=${providers.joinToString()}")
        if (providers.isEmpty()) { fail("location_disabled"); return }
        val token = requestId
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (token != requestId) return
                if (!validOwner()) { cancel(); return }
                receivedSamples++
                if (!isFresh(location) || !location.hasAccuracy() ||
                    !location.accuracy.isFinite() || location.accuracy <= 0f ||
                    !location.latitude.isFinite() || !location.longitude.isFinite() ||
                    location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) {
                    rejectedSamples++
                    return
                }
                latest = Location(location)
                val previous = best
                if (previous == null || !isFresh(previous) || location.accuracy <= previous.accuracy) best = Location(location)
                Log.d("MapLocation", "fix: provider=${location.provider} accuracyM=${location.accuracy} ageMs=${ageMs(location)}")
                if (!precise || location.accuracy <= 50f) {
                    bestFresh()?.let { succeed(it) }
                } else scheduleFallback()
            }
            override fun onProviderDisabled(provider: String) {
                if (token != requestId) return
                if (providers.none { manager.isProviderEnabled(it) }) {
                    bestFresh()?.let { succeed(it) } ?: fail("location_disabled")
                }
            }
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Legacy callback required on Android 8-10")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listener = callback
        armTimeout(45_000L) {
            if (validOwner()) bestFresh()?.let { succeed(it) } ?: fail("timeout") else cancel()
        }
        var registered = false
        for (provider in providers) {
            try {
                manager.requestLocationUpdates(provider, 1_000L, 0f, callback, Looper.getMainLooper())
                registered = true
            } catch (_: SecurityException) {
                // A permission can be revoked while the call stays alive.
            } catch (_: IllegalArgumentException) {
                // A provider can disappear on devices without that hardware/service.
            }
        }
        if (!registered) fail("unavailable")
    }

    private fun armTimeout(delay: Long, action: () -> Unit) {
        timeout?.let { handler.removeCallbacks(it) }
        val task = Runnable { action() }
        timeout = task
        handler.postDelayed(task, delay)
    }

    private fun succeed(location: Location) {
        if (!validOwner()) { cancel(); return }
        val ageMs = ageMs(location)
        if (ageMs !in 0L..30_000L) { fail("timeout"); return }
        Log.i("MapLocation", "success: accuracyM=${location.accuracy} ageMs=$ageMs received=$receivedSamples rejected=$rejectedSamples")
        finish(JSONObject().put("ok", true)
            .put("latitude", location.latitude).put("longitude", location.longitude)
            .put("accuracy", location.accuracy.toDouble()).put("timestamp", location.time)
            .put("ageMs", ageMs).put("approximate", !precise))
    }

    private fun fail(code: String) {
        Log.i("MapLocation", "failure: code=$code received=$receivedSamples rejected=$rejectedSamples")
        finish(JSONObject().put("ok", false).put("error", code))
    }

    private fun finish(result: JSONObject) {
        val id = requestId ?: return
        val url = requestUrl
        val deliver = validOwner()
        result.put("id", id)
        cancel()
        if (!deliver) return
        // Re-check after posting: navigation and app-switch callbacks may have run meanwhile.
        val script = "window.HanoiMyLocation && window.HanoiMyLocation.onNativeResult($result);"
        webView.post {
            if (allowed() && webView.url == url) webView.evaluateJavascript(script, null)
        }
    }

    fun cancelFromJs(id: String) { if (requestId == id) cancel() }

    fun cancel(reason: String? = null) {
        if (reason != null && requestId != null) { fail(reason); return }
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        fallback?.let { handler.removeCallbacks(it) }
        fallback = null
        listener?.let { try { manager.removeUpdates(it) } catch (_: SecurityException) {} }
        listener = null
        requestId = null
        requestUrl = null
        best = null
        latest = null
        receivedSamples = 0
        rejectedSamples = 0
    }
}
