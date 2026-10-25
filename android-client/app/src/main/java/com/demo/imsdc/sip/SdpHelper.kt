package com.demo.imsdc.sip

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.net.InetAddress
import java.net.Socket
import java.net.InetSocketAddress
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

object SdpHelper {

    
    fun translateWebRtcOfferToSipOffer(sdp: String, localIpAddress: String, activeReqAppId: String? = null, isCallHoldActive: Boolean = false, localAudioPort: Int = 40002, localVideoPort: Int = 40004): String {
    Log.i("SipManager", "Raw local SDP to translate:\n$sdp")
    val validIp = if (!localIpAddress.isNullOrBlank() && !localIpAddress.contains(":") && localIpAddress != "0.0.0.0" && localIpAddress != "127.0.0.1" && localIpAddress != "::") {
        localIpAddress
    } else {
        val probed = com.demo.imsdc.util.NetworkUtils.getLocalIpAddress(null, null)
        if (probed != "0.0.0.0" && !probed.contains(":")) probed else "127.0.0.1"
    }
    val cleanSdp = sdp.replace("\r", "")
    val lines = cleanSdp.split("\n")
    val newLines = mutableListOf<String>()
    
    // Parse original blocks from WebRTC local offer to extract DTLS/ICE attributes
    data class OrigBlocks(
    val sessionHeader: String,
    val audioBlock: String?,
    val videoBlock: String?,
    val appBlocks: List<String>
    )
    
    val origSessionHeader = StringBuilder()
    var origAudio: StringBuilder? = null
    var origVideo: StringBuilder? = null
    val origApps = mutableListOf<StringBuilder>()
    var currentOrigBlock: StringBuilder? = origSessionHeader
    
    for (line in lines) {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) continue
    if (trimmed.startsWith("m=")) {
    val sb = StringBuilder()
    if (trimmed.startsWith("m=audio ")) {
    origAudio = sb
    currentOrigBlock = origAudio
    } else if (trimmed.startsWith("m=video ")) {
    origVideo = sb
    currentOrigBlock = origVideo
    } else if (trimmed.startsWith("m=application ")) {
    val newApp = sb
    origApps.add(newApp)
    currentOrigBlock = newApp
    } else {
    currentOrigBlock = null
    }
    }
    currentOrigBlock?.append(trimmed)?.append("\n")
    }
    val orig = OrigBlocks(
    origSessionHeader.toString(),
    origAudio?.toString(),
    origVideo?.toString(),
    origApps.map { it.toString() }
    )
    
    // Credentials data structure including setup role
    data class Credentials(val ufrag: String, val pwd: String, val fingerprint: String, val setup: String)
    
    // Helper to extract ICE/DTLS from a block
    fun extractCreds(blockText: String?): Credentials {
    var ufrag = ""
    var pwd = ""
    var fp = ""
    var setup = ""
    blockText?.replace("\r", "")?.split("\n")?.forEach { line ->
    val t = line.trim()
    if (t.startsWith("a=ice-ufrag:")) ufrag = t
    if (t.startsWith("a=ice-pwd:")) pwd = t
    if (t.startsWith("a=fingerprint:")) fp = t
    if (t.startsWith("a=setup:")) setup = t
    }
    return Credentials(ufrag, pwd, fp, setup)
    }
    
    val bdcCreds = extractCreds(orig.appBlocks.getOrNull(0))
    val adcCreds = extractCreds(orig.appBlocks.getOrNull(1))
    
    var bdcPort = 40000
    var bdcCandidate = ""
    orig.appBlocks.getOrNull(0)?.let { block ->
    val mLine = block.split("\n").find { it.startsWith("m=application ") }
    mLine?.split(" ")?.getOrNull(1)?.toIntOrNull()?.let { bdcPort = it }
    val cand = block.split("\n").find { 
        it.startsWith("a=candidate:") && 
        it.contains(" typ host", ignoreCase = true) && 
        it.contains(" udp ", ignoreCase = true) &&
        (it.split(" ").getOrNull(4)?.contains(":") == false) // Prefer IPv4 (no colon)
    } ?: block.split("\n").find { 
        it.startsWith("a=candidate:") && 
        it.contains(" typ host", ignoreCase = true) && 
        it.contains(" udp ", ignoreCase = true) 
    }
    if (cand != null) {
        val tokens = cand.trim().split(" ").toMutableList()
        if (tokens.size >= 8) {
            tokens[4] = validIp
            tokens[5] = bdcPort.toString()
        }
        bdcCandidate = tokens.joinToString(" ")
    }
    }
    
