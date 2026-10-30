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
import org.json.JSONObject

/**
 * Photos and videos share ADC 1000. JSON begin (wire=2) precedes binary frames on
 * the same reliable ordered channel. v2 header: type=2 (u8), ID length (u8),
 * sequence / total / payload length (u32 BE each), UTF-8 ID, payload (<=16000).
 * Files are received on IO into .part files, SHA-256 verified, then atomically
 * renamed and exposed through the WebView's local /media/ origin. Video bytes
 * never cross evaluateJavascript. Legacy photo-only type=1 is still accepted.
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
        const val CHUNK_PAYLOAD: Int = SharedMediaFiles.CHUNK_BYTES

        /** Header 11 byte: type(1) + idHash(2) + seq(4) + total(2) + len(2). */
        const val CHUNK_HEADER: Int = 11

        /** Ngưỡng ngưng gửi khi SCTP buffer còn treo nhiều data (đơn vị byte). */
        const val HIGH_WATER_MARK: Int = 512 * 1024

        /** Nhịp nghỉ tối thiểu giữa 2 chunk liên tiếp (ms) — chống burst làm ngợm pacer nội bộ libwebrtc. */
        const val CHUNK_PACE_MS: Long = 12

        const val MAX_MEDIA_BYTES: Long = SharedMediaFiles.MAX_BYTES

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
    private val assets = ConcurrentHashMap<String, JSONObject>()
    private var fileReceiver: SharedMediaFiles? = null
    private val receiveQueue = Channel<ByteArray>(128)
    @Volatile private var closed = false

    init {
        scope.launch(Dispatchers.IO) {
            try {
                for (bytes in receiveQueue) {
                    try { receiveFileFrame(bytes) }
                    catch (e: Exception) {
                        Log.w(TAG, "Rejected media frame", e)
                        if (e is SharedMediaFiles.TransferFailure) {
                            notifyPage(JSONObject().put("t", "transfer-error").put("id", e.mediaId))
                        }
                    }
                }
            } finally {
                fileReceiver?.close()
            }
        }
    }

    private fun extension(mime: String): String = SharedMediaFiles.extension(mime)

    /** URI decoding/copying stays off the UI and WebView threads; never bridge a full video as base64. */
    suspend fun importMedia(context: Context, uri: android.net.Uri): JSONObject = withContext(Dispatchers.IO) {
        check(!closed) { "Call ended" }
        val id = "m_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        val isVideo = mime.startsWith("video/")
        val storedMime = if (isVideo) mime else "image/jpeg"
        val ext = extension(storedMime)
        require(isVideo || mime.startsWith("image/")) { "Chọn ảnh hoặc video MP4/WebM/3GP" }
        val dir = File(context.cacheDir, STORE_DIR).apply { mkdirs() }
        val file = File(dir, "$id.$ext")
        var width = 0
        var height = 0
        try {
            if (isVideo) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_MEDIA_BYTES) { "Video tối đa 50 MB" }
                            check(!closed) { "Call ended" }
                            out.write(buf, 0, n)
                        }
                    }
                } ?: error("Không đọc được video")
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation == 90 || rotation == 270) { val swap = width; width = height; height = swap }
                    if (android.os.Build.VERSION.SDK_INT >= 27) {
                        // Thumbnail failure must not reject an otherwise readable video or affect the call codec.
                        runCatching {
                            val thumb = retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_DIMEN, THUMB_DIMEN)
                            thumb?.let {
                                try { File(dir, "${id}_t.jpg").writeBytes(compressJpeg(it, THUMB_QUALITY)) }
                                finally { it.recycle() }
                            }
                        }.onFailure { Log.w(TAG, "Video thumbnail unavailable: ${it.message}") }
                    }
                } finally { retriever.release() }
                require(width > 0 && height > 0) { "Không đọc được kích thước video" }
            } else {
                require(importFromUri(context, uri, id) != null) { "Không đọc được ảnh" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                width = bounds.outWidth
                height = bounds.outHeight
            }
            check(!closed) { "Call ended" }
            require(file.length() in 1..MAX_MEDIA_BYTES) { "Tệp rỗng hoặc lớn hơn 50 MB" }
            val thumb = File(dir, "${id}_t.jpg")
            val meta = JSONObject().put("id", id).put("mime", storedMime).put("w", width).put("h", height)
                .put("bytes", file.length()).put("sha256", hashFile(file))
                .put("url", "https://miniapp/media/${file.name}")
                .put("thumb", if (thumb.exists()) android.util.Base64.encodeToString(thumb.readBytes(), android.util.Base64.NO_WRAP) else "")
            assets[id] = meta
            meta
        } catch (e: Exception) {
            file.delete()
            File(dir, "${id}_t.jpg").delete()
            throw e
        }
    }

    private fun hashFile(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; hash.update(buf, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════ PHÍA GỬI ═══════════════════

    /**
     * Đưa ảnh vào hàng đợi stream. Trả về totalChunks (đã tính sẵn) để caller
     * có thể bắn msg JSON "begin" trước/sau khi gọi hàm này.
     */
    fun enqueueFile(context: Context, photoId: String, onDone: ((Boolean) -> Unit)? = null): Int? {
        if (closed || !photoId.matches(Regex("[A-Za-z0-9_-]{1,96}"))) return null
        val file = fileFor(context, photoId)
        if (!file.exists()) {
            Log.w(TAG, "enqueueFile: not found ${file.absolutePath}")
            return null
        }
        val total = ((file.length() + CHUNK_PAYLOAD - 1) / CHUNK_PAYLOAD).toInt()
        if (total < 1 || file.length() > MAX_MEDIA_BYTES) {
            Log.w(TAG, "enqueueFile: invalid chunk count $total for ${file.length()} bytes")
            return null
        }
        if (sendQueue.trySend(SendJob(photoId, file, total, onDone)).isFailure) return null
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
                    try { streamFile(job) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        Log.e(TAG, "streamFile failed for ${job.photoId}: ${e.message}", e)
                        job.onDone?.invoke(false)
                        val error = JSONObject().put("t", "transfer-error").put("id", job.photoId)
                        imsDcSdk?.sendOrQueueAdc(error.toString().toByteArray())
                    }
                }
            }
        }
    }

    /** Stream 1 file theo chunk, gate theo bufferedAmount. */
    private suspend fun streamFile(job: SendJob) {
        val adc = awaitOpenChannel() ?: run {
            job.onDone?.invoke(false); return
        }
        val total = job.totalChunks
        val metadata = assets[job.photoId]?.let { JSONObject(it.toString()) }
            ?: JSONObject().put("id", job.photoId).put("mime", "image/jpeg")
                .put("bytes", job.filePath.length()).put("sha256", hashFile(job.filePath))
        metadata.remove("thumb")
        metadata.remove("url")
        metadata.put("t", "begin").put("wire", 2).put("chunks", total)
        sendFrame(adc, metadata.toString().toByteArray(), false)

        FileInputStream(job.filePath).use { fis ->
            var seq = 0
            val payload = ByteArray(CHUNK_PAYLOAD)
            while (true) {
                val n = readFull(fis, payload)
                if (n <= 0) break

                // v2 carries the full ID, avoiding collisions in the old 16-bit photo hash.
                val frame = SharedMediaFiles.frame(job.photoId, seq, total, payload, n)
                sendFrame(adc, frame, true)
                seq++
                kotlinx.coroutines.delay(CHUNK_PACE_MS)
            }
        }
        Log.i(TAG, "streamFile done: ${job.photoId} ($total chunks)")
        job.onDone?.invoke(true)
    }

    private suspend fun sendFrame(adc: DataChannel, bytes: ByteArray, binary: Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (!closed && android.os.SystemClock.elapsedRealtime() < deadline) {
            check(adc.state() == DataChannel.State.OPEN) { "Data channel closed" }
            if (adc.bufferedAmount() <= HIGH_WATER_MARK && adc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), binary))) return
            kotlinx.coroutines.delay(20)
        }
        error("Media transfer timed out")
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
            val adc = webRtcManager.adcChannel
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
        if (closed || bytes.isEmpty()) return false
        if (bytes[0] == 2.toByte() || bytes[0] == '{'.code.toByte()) {
            val binary = bytes[0] == 2.toByte()
            val begin = !binary && runCatching { JSONObject(String(bytes, Charsets.UTF_8)).let { it.optString("t") == "begin" && it.optInt("wire") == 2 } }.getOrDefault(false)
            if (binary || begin) {
                if (receiveQueue.trySend(bytes).isFailure) {
                    Log.e(TAG, "Media receive queue full; rejecting transfer")
                    cleanup(webView?.context ?: return binary)
                }
                return binary
            }
        }
        if (bytes.isEmpty() || bytes[0].toInt() != FRAME_DATA_CHUNK) return false
        if (bytes.size < CHUNK_HEADER) return true

        val idHash = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val seq = ((bytes[3].toInt() and 0xFF) shl 24) or ((bytes[4].toInt() and 0xFF) shl 16) or
                ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        val total = ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[8].toInt() and 0xFF)
        val len = ((bytes[9].toInt() and 0xFF) shl 8) or (bytes[10].toInt() and 0xFF)
        if (total < 1 || total.toLong() * CHUNK_PAYLOAD > MAX_MEDIA_BYTES + CHUNK_PAYLOAD || seq < 0 || seq >= total || len > CHUNK_PAYLOAD || CHUNK_HEADER + len > bytes.size) {
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

    /** IO-only receiver; large files are verified on disk before exposing a playback URL. */
    private fun receiveFileFrame(bytes: ByteArray) {
        val context = webView?.context ?: return
        val receiver = fileReceiver ?: SharedMediaFiles(File(context.cacheDir, STORE_DIR)).also { fileReceiver = it }
        if (bytes[0] == '{'.code.toByte()) {
            val meta = JSONObject(String(bytes, Charsets.UTF_8))
            receiver.begin(SharedMediaFiles.Metadata(meta.getString("id"), meta.getString("mime"),
                meta.getLong("bytes"), meta.getString("sha256"), meta.getInt("chunks"), meta.optInt("w"), meta.optInt("h")))
        } else {
            val result = receiver.accept(bytes) ?: return
            val meta = result.metadata
            val event = JSONObject().put("id", meta.id).put("mime", meta.mime).put("bytes", meta.bytes)
                .put("sha256", meta.sha256).put("w", meta.width).put("h", meta.height)
                .put("t", "stored").put("url", "https://miniapp/media/${result.file.name}")
            assets[meta.id] = event
            notifyPage(event)
        }
    }

    private fun notifyPage(event: JSONObject) {
        scope.launch(Dispatchers.Main) {
            if (!closed) webView?.evaluateJavascript("window.receiveData && window.receiveData(${JSONObject.quote(event.toString())});", null)
        }
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

            listOf(bmp, rotated, full, thumb).distinct().forEach { it.recycle() }

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
                orientBitmap(ExifInterface(ByteArrayInputStream(bytes)), bmp)
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

            listOf(bmp, rotated, full, thumb).distinct().forEach { it.recycle() }

            Pair(fullBytes.size, thumbBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "importFromBytes failed: ${e.message}", e)
            null
        }
    }

    private fun applyExifRotation(context: Context, uri: android.net.Uri, bmp: Bitmap): Bitmap {
        return try {
            val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return bmp
            orientBitmap(exif, bmp)
        } catch (e: Exception) {
            Log.w(TAG, "EXIF read failed, using original: ${e.message}")
            bmp
        }
    }

    private fun orientBitmap(exif: ExifInterface, bmp: Bitmap): Bitmap {
        val transform = Matrix()
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { transform.setRotate(180f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { transform.setRotate(90f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { transform.setRotate(-90f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, transform, true)
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
        File(File(context.cacheDir, STORE_DIR), if (thumb) "${photoId}_t.jpg" else "$photoId.${extension(assets[photoId]?.optString("mime") ?: "image/jpeg")}")

    /** Dọn toàn bộ photostore — gọi khi BYE/teardown cuộc gọi (GSMA TS.66 lifecycle). */
    fun cleanup(context: Context) {
        if (closed) return
        closed = true
        sendQueue.close()
        receiveQueue.cancel()
        // lifecycleScope may already be cancelled in onDestroy; cleanup must still run.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                File(context.cacheDir, STORE_DIR).deleteRecursively()
                rxBuffers.clear()
                assets.clear()
                Log.i(TAG, "photostore cleaned")
            }.onFailure { Log.w(TAG, "cleanup failed: ${it.message}") }
        }
    }
}
