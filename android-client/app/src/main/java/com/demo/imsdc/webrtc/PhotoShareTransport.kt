package com.demo.imsdc.webrtc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.DataChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * PhotoShareTransport — Vận chuyển ảnh qua ADC stream 1000 (chế độ P2P relay qua MF).
 *
 * Wire protocol (xem docs photo-share):
 *   • JSON control: byte đầu = '0x7B' ('{') — đi qua sendOrQueueAdc như mọi app khác.
 *   • Binary DATA_CHUNK: byte đầu = 0x01, theo cấu trúc:
 *
 *       offset  size  field
 *       0       1     0x01 (FRAME_DATA_CHUNK)
 *       1       2     photoIdHash u16 BE
 *       3       4     seq u32 BE          (bắt đầu từ 0)
 *       7       2     totalChunks u16 BE
 *       9       2     chunkLen u16 BE
 *       11      N     payload (N = chunkLen, tối đa CHUNK_PAYLOAD)
 *
 *   SCTP DataChannel mặc định reliable+ordered ⇒ không cần retransmit ở tầng app;
 *   sha256 trong msg "begin" là lớp đối chiếu toàn vẹn duy nhất (phát hiện lần cuối).
 *
 * Flow control: không sleep cứng — gate theo bufferedAmount của channel
 * (HIGH_WATER_MARK) để không làm phình SCTP send buffer khi truyền ảnh lớn.
 */
