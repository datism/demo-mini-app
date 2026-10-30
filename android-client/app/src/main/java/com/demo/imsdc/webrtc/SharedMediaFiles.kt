package com.demo.imsdc.webrtc

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Ordered, bounded disk transfer. Has no Android dependencies so framing and integrity can be tested on JVM. */
class SharedMediaFiles(private val directory: File) : Closeable {
    data class Metadata(val id: String, val mime: String, val bytes: Long, val sha256: String,
                        val chunks: Int, val width: Int = 0, val height: Int = 0)
    data class Complete(val metadata: Metadata, val file: File)
    class TransferFailure(val mediaId: String, cause: Exception) : IllegalArgumentException("Media transfer failed: $mediaId", cause)
    private class Pending(val metadata: Metadata, val part: File) {
        val output = FileOutputStream(part)
        val digest = MessageDigest.getInstance("SHA-256")
        var next = 0
        var bytes = 0L
        val startedAt = System.nanoTime()
        fun discard() { runCatching { output.close() }; part.delete() }
    }
    private val pending = HashMap<String, Pending>()

    companion object {
        const val CHUNK_BYTES = 16000
        const val MAX_BYTES = 50L * 1024 * 1024
        fun extension(mime: String): String = when (mime) {
            "image/jpeg" -> "jpg"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/3gpp" -> "3gp"
            else -> throw IllegalArgumentException("Định dạng không hỗ trợ: $mime")
        }
        fun frame(id: String, seq: Int, total: Int, payload: ByteArray, length: Int): ByteArray {
            require(id.matches(Regex("[A-Za-z0-9_-]{1,96}")))
            require(seq >= 0 && seq < total && length in 1..CHUNK_BYTES && length <= payload.size)
            val encodedId = id.toByteArray(Charsets.UTF_8)
            return ByteBuffer.allocate(14 + encodedId.size + length)
                .put(2.toByte()).put(encodedId.size.toByte()).putInt(seq).putInt(total).putInt(length)
                .put(encodedId).put(payload, 0, length).array()
        }
    }

    fun begin(meta: Metadata) {
        require(meta.id.matches(Regex("[A-Za-z0-9_-]{1,96}")))
        extension(meta.mime)
        require(meta.bytes in 1..MAX_BYTES)
        require(meta.chunks == ((meta.bytes + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt())
        require(meta.sha256.matches(Regex("[a-f0-9]{64}")))
        // Interrupted peers must not retain file descriptors indefinitely.
        val expired = pending.filterValues { System.nanoTime() - it.startedAt > 180_000_000_000L }.keys.toList()
        expired.forEach { pending.remove(it)?.discard() }
        pending.remove(meta.id)?.discard()
        require(pending.size < 8) { "Too many media transfers" }
        check(directory.isDirectory || directory.mkdirs())
        pending[meta.id] = Pending(meta, File(directory, "${meta.id}.part"))
    }

    fun accept(bytes: ByteArray): Complete? {
        require(bytes.size >= 14 && bytes[0] == 2.toByte())
        val frame = ByteBuffer.wrap(bytes)
        frame.get()
        val idLen = frame.get().toInt() and 0xff
        val seq = frame.int
        val total = frame.int
        val length = frame.int
        require(idLen in 1..96 && length in 1..CHUNK_BYTES && bytes.size == 14 + idLen + length)
        val idBytes = ByteArray(idLen); frame.get(idBytes)
        val id = String(idBytes, Charsets.UTF_8)
        val transfer = pending[id] ?: return null
        try {
            require(seq == transfer.next && total == transfer.metadata.chunks)
            require(length.toLong() == minOf(CHUNK_BYTES.toLong(), transfer.metadata.bytes - transfer.bytes))
            transfer.output.write(bytes, 14 + idLen, length)
            transfer.digest.update(bytes, 14 + idLen, length)
            transfer.bytes += length
            transfer.next++
            if (transfer.next != total) return null
            transfer.output.close()
            require(transfer.bytes == transfer.metadata.bytes)
            val hash = transfer.digest.digest().joinToString("") { "%02x".format(it) }
            require(hash == transfer.metadata.sha256) { "Media checksum mismatch" }
            val file = File(directory, "$id.${extension(transfer.metadata.mime)}")
            check(transfer.part.renameTo(file)) { "Cannot store media" }
            pending.remove(id)
            return Complete(transfer.metadata, file)
        } catch (e: Exception) {
            pending.remove(id)?.discard()
            throw TransferFailure(id, e)
        }
    }

    override fun close() {
        pending.values.forEach { it.discard() }
        pending.clear()
    }
}
