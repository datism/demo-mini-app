package com.demo.imsdc.media

import android.util.Log
import java.net.DatagramSocket

/**
 * Local RTP ports for the current call, shared by the SDP (SipManager) and the sockets (RtpMediaManager).
 *
 * Ports are picked by the OS instead of fixed numbers: the phone's own IMS stack may already hold a
 * fixed port (seen on OPPO: radio UID owned UDP 40008), which left the call with no RTP at all.
 * MF latches on the address packets come from, so any port works.
 */
object RtpPorts {
    private const val TAG = "RtpPorts"

    @Volatile var audio: Int = 0
        private set
    @Volatile var video: Int = 0
        private set

    /** Pick a fresh free audio/video port pair for a new call. */
    @Synchronized
    fun allocate() {
        val a = freePort()
        var v = freePort()
        while (v == a) v = freePort()
        audio = a
        video = v
        Log.i(TAG, "Allocated local RTP ports: audio=$audio, video=$video")
    }

    /** Ports for the current call, allocating them if no call has done so yet. */
    @Synchronized
    fun current(): Pair<Int, Int> {
        if (audio == 0 || video == 0) allocate()
        return audio to video
    }

    /** Audio/video ports of the shared-content pair (a=content:slides); fresh for every share. */
    @Volatile var slidesAudio: Int = 0
        private set
    @Volatile var slidesVideo: Int = 0
        private set

    @Synchronized
    fun allocateSlides(): Pair<Int, Int> {
        var a = freePort(); var v = freePort()
        while (a == audio || a == video) a = freePort()
        while (v == a || v == audio || v == video) v = freePort()
        slidesAudio = a; slidesVideo = v
        Log.i(TAG, "Allocated content-share RTP ports: audio=$a, video=$v")
        return a to v
    }

    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }
}
