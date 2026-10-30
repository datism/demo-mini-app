import com.demo.imsdc.webrtc.SharedMediaFiles
import com.demo.imsdc.webrtc.SharedMediaRange
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest

private var passed = 0
private fun verify(name: String, block: () -> Boolean) {
    check(block()) { name }; passed++; println("PASS $name")
}
private fun rejected(name: String, block: () -> Unit) = verify(name) { runCatching(block).isFailure }
private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun meta(id: String, bytes: ByteArray, mime: String = "video/mp4") = SharedMediaFiles.Metadata(
    id, mime, bytes.size.toLong(), hash(bytes), (bytes.size + SharedMediaFiles.CHUNK_BYTES - 1) / SharedMediaFiles.CHUNK_BYTES)
private fun send(receiver: SharedMediaFiles, meta: SharedMediaFiles.Metadata, bytes: ByteArray): SharedMediaFiles.Complete? {
    receiver.begin(meta)
    var completed: SharedMediaFiles.Complete? = null
    var offset = 0
    for (seq in 0 until meta.chunks) {
        val count = minOf(SharedMediaFiles.CHUNK_BYTES, bytes.size - offset)
        completed = receiver.accept(SharedMediaFiles.frame(meta.id, seq, meta.chunks, bytes.copyOfRange(offset, offset + count), count))
        offset += count
    }
    return completed
}
fun main() {
    val dir = Files.createTempDirectory("shared-media-native-").toFile()
    try {
        SharedMediaFiles(dir).use { receiver ->
            val video = ByteArray(2 * 1024 * 1024 + 73) { (it % 251).toByte() }
            val result = send(receiver, meta("video", video), video)
            verify("multi-chunk video stored byte-for-byte") { result!!.file.readBytes().contentEquals(video) }
            verify("completed video has correct extension") { result!!.file.name == "video.mp4" }
            verify("partial file removed on completion") { !File(dir, "video.part").exists() }
            val photo = byteArrayOf(1,2,3,4)
            verify("single-chunk photo") { send(receiver, meta("photo", photo, "image/jpeg"), photo)!!.file.extension == "jpg" }
            verify("legacy hash collision fixture") { "Aa".hashCode() == "BB".hashCode() }
            send(receiver, meta("Aa", photo), photo)
            val other = byteArrayOf(5,6,7,8)
            send(receiver, meta("BB", other), other)
            verify("full IDs isolate colliding legacy hashes") { File(dir,"Aa.mp4").readBytes().contentEquals(photo) && File(dir,"BB.mp4").readBytes().contentEquals(other) }
            val bad = meta("corrupt", photo).copy(sha256 = "0".repeat(64))
            rejected("checksum mismatch rejected") { send(receiver, bad, photo) }
            verify("corrupt file never published") { !File(dir,"corrupt.mp4").exists() && !File(dir,"corrupt.part").exists() }
            rejected("truncated binary header rejected") { receiver.accept(byteArrayOf(2,1,0)) }
            rejected("negative payload length rejected") {
                val frame = SharedMediaFiles.frame("photo",0,1,photo,photo.size)
                ByteBuffer.wrap(frame).putInt(10,-1); receiver.accept(frame)
            }
            rejected("oversized declared file rejected") { receiver.begin(meta("big",photo).copy(bytes=SharedMediaFiles.MAX_BYTES+1)) }
            rejected("path traversal ID rejected") { receiver.begin(meta("../escape",photo)) }
            rejected("unsupported media MIME rejected") { receiver.begin(meta("badMime",photo,"application/octet-stream")) }
            rejected("inconsistent chunk count rejected") { receiver.begin(meta("badCount",photo).copy(chunks=2)) }
            rejected("invalid hash rejected") { receiver.begin(meta("badHash",photo).copy(sha256="abc")) }
            val larger = ByteArray(20000)
            val ordered = meta("ordered",larger)
            receiver.begin(ordered)
            rejected("out-of-order frame rejected") { receiver.accept(SharedMediaFiles.frame("ordered",1,ordered.chunks,ByteArray(4000),4000)) }
            verify("failed ordered transfer closes partial file") { !File(dir,"ordered.part").exists() }
            val retry = meta("retry", larger)
            receiver.begin(retry)
            receiver.accept(SharedMediaFiles.frame("retry",0,2,ByteArray(16000),16000))
            verify("retry begins from clean partial file") { send(receiver,retry,larger)!!.file.length() == 20000L }
            receiver.begin(meta("unfinished",photo))
        }
        verify("close removes unfinished transfer") { !File(dir,"unfinished.part").exists() }
        val rangeFile = File(dir,"range.mp4").apply { writeBytes(ByteArray(100) { it.toByte() }) }
        verify("full response") { SharedMediaRange.parse(100,null).open(rangeFile).use { it.readBytes().size == 100 } }
        verify("bounded range response") { SharedMediaRange.parse(100,"bytes=10-19").open(rangeFile).use { it.readBytes().contentEquals(ByteArray(10) { (it+10).toByte() }) } }
        verify("suffix response") { SharedMediaRange.parse(100,"bytes=-10").start == 90L }
        verify("open-ended response") { SharedMediaRange.parse(100,"bytes=90-").length == 10L }
        verify("end clamped to file size") { SharedMediaRange.parse(100,"bytes=90-999").end == 99L }
        verify("suffix clamped to whole file") { SharedMediaRange.parse(100,"bytes=-999").length == 100L }
        verify("range stream cannot skip beyond boundary") { SharedMediaRange.parse(100,"bytes=10-19").open(rangeFile).use { it.skip(1000) == 10L && it.read() == -1 } }
        verify("range stream single-byte reads bounded") { SharedMediaRange.parse(100,"bytes=99-99").open(rangeFile).use { it.read() == 99 && it.read() == -1 } }
        verify("empty file full response") { SharedMediaRange.parse(0,null).length == 0L }
        listOf("bytes=100-", "bytes=-0", "bytes=-", "bytes=20-10", "bytes=0-1,10-20", "bytes=9999999999999999999999999-").forEach { value ->
            rejected("invalid range $value") { SharedMediaRange.parse(100,value) }
        }
        println("$passed native assertions passed")
    } finally { dir.deleteRecursively() }
}