    var adcPort = 40006
    var adcCandidate = ""
    var adcMid = "3"
    orig.appBlocks.getOrNull(1)?.let { block ->
    val mLine = block.split("\n").find { it.startsWith("m=application ") }
    mLine?.split(" ")?.getOrNull(1)?.toIntOrNull()?.let { adcPort = it }
    val cand = block.split("\n").find { 
        it.startsWith("a=candidate:") && 
        it.contains(" typ host", ignoreCase = true) && 
        it.contains(" udp ", ignoreCase = true) &&
        (it.split(" ").getOrNull(4)?.contains(":") == false) // Prefer IPv4 (no colon)
    } ?: block.split("\n").find { 
        it.startsWith("a=candidate:") && 
        it.contains(" typ host", ignoreCase = true) && 
        it.contains(" udp ", ignoreCase = true) 
    }
    if (cand != null) {
        val tokens = cand.trim().split(" ").toMutableList()
        if (tokens.size >= 8) {
            tokens[4] = validIp
            tokens[5] = adcPort.toString()
        }
        adcCandidate = tokens.joinToString(" ")
    }
    val midLine = block.split("\n").find { it.startsWith("a=mid:") }
    if (midLine != null) {
        adcMid = midLine.substringAfter("a=mid:").trim()
    }
    }
    
    // 1.5. Extract only the first SSRC mapping lines for audio and video
    var currentSection = ""
    val audioSsrcs = mutableListOf<String>()
    val videoSsrcs = mutableListOf<String>()
    var firstAudioSsrcId: String? = null
    var firstVideoSsrcId: String? = null
    
    for (line in lines) {
    val trimmed = line.trim()
    if (trimmed.startsWith("m=audio ")) {
    currentSection = "audio"
    } else if (trimmed.startsWith("m=video ")) {
    currentSection = "video"
    } else if (trimmed.startsWith("m=application ")) {
    currentSection = "application"
    } else if (trimmed.startsWith("a=ssrc:")) {
    val parts = trimmed.substringAfter("a=ssrc:").split(" ")
    val ssrcId = parts.firstOrNull()
    if (ssrcId != null) {
    if (currentSection == "audio") {
    if (firstAudioSsrcId == null) {
    firstAudioSsrcId = ssrcId
    }
    if (ssrcId == firstAudioSsrcId) {
    audioSsrcs.add(trimmed)
    }
    } else if (currentSection == "video") {
    if (firstVideoSsrcId == null) {
    firstVideoSsrcId = ssrcId
    }
    if (ssrcId == firstVideoSsrcId) {
    videoSsrcs.add(trimmed)
    }
    }
    }
    }
    }
    
