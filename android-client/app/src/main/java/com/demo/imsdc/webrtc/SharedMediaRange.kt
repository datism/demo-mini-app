package com.demo.imsdc.webrtc

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/** A single HTTP byte range, including suffix/open-ended ranges used by WebView video seeking. */
data class SharedMediaRange(val start: Long, val end: Long, val total: Long, val partial: Boolean) {
    val length: Long get() = (end - start + 1).coerceAtLeast(0)
    companion object {
        fun parse(size: Long, header: String?): SharedMediaRange {
            require(size >= 0)
            if (header == null) return SharedMediaRange(0, size - 1, size, false)
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: error("Invalid range")
            val first = match.groupValues[1]
            val last = match.groupValues[2]
            require(first.isNotEmpty() || last.isNotEmpty())
            val start: Long
            val end: Long
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: error("Invalid suffix")
                require(suffix > 0)
                start = (size - suffix).coerceAtLeast(0)
                end = size - 1
            } else {
                start = first.toLongOrNull() ?: error("Invalid start")
                end = if (last.isEmpty()) size - 1 else (last.toLongOrNull() ?: error("Invalid end")).coerceAtMost(size - 1)
            }
            require(start < size && end >= start)
            return SharedMediaRange(start, end, size, true)
        }
    }

    fun open(file: File): InputStream {
        val input = file.inputStream()
        input.channel.position(start)
        return object : FilterInputStream(input) {
            var remaining = length
            override fun read(): Int {
                if (remaining <= 0) return -1
                val value = `in`.read()
                if (value >= 0) remaining--
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                if (count == 0) return 0
                if (remaining <= 0) return -1
                val n = `in`.read(buffer, offset, minOf(count.toLong(), remaining).toInt())
                if (n > 0) remaining -= n
                return n
            }
            override fun skip(count: Long): Long {
                val n = `in`.skip(count.coerceIn(0, remaining)); remaining -= n; return n
            }
            override fun available(): Int = minOf(remaining, `in`.available().toLong()).toInt()
        }
    }
}
