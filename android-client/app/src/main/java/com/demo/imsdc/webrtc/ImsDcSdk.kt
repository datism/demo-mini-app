package com.demo.imsdc.webrtc

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.ct.ertclib.dc.core.common.NewCallAppSdkInterface
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class ImsDcSdk(
    private val context: Context,
    private val webView: WebView,
    private val webRtcManager: WebRtcManager,
    private val dcManager: DataChannelManager
) {
    companion object {
        private const val TAG = "ImsDcSdk"
    }

    init {
        // Gán reference SDK ngược lại cho DataChannelManager để có thể gọi callback notifyData
        dcManager.imsDcSdk = this
    }

    var onJsRequestedAdc: ((String) -> Unit)? = null

    // Danh sách lưu nhãn kênh dữ liệu (dcLabel) đang hoạt động
    private var activeDcLabel: String? = null
    val activeDcLabels = java.util.concurrent.CopyOnWriteArraySet<String>()
    private val pendingAdcQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    fun getPrimaryDcLabel(): String {
        return synchronized(activeDcLabels) {
            activeDcLabel ?: activeDcLabels.firstOrNull() ?: "control_${dcManager.targetAppId ?: "sketch-sync-app"}_0"
        }
    }

    fun flushPendingAdc() {
        val adc = webRtcManager.foregroundAdcChannel
        if (adc != null && adc.state() == org.webrtc.DataChannel.State.OPEN) {
            while (pendingAdcQueue.isNotEmpty()) {
                val bytes = pendingAdcQueue.poll() ?: break
                val buffer = org.webrtc.DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), true)
                adc.send(buffer)
                Log.i(TAG, "✅ Flushed queued ADC message (${bytes.size} bytes)")
            }
        }
    }

    fun sendOrQueueAdc(bytes: ByteArray) {
        if (bytes.size > MAX_DC_MESSAGE) {
            // Over the negotiated max message size the send fails and WebRTC closes the channel: the page would
            // lose it for the rest of the call (seen with the drawing app's old "clear" carrying every stroke).
            Log.e(TAG, "ADC message too large (${bytes.size} bytes > $MAX_DC_MESSAGE): not sent, the channel would close")
            return
        }
        // On hold the data channels are suspended: the message waits and goes out on resume, in order.
        if (com.demo.imsdc.call.CallHold.active) { dcManager.holdOutgoing { sendOrQueueAdc(bytes) }; return }
        val adc = webRtcManager.foregroundAdcChannel
        if (adc != null && adc.state() == org.webrtc.DataChannel.State.OPEN) {
            val buffer = org.webrtc.DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), true)
            adc.send(buffer)
            Log.i(TAG, "Successfully sent ${bytes.size} bytes over WebRTC ADC (Stream 1000)")
        } else {
            Log.w(TAG, "WebRTC ADC not OPEN (state=${adc?.state()}). Queueing message (${bytes.size} bytes)")
            pendingAdcQueue.add(bytes)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                for (i in 1..25) {
                    kotlinx.coroutines.delay(300)
                    val curAdc = webRtcManager.foregroundAdcChannel
                    if (curAdc != null && curAdc.state() == org.webrtc.DataChannel.State.OPEN) {
                        flushPendingAdc()
                        break
                    }
                }
            }
        }
    }

    inner class JavascriptInterface {
        @android.webkit.JavascriptInterface
        fun call(method: String, args: String): String {
            Log.i(TAG, "← GSMA Bridge call: method=$method, args=$args")
            try {
                val json = JSONObject(args)
                val callback = json.optString("_dscbstub")
                
                val data = json.optJSONObject("data") ?: try {
                    val dataStr = json.optString("data")
                    if (!dataStr.isNullOrEmpty() && dataStr != "null") JSONObject(dataStr) else JSONObject()
                } catch (e: Exception) {
                    JSONObject()
                }
                var function = data.optString("function")
                if (function.isNullOrEmpty()) {
                    function = json.optString("function")
                }
                if (function.isNullOrEmpty()) {
                    function = if (method.startsWith("dc.")) method.substringAfter("dc.") else method
                }
                val params = data.optJSONObject("params") ?: json.optJSONObject("params") ?: json

                Log.d(TAG, "GSMA function: $function, callback: $callback")

                when (function) {
                    "createAppDataChannel" -> {
                        val dcLabels = params.optJSONArray("dcLabels")
                        val dcLabel = if (dcLabels != null && dcLabels.length() > 0) dcLabels.getString(0) else "control_sketch-sync-app_0"
                        val xml = params.optString("DataChannelAppInfoXml")
                        
                        Log.i(TAG, "createAppDataChannel label=$dcLabel, xml=$xml")
                        synchronized(activeDcLabels) {
                            activeDcLabels.clear()
                            activeDcLabel = dcLabel
                            activeDcLabels.add(dcLabel)
                        }
                        
                        val appId = dcLabel.split("_").getOrNull(1) ?: dcManager.targetAppId ?: "sketch-sync-app"
                        dcManager.targetAppId = appId

                        // Rule 11: Natively trigger SIP re-INVITE when JS calls createDataChannel
                        onJsRequestedAdc?.invoke(appId)

                        if (!callback.isNullOrEmpty()) {
                            val resp = org.json.JSONObject().apply {
                                put("code", "0")
                                put("msg", "success")
                            }.toString()
                            sendCallback(callback, resp)
                        }
                        // Do NOT registerObserver or notifyDataChannelState here.
                        // DataChannelManager will handle it and call flushPendingAdc / notifyDataChannelState when OPEN.
                    }
                    "sendData" -> {
                        val dcLabel = params.optString("dcLabel")
                        val base64Data = params.optString("data")
                        Log.d(TAG, "sendData dcLabel=$dcLabel, base64 length=${base64Data.length}")

                        if (!dcLabel.isNullOrEmpty()) {
                            synchronized(activeDcLabels) {
                                activeDcLabel = dcLabel
                                activeDcLabels.add(dcLabel)
                            }
                        }

                        val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
                        sendOrQueueAdc(bytes)

                        if (!callback.isNullOrEmpty()) {
                            val resp = JSONObject().apply {
                                put("code", "0")
                                put("msg", "success")
                            }.toString()
                            sendCallback(callback, resp)
                        }
                    }
                    "closeAppDataChannel" -> {
                        val dcLabel = params.optString("dcLabel")
                        Log.i(TAG, "closeAppDataChannel dcLabel=$dcLabel")
                        if (!dcLabel.isNullOrEmpty()) {
                            synchronized(activeDcLabels) {
                                activeDcLabels.remove(dcLabel)
                                if (activeDcLabel == dcLabel) {
                                    activeDcLabel = activeDcLabels.firstOrNull()
                                }
                            }
                        }

                        if (!callback.isNullOrEmpty()) {
                            val resp = JSONObject().apply {
                                put("code", "0")
                                put("msg", "success")
                            }.toString()
                            sendCallback(callback, resp)
                        }
                    }
                    "getBufferedAmount" -> {
                        if (!callback.isNullOrEmpty()) {
                            val resp = JSONObject().apply {
                                put("code", "0")
                                put("msg", "success")
                                put("data", JSONObject().put("bufferedAmount", 0))
                            }.toString()
                            sendCallback(callback, resp)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling bridge call: ${e.message}", e)
            }
            return "{}"
        }
    }

    fun getJavascriptInterface(): JavascriptInterface {
        return JavascriptInterface()
    }

    fun getWebView(): WebView {
        return webView
    }

    /**
     * Gửi callback trả kết quả về cho hàm JS
     */
    private fun sendCallback(callback: String, resultJson: String) {
        webView.post {
            // DSBridge callback format: window.dscbN(JSON_string)
            // webrtcDC.js does JSON.parse(result) on the callback arg, so we must pass a JSON string
            val escaped = resultJson.replace("\\", "\\\\").replace("\"", "\\\"")
            val script = "javascript:if (window.$callback) { window.$callback(\"$escaped\"); delete window.$callback; }"
            Log.d(TAG, "Evaluating callback script: $script")
            webView.evaluateJavascript(script, null)
        }
    }

    /**
     * Thông báo trạng thái DataChannel (OPEN = 1, CLOSED = 3) về WebView qua dsbridge event dispatching
     */
    fun notifyDataChannelState(dcLabel: String, status: Int) {
        val payload = JSONObject().apply {
            put("dcLabel", dcLabel)
            put("imsDCStatus", status)
        }.toString()

        val argsArray = JSONArray().apply {
            put(payload)
        }.toString()

        val callInfo = JSONObject().apply {
            put("method", "dataChannelNotify")
            put("callbackId", 0)
            put("data", argsArray)
        }.toString()

        webView.post {
            val script = "javascript:window._handleMessageFromNative($callInfo);"
            Log.i(TAG, "→ dataChannelNotify: $script")
            webView.evaluateJavascript(script, null)
        }
    }

    /**
     * Thông báo nhận tin nhắn từ đối phương (ADC data)
     */
    fun notifyMessageReceived(bytes: ByteArray) {
        try {
            val labels = synchronized(activeDcLabels) {
                if (activeDcLabels.isNotEmpty()) {
                    activeDcLabels.toList()
                } else {
                    listOf("control_${dcManager.targetAppId ?: "sketch-sync-app"}_0")
                }
            }
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)

            for (dcLabel in labels) {
                val payload = JSONObject().apply {
                    put("dcLabel", dcLabel)
                    put("message", base64Data)
                }.toString()

                val argsArray = JSONArray().apply {
                    put(payload)
                }.toString()

                val callInfo = JSONObject().apply {
                    put("method", "messageNotify")
                    put("callbackId", 0)
                    put("data", argsArray)
                }.toString()

                webView.post {
                    try {
                        val script = "javascript:window._handleMessageFromNative($callInfo);"
                        Log.d(TAG, "→ messageNotify ($dcLabel): length=${base64Data.length}")
                        webView.evaluateJavascript(script, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed evaluateJavascript in messageNotify: ${e.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in notifyMessageReceived: ${t.message}", t)
        }
    }

    fun startMiniApp(callId: String, appId: String) {
        Log.i(TAG, "startMiniApp callId=$callId, appId=$appId")
        try {
            NewCallAppSdkInterface.startMiniApp(callId, appId, { id, success, reason ->
                Log.i(TAG, "startMiniApp result: appId=$id, success=$success, reason=$reason")
            }, { id, progress ->
                Log.d(TAG, "startMiniApp progress: appId=$id, progress=$progress")
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error starting mini app via SDK: ${e.message}")
        }
    }

    fun handleRemoteSdp(sdp: String, callId: String?) {
        Log.i(TAG, "handleRemoteSdp callId=$callId")
    }

    fun onCallEnded() {
        Log.i(TAG, "onCallEnded - performing JavaScript cleanup")
        val script = "javascript:if (window.IMSDataChannelBridge && typeof window.IMSDataChannelBridge.cleanup === 'function') { window.IMSDataChannelBridge.cleanup(); } if (window.onCallEnded) { window.onCallEnded(); }"
        webView.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed evaluateJavascript in onCallEnded: ${e.message}")
            }
        }
        resetLabels()
    }

    fun resetLabels() {
        Log.i(TAG, "Resetting DataChannel labels")
        synchronized(activeDcLabels) {
            activeDcLabel = null
            activeDcLabels.clear()
        }
    }
}