class PhotoShareTransport(
    private val webRtcManager: WebRtcManager,
    private val scope: CoroutineScope,
    private val imsDcSdk: ImsDcSdk?
) {

    /** WebView target — set từ CallActivity sau khi binding sẵn sàng. */
    var webView: android.webkit.WebView? = null

    companion object {
        private const val TAG = "PhotoShareTransport"

        /** Định danh frame binary DATA_CHUNK (JSON luôn bắt đầu bằng '{' = 0x7B). */
        const val FRAME_DATA_CHUNK: Int = 0x01

        /** Payload tối đa mỗi chunk — khớp giá trị đã chứng minh ổn định trên S24 (sendChunked của MF). */
        const val CHUNK_PAYLOAD: Int = 16000

        /** Header 11 byte: type(1) + idHash(2) + seq(4) + total(2) + len(2). */
        const val CHUNK_HEADER: Int = 11

        /** Ngưỡng ngưng gửi khi SCTP buffer còn treo nhiều data (đơn vị byte). */
        const val HIGH_WATER_MARK: Int = 512 * 1024

        /** Nhịp nghỉ tối thiểu giữa 2 chunk liên tiếp (ms) — chống burst làm ngợm pacer nội bộ libwebrtc. */
        const val CHUNK_PACE_MS: Long = 3

        /** Cạnh dài tối đa của ảnh full-resolution sau khi transcode. */
        const val MAX_DIMEN: Int = 1920

        /** Cạnh dài tối đa của thumbnail. */
        const val THUMB_DIMEN: Int = 256

        /** Chất lượng JPEG. */
        const val JPEG_QUALITY: Int = 80
        const val THUMB_QUALITY: Int = 70

        /** Thư mục ảnh trong cacheDir — ĐẶT NGOÀI miniapps/ để không bị delete khi cài ZIP mới. */
        const val STORE_DIR: String = "photostore"
    }

    // ─── Trạng thái gửi ─────────────────────────────────────────────────────
    /** Hàng đợi gửi tuần tự (một ảnh tại một thời điểm tránh xen kẽ chunk). */
    private val sendQueue = Channel<SendJob>(Channel.UNLIMITED)
    private var sendWorkerStarted = false

    private class SendJob(
        val photoId: String,
        val filePath: File,
        val totalChunks: Int,
        val onDone: ((Boolean) -> Unit)? = null
    )

    // ─── Trạng thái nhận ────────────────────────────────────────────────────
    /** Buffer ghép chunks của 1 ảnh. parts cấp đủ ngay khi tạo (đã biết total). */
    private class AssmBuffer(val totalChunks: Int) {
        val parts: Array<ByteArray?> = arrayOfNulls(totalChunks)
        var received: Int = 0
    }

    private val rxBuffers = ConcurrentHashMap<Int, AssmBuffer>()

    // ═══════════════════ PHÍA GỬI ═══════════════════

    /**
     * Đưa ảnh vào hàng đợi stream. Trả về totalChunks (đã tính sẵn) để caller
     * có thể bắn msg JSON "begin" trước/sau khi gọi hàm này.
     */
    fun enqueueFile(context: Context, photoId: String, onDone: ((Boolean) -> Unit)? = null): Int? =
        enqueueMediaFile(context, photoId, isVideo = false, onDone = onDone)

    fun enqueueVideoFile(context: Context, mediaId: String, onDone: ((Boolean) -> Unit)? = null): Int? =
        enqueueMediaFile(context, mediaId, isVideo = true, onDone = onDone)

    private fun enqueueMediaFile(
        context: Context,
        mediaId: String,
        isVideo: Boolean,
        onDone: ((Boolean) -> Unit)?
    ): Int? {
        val file = fileForMedia(context, mediaId, isVideo)
        if (!file.exists()) {
            Log.w(TAG, "enqueueFile: not found ${file.absolutePath}")
            return null
        }
        val total = ((file.length() + CHUNK_PAYLOAD - 1) / CHUNK_PAYLOAD).toInt()
        if (total < 1 || total > 0xFFFF) {
            Log.w(TAG, "enqueueFile: invalid chunk count $total for ${file.length()} bytes")
            return null
        }
        sendQueue.trySend(SendJob(mediaId, file, total, onDone))
        ensureSendWorker()
        return total
    }

    private fun ensureSendWorker() {
        if (sendWorkerStarted) return
        synchronized(this) {
            if (sendWorkerStarted) return
            sendWorkerStarted = true
            scope.launch(Dispatchers.IO) {
                for (job in sendQueue) {
                    runCatching { streamFile(job) }
                        .onFailure { Log.e(TAG, "streamFile failed for ${job.photoId}: ${it.message}", it) }
                }
            }
        }
    }

    /** Stream 1 file theo chunk, gate theo bufferedAmount. */
    private suspend fun streamFile(job: SendJob) {
        val adc = awaitOpenChannel() ?: run {
            job.onDone?.invoke(false); return
        }
        val idHash = idHashOf(job.photoId)
        val total = job.totalChunks

        FileInputStream(job.filePath).use { fis ->
            var seq = 0
            val payload = ByteArray(CHUNK_PAYLOAD)
            while (true) {
                val n = readFull(fis, payload)
                if (n <= 0) break

                // Flow control: chờ cống rãnh thông thoáng trước khi nhét thêm.
                var waited = 0
                while (adc.bufferedAmount() > HIGH_WATER_MARK && waited < 30_000) {
                    kotlinx.coroutines.delay(30)
                    waited += 30
                }

                val frame = ByteArray(CHUNK_HEADER + n)
                frame[0] = FRAME_DATA_CHUNK.toByte()
                frame[1] = (idHash shr 8).toByte(); frame[2] = idHash.toByte()
                frame[3] = (seq ushr 24).toByte(); frame[4] = (seq ushr 16).toByte()
                frame[5] = (seq ushr 8).toByte();  frame[6] = seq.toByte()
                frame[7] = (total shr 8).toByte(); frame[8] = total.toByte()
                frame[9] = (n shr 8).toByte();     frame[10] = n.toByte()
                System.arraycopy(payload, 0, frame, CHUNK_HEADER, n)

                // binary frame — giữ nguyên stack: JS phía nhận bóc byte đầu để phân loại.
                val sent = runCatching {
                    adc.send(DataChannel.Buffer(ByteBuffer.wrap(frame), true))
                }

                if (sent.isFailure) {
                    Log.e(TAG, "send chunk failed at seq=$seq for ${job.photoId}: ${sent.exceptionOrNull()?.message}")
                    job.onDone?.invoke(false)
                    return
                }
                seq++
                kotlinx.coroutines.delay(CHUNK_PACE_MS)
            }
        }
        Log.i(TAG, "streamFile done: ${job.photoId} ($total chunks)")
        job.onDone?.invoke(true)
    }

    private fun readFull(fis: FileInputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = fis.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    /** Chờ channel ADC mở (tối đa 10s) — đề phòng gọi trước khi DTLS xong. */
    private suspend fun awaitOpenChannel(): DataChannel? {
        var waited = 0L
        while (waited < 10_000) {
            // A Content Sharing session sends on its own stream (1002); older flows use the foreground ADC.
            val adc = webRtcManager.shareChannel?.takeIf { it.state() == DataChannel.State.OPEN }
                ?: webRtcManager.foregroundAdcChannel
            if (adc != null && adc.state() == DataChannel.State.OPEN) return adc
            kotlinx.coroutines.delay(100)
            waited += 100
        }
        return null
    }

    // ═══════════════════ PHÍA NHẬN ═══════════════════

    /**
     * Được DataChannelManager gọi TRƯỚC khi đẩy bytes lên WebView.
     * Trả về true nếu frame đã được tiêu thụ hoàn toàn bởi transport
     * (tức là binary DATA_CHUNK — không cần nfred cho JS path text).
     */
    fun onIncomingBytes(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes[0].toInt() != FRAME_DATA_CHUNK) return false

        val idHash = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val seq = ((bytes[3].toInt() and 0xFF) shl 24) or ((bytes[4].toInt() and 0xFF) shl 16) or
                ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        val total = ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[8].toInt() and 0xFF)
        val len = ((bytes[9].toInt() and 0xFF) shl 8) or (bytes[10].toInt() and 0xFF)
        if (total < 1 || total > 0xFFFF || len < 0 || CHUNK_HEADER + len > bytes.size) {
            Log.w(TAG, "Malformed DATA_CHUNK frame (total=$total len=$len size=${bytes.size}) — dropped")
            return true
        }

        if (total == 1) {
            // Fast path: ảnh/nét nhỏ nằm trọn 1 chunk — chuyển thẳng cho JS không qua buffer.
            deliverToJs(idHash, bytes.copyOfRange(CHUNK_HEADER, CHUNK_HEADER + len))
            return true
        }

        val buf = rxBuffers.compute(idHash) { _, existing ->
            existing ?: AssmBuffer(total)
        } ?: return true

        if (seq < total && buf.parts[seq] == null) {
            buf.parts[seq] = bytes.copyOfRange(CHUNK_HEADER, CHUNK_HEADER + len)
            buf.received++
        }
        if (buf.received >= total) {
            rxBuffers.remove(idHash)
            val assembled = ByteArray(buf.parts.sumOf { it!!.size })
            var off = 0
            for (p in buf.parts) {
                System.arraycopy(p!!, 0, assembled, off, p.size)
                off += p.size
            }
            deliverToJs(idHash, assembled)
        }
        return true
    }

    private fun deliverToJs(idHash: Int, payload: ByteArray) {
        // Đẩy thẳng window.receiveData("PSHARE:<idHash>:<b64>"). KHÔNG đi qua
        // notifyMessageReceived của ImsDcSdk — đường đó base64 lần nữa
        // (double-encode ~33% phình + evaluateJavascript chuỗi BW lớn sẽ chậm).
        // Base64 chỉ chứa [A-Za-z0-9+/=] — an toàn trong single-quoted JS string.
        val b64 = android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)
        val jsPayload = "PSHARE:$idHash:$b64"
        scope.launch(Dispatchers.Main) {
            val wv = webView
            if (wv != null) {
                wv.evaluateJavascript(
                    "if (typeof window.receiveData === 'function') window.receiveData('$jsPayload');",
                    null
                )
            } else {
                // Fallback: đường GSMA khi WebView chưa gắn (hiếm — chỉ đầu cuộc gọi).
                imsDcSdk?.notifyMessageReceived(jsPayload.toByteArray(Charsets.UTF_8))
            }
        }
    }

    // ═══════════════════ TRANSCODE & STORE ═══════════════════

    /**
     * Đưa 1 ảnh từ album (content:// URI) vào photostore sau khi chuẩn hoá:
     * decode theo orientation EXIF → xoay đúng chiều → scale cạnh dài MAX_DIMEN
     * → JPEG q80; đồng thời sinh thumbnail THUMB_DIMEN q70 đặt ở <id>_t.jpg.
     *
     * Trả về [fullBytes, thumbBytes] để caller đóng gói msg JSON "offer".
     */
    suspend fun importFromUri(
        context: Context,
        uri: android.net.Uri,
        photoId: String
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext null

            // EXIF orientation — WebView <img> không tự áp EXIF, phải xoay thủ công.
            val rotated = applyExifRotation(context, uri, bmp)

            val full = scaleTo(rotated, MAX_DIMEN)
            val thumb = scaleTo(rotated, THUMB_DIMEN)

            val fullBytes = compressJpeg(full, JPEG_QUALITY)
            val thumbBytes = compressJpeg(thumb, THUMB_QUALITY)

            val dir = File(context.cacheDir, STORE_DIR)
            dir.mkdirs()
            File(dir, "$photoId.jpg").writeBytes(fullBytes)
            File(dir, "${photoId}_t.jpg").writeBytes(thumbBytes)

            if (rotated !== bmp) bmp.recycle()
            if (full !== rotated) full.recycle()
            if (thumb !== rotated) thumb.recycle()

            Pair(fullBytes.size, thumbBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "importFromUri failed: ${e.message}", e)
            null
        }
    }

    /**
     * Biến thể nhận raw bytes (base64 từ WebView <input type=file>):
     * decode → thử đọc EXIF từ chính bytes đó → xoay/scale/JPEG như importFromUri.
     */
    suspend fun importFromBytes(
        context: Context,
        bytes: ByteArray,
        photoId: String
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return@withContext null

            // EXIF orientation từ segment JPEG/HEIC gốc (nếu đọc được).
            val rotated = try {
                val exif = ExifInterface(java.io.ByteArrayInputStream(bytes))
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
                val degrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degrees == 0f) bmp
                else Bitmap.createBitmap(
                    bmp, 0, 0, bmp.width, bmp.height,
                    Matrix().apply { postRotate(degrees) }, true
                )
            } catch (e: Exception) {
                Log.w(TAG, "EXIF from bytes failed: ${e.message}")
                bmp
            }

            val full = scaleTo(rotated, MAX_DIMEN)
            val thumb = scaleTo(rotated, THUMB_DIMEN)
            val fullBytes = compressJpeg(full, JPEG_QUALITY)
            val thumbBytes = compressJpeg(thumb, THUMB_QUALITY)

            val dir = File(context.cacheDir, STORE_DIR)
            dir.mkdirs()
            File(dir, "$photoId.jpg").writeBytes(fullBytes)
            File(dir, "${photoId}_t.jpg").writeBytes(thumbBytes)

            if (rotated !== bmp) bmp.recycle()
            if (full !== rotated) full.recycle()
            if (thumb !== rotated) thumb.recycle()

            Pair(fullBytes.size, thumbBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "importFromBytes failed: ${e.message}", e)
            null
        }
    }

    private fun applyExifRotation(context: Context, uri: android.net.Uri, bmp: Bitmap): Bitmap {
        return try {
            val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return bmp
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) return bmp
            val m = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width,bmp.height, m, true)
        } catch (e: Exception) {
            Log.w(TAG, "EXIF read failed, using original: ${e.message}")
            bmp
        }
    }

    /**
     * Power-of-two inSampleSize so a 12-50 MP photo is never decoded at full size (a 50 MP ARGB bitmap
     * is ~200 MB and freezes or kills the app); the result still has a long side >= MAX_DIMEN.
     */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        val longSide = maxOf(width, height)
        var sample = 1
        while (longSide / (sample * 2) >= MAX_DIMEN) sample *= 2
        return sample
    }

    private fun scaleTo(src: Bitmap, maxDimen: Int): Bitmap {
        val longSide = maxOf(src.width, src.height)
        if (longSide <= maxDimen) return src
        val scale = maxDimen.toFloat() / longSide
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    private fun compressJpeg(bmp: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    // ═══════════════════ TIỆN ÍCH ═══════════════════

    fun idHashOf(photoId: String): Int =
        (photoId.hashCode() and 0xFFFF)

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    fun fileFor(context: Context, photoId: String, thumb: Boolean = false): File =
        File(File(context.cacheDir, STORE_DIR), if (thumb) "${photoId}_t.jpg" else "$photoId.jpg")

    fun fileForMedia(context: Context, mediaId: String, isVideo: Boolean): File =
        File(File(context.cacheDir, STORE_DIR), if (isVideo) "$mediaId.mp4" else "$mediaId.jpg")

    fun importVideoBytes(context: Context, videoId: String, bytes: ByteArray): File? {
        if (bytes.isEmpty() || bytes.size > 6 * 1024 * 1024) return null
        return try {
            val dir = File(context.cacheDir, STORE_DIR)
            dir.mkdirs()
            File(dir, "$videoId.mp4").apply { writeBytes(bytes) }
        } catch (e: Exception) {
            Log.e(TAG, "importVideoBytes failed: ${e.message}", e)
            null
        }
    }

    /** Dọn toàn bộ photostore — gọi khi BYE/teardown cuộc gọi (GSMA TS.66 lifecycle). */
    fun cleanup(context: Context) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                File(context.cacheDir, STORE_DIR).deleteRecursively()
                rxBuffers.clear()
                Log.i(TAG, "photostore cleaned")
            }.onFailure { Log.w(TAG, "cleanup failed: ${it.message}") }
        }
    }
}