    // 2. Parse top lines (v=, o=, s=, t=)
    for (line in lines) {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) continue
    if (trimmed.startsWith("m=")) break // Stop at media section
    if (trimmed.startsWith("a=group:BUNDLE") || trimmed.startsWith("a=msid-semantic:")) continue
    newLines.add(trimmed)
    }
    val hasADC = activeReqAppId != null
    
    // 3. Synthesize m=audio and m=video sections with candidates and ssrcs
    newLines.add("m=audio $localAudioPort RTP/AVP 8")
    newLines.add("c=IN IP4 $validIp")
    newLines.add("a=rtpmap:8 PCMA/8000")
    newLines.add("a=mid:0")
    newLines.add("a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid")
    newLines.add(if (isCallHoldActive) "a=inactive" else "a=sendrecv")
    audioSsrcs.forEach { newLines.add(it) }
    
    newLines.add("m=video $localVideoPort RTP/AVP 97")
    newLines.add("c=IN IP4 $validIp")
    newLines.add("a=rtpmap:97 H264/90000")
    newLines.add("a=fmtp:97 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f")
    newLines.add("a=mid:1")
    newLines.add("a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid")
    newLines.add(if (isCallHoldActive) "a=inactive" else "a=sendrecv")
    videoSsrcs.forEach { newLines.add(it) }
    
    // 4. Synthesize BDC application section (mid 2)
    newLines.add("m=application $bdcPort UDP/DTLS/SCTP webrtc-datachannel")
    newLines.add("c=IN IP4 $validIp")
    newLines.add("a=mid:2")
    if (bdcCreds.ufrag.isNotEmpty()) newLines.add(bdcCreds.ufrag)
    if (bdcCreds.pwd.isNotEmpty()) newLines.add(bdcCreds.pwd)
    if (bdcCreds.fingerprint.isNotEmpty()) newLines.add(bdcCreds.fingerprint)
    if (bdcCreds.setup.isNotEmpty()) {
    newLines.add(bdcCreds.setup)
    } else {
    newLines.add("a=setup:actpass")
    }
    if (bdcCandidate.isNotEmpty()) newLines.add(bdcCandidate)
    newLines.add("a=tls-id:tlsid${System.currentTimeMillis()}bdc")
    newLines.add("a=dcmap:10 subprotocol=\"http\"")
    newLines.add("a=3gpp-bdc-used-by:sender")
    newLines.add(if (isCallHoldActive) "a=inactive" else "a=sendrecv")
    
    // 5. Synthesize ADC application section (mid 3) if active
    if (hasADC) {
    val appId = activeReqAppId!!
    if (appId == "close" || appId == "operator-menu") {
    newLines.add("m=application 0 UDP/DTLS/SCTP webrtc-datachannel")
    newLines.add("c=IN IP4 127.0.0.1")
    newLines.add("a=mid:$adcMid")
    } else {
    newLines.add("m=application $adcPort UDP/DTLS/SCTP webrtc-datachannel")
    newLines.add("c=IN IP4 $validIp")
    newLines.add("a=mid:$adcMid")
    if (adcCreds.ufrag.isNotEmpty()) newLines.add(adcCreds.ufrag)
    if (adcCreds.pwd.isNotEmpty()) newLines.add(adcCreds.pwd)
    if (adcCreds.fingerprint.isNotEmpty()) newLines.add(adcCreds.fingerprint)
    if (adcCreds.setup.isNotEmpty()) {
    newLines.add(adcCreds.setup)
    } else {
    newLines.add("a=setup:actpass")
    }
    if (adcCandidate.isNotEmpty()) newLines.add(adcCandidate)
    newLines.add("a=tls-id:tlsid${System.currentTimeMillis()}adc")
    newLines.add("a=dcmap:1000 subprotocol=\"$appId\"")
    newLines.add("a=3gpp-req-app:\"$appId\";1000-UE")
    newLines.add(if (isCallHoldActive) "a=inactive" else "a=sendrecv")
    }
    }
    
    val result = newLines.joinToString("\r\n") + "\r\n"
    Log.i("SipManager", "translateWebRtcOfferToSipOffer generated SDP:\n$result")
    return result
    }

    
    fun translateSipAnswerToWebRtcAnswer(sdp: String, localOfferSdp: String? = null): String {
    Log.i("SipManager", "Raw SIP SDP to translate:\n$sdp")
    val cleanSdp = sdp.replace("\r", "")
    val lines = cleanSdp.split("\n")
    val newLines = mutableListOf<String>()
    
    val hasAudio = sdp.contains("m=audio")
    val hasVideo = sdp.contains("m=video")
    
    // 1. Keep top lines (v=, o=, s=, t=)
    for (line in lines) {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) continue
    if (trimmed.startsWith("m=")) break
    if (trimmed.startsWith("a=group:BUNDLE") || trimmed.startsWith("a=msid-semantic:")) continue
    newLines.add(trimmed)
    }
    
    // 2. Extract application section ports and credentials
    var appPort = "40000"
    var remoteIp = "127.0.0.1"
    var iceUfrag = ""
    var icePwd = ""
    var fingerprint = ""
    var setup = ""
    
    data class AppSection(
    var appPort: String = "40000",
    var remoteIp: String = "127.0.0.1",
    var iceUfrag: String = "",
    var icePwd: String = "",
    var fingerprint: String = "",
    var setup: String = "",
    var mid: String = ""
    )
    
    val appSections = mutableListOf<AppSection>()
    var currentApp: AppSection? = null
    var inAppSection = false
    
    for (line in lines) {
    val trimmed = line.trim()
    if (trimmed.startsWith("m=application ")) {
    inAppSection = true
    val parts = trimmed.split(" ")
    val port = if (parts.size >= 2) parts[1] else "40000"
    currentApp = AppSection(appPort = port)
    appSections.add(currentApp)
    } else if (trimmed.startsWith("m=")) {
    inAppSection = false
    currentApp = null
    }
    if (inAppSection && currentApp != null) {
    if (trimmed.startsWith("c=IN IP4 ")) {
    val parts = trimmed.split(" ")
    if (parts.size >= 3) currentApp.remoteIp = parts[2]
    }
    if (trimmed.startsWith("a=ice-ufrag:")) currentApp.iceUfrag = trimmed
    if (trimmed.startsWith("a=ice-pwd:")) currentApp.icePwd = trimmed
    if (trimmed.startsWith("a=fingerprint:")) currentApp.fingerprint = trimmed
    if (trimmed.startsWith("a=setup:")) currentApp.setup = trimmed
    if (trimmed.startsWith("a=mid:")) currentApp.mid = trimmed.substringAfter("a=mid:")
    }
    }
    
    val app1 = if (appSections.isNotEmpty()) appSections[0] else null
    if (app1 != null) {
    val effectiveIp = app1.remoteIp
    val candidateLine = "a=candidate:1 1 udp 2122260991 $effectiveIp ${app1.appPort} typ host generation 0"
    
    newLines.add("m=application ${app1.appPort} UDP/DTLS/SCTP webrtc-datachannel")
    newLines.add("c=IN IP4 $effectiveIp")
    newLines.add("a=mid:0")
    newLines.add("a=sctp-port:5000")
    newLines.add("a=ice-lite")
    if (app1.iceUfrag.isNotEmpty()) newLines.add(app1.iceUfrag)
    if (app1.icePwd.isNotEmpty()) newLines.add(app1.icePwd)
    if (app1.fingerprint.isNotEmpty()) newLines.add(app1.fingerprint)
    if (app1.setup.isNotEmpty()) newLines.add(app1.setup)
    newLines.add(candidateLine)
    
    if (appSections.size > 1) {
    val app2 = appSections[1]
    val effectiveIp2 = app2.remoteIp
    val candidateLine2 = "a=candidate:1 1 udp 2122260991 $effectiveIp2 ${app2.appPort} typ host generation 0"
    
    newLines.add("m=application ${app2.appPort} UDP/DTLS/SCTP webrtc-datachannel")
    newLines.add("c=IN IP4 $effectiveIp2")
    if (app2.mid.isNotEmpty()) {
    newLines.add("a=mid:${app2.mid}")
    } else {
    newLines.add("a=mid:1")
    }
    newLines.add("a=sctp-port:5000")
    newLines.add("a=ice-lite")
    if (app2.iceUfrag.isNotEmpty()) newLines.add(app2.iceUfrag)
    if (app2.icePwd.isNotEmpty()) newLines.add(app2.icePwd)
    if (app2.fingerprint.isNotEmpty()) newLines.add(app2.fingerprint)
    if (app2.setup.isNotEmpty()) newLines.add(app2.setup)
    newLines.add(candidateLine2)
    }
    } else {
    // Pure DataChannel mode (1 m-line, app=mid:0)
    val appPortToUse = appPort
    val remoteIpToUse = remoteIp
    newLines.add("m=application $appPortToUse UDP/DTLS/SCTP webrtc-datachannel")
    newLines.add("c=IN IP4 $remoteIpToUse")
    if (iceUfrag.isNotEmpty()) newLines.add(iceUfrag)
    if (icePwd.isNotEmpty()) newLines.add(icePwd)
    if (fingerprint.isNotEmpty()) newLines.add(fingerprint)
    if (setup.isNotEmpty()) newLines.add(setup)
    newLines.add("a=mid:0")
    newLines.add("a=sctp-port:5000")
    newLines.add("a=candidate:1 1 udp 2122260991 $remoteIpToUse $appPortToUse typ host generation 0")
    }
    
    val result = newLines.joinToString("\r\n") + "\r\n"
    Log.i("SipManager", "translateSipAnswerToWebRtcAnswer generated SDP:\n$result")
    return result
    }

    
    fun parseReqApp(msg: String): String? {
    val sdp = extractSdpBody(msg) ?: return null
    for (line in sdp.split("\n")) {
    val trimmed = line.trim()
    if (trimmed.startsWith("a=3gpp-req-app:", ignoreCase = true)) {
    val valStr = trimmed.substringAfter(":").trim()
    var appId = ""
    if (valStr.contains("\"")) {
    val firstQuote = valStr.indexOf("\"")
    val secondQuote = valStr.indexOf("\"", firstQuote + 1)
    if (secondQuote > firstQuote) {
    appId = valStr.substring(firstQuote + 1, secondQuote).trim()
    }
    }
    if (appId.isEmpty()) {
    val parts = if (valStr.contains(";")) valStr.split(";") else valStr.split(":")
    for (p in parts) {
    val candidate = p.trim().replace("\"", "")
    if (candidate.isNotEmpty() && candidate != "1000" && candidate != "1001" && candidate != "0" && candidate != "10" && !candidate.endsWith("-UE")) {
    appId = candidate
    break
    }
    }
    }
    if (appId.isNotEmpty()) {
    Log.i("SdpHelper", "Parsed 3gpp-req-app appId: $appId from line: $trimmed")
    return appId
    }
    }
    }
    return null
    }



    fun extractSdpBody(msg: String): String? {
        val parts = msg.split("\r\n\r\n", "\n\n")
        return if (parts.size >= 2) parts.drop(1).joinToString("\r\n\r\n") else null
    }
}
