package com.voicestudio.mobile.data.library

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams mono float PCM to a 16-bit WAV without holding the whole recording in
 * memory (audiobook chapters can be hours long). On close it fills in the RIFF
 * sizes, appends the same RIFF INFO synthetic-audio provenance marker as
 * `markSynthetic`, and syncs the file to storage.
 */
class WaveWriter(private val file: File, val sampleRate: Int, private val synthetic: Boolean = true) : Closeable {
    private val output = RandomAccessFile(file.apply { parentFile?.mkdirs() }, "rw").apply { setLength(0); write(ByteArray(44)) }
    private val buffer = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
    var frames = 0L
        private set
    private var closed = false

    init { require(sampleRate in 8000..192000) }

    fun write(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset) {
        check(!closed)
        for (i in offset until offset + count) {
            if (buffer.remaining() < 2) flush()
            val value = samples[i].takeIf { it.isFinite() } ?: 0f
            buffer.putShort((value.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        }
        frames += count
    }
    fun silence(seconds: Double) = write(FloatArray((seconds * sampleRate).toInt().coerceAtLeast(0)))
    private fun flush() { output.write(buffer.array(), 0, buffer.position()); buffer.clear() }

    override fun close() {
        if (closed) return
        closed = true
        try {
            flush()
            val dataBytes = frames * 2
            val marker = "VoiceStudio synthetic audio\u0000".toByteArray(Charsets.US_ASCII)
            val padded = marker.size + (marker.size % 2)
            if (synthetic) {
                val info = ByteBuffer.allocate(8 + 4 + 8 + padded).order(ByteOrder.LITTLE_ENDIAN)
                info.put("LIST".toByteArray()).putInt(4 + 8 + padded).put("INFO".toByteArray()).put("ICMT".toByteArray()).putInt(marker.size).put(marker)
                if (marker.size % 2 != 0) info.put(0)
                output.write(info.array())
            }
            val total = output.length()
            require(total - 8 <= 0xffffffffL) { "WAV larger than 4 GB" }
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt((total - 8).toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(dataBytes.toInt())
            output.seek(0); output.write(header.array())
            output.fd.sync()
        } finally { output.close() }
    }
}
