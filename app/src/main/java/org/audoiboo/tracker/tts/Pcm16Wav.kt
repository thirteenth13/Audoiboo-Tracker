package org.audoiboo.tracker.tts

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

object Pcm16Wav {
    private const val HEADER_SIZE = 44

    fun write(audio: SherpaAudio, file: File) {
        require(audio.sampleRateHz > 0)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            writeHeader(raf, audio.sampleRateHz, audio.samples.size * 2)
            audio.samples.forEach { sample ->
                val pcm = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt()
                writeLeShort(raf, pcm)
            }
        }
    }

    fun append(source: File, destination: File): Int {
        require(source.isFile) { "Source WAV is missing" }
        RandomAccessFile(source, "r").use { input ->
            val sourceInfo = readInfo(input)
            destination.parentFile?.mkdirs()
            if (!destination.exists() || destination.length() == 0L) {
                RandomAccessFile(destination, "rw").use { output ->
                    output.setLength(0)
                    writeHeader(output, sourceInfo.sampleRateHz, 0)
                }
            }
            RandomAccessFile(destination, "rw").use { output ->
                val destinationInfo = readInfo(output)
                require(destinationInfo.sampleRateHz == sourceInfo.sampleRateHz) { "WAV sample rate mismatch" }
                output.seek(output.length())
                input.seek(sourceInfo.dataOffset)
                copyExactly(input, output, sourceInfo.dataSize)
                val dataSize = (output.length() - HEADER_SIZE).toInt()
                patchSizes(output, dataSize)
            }
            return sourceInfo.sampleRateHz
        }
    }

    data class Info(val sampleRateHz: Int, val dataOffset: Long, val dataSize: Int)

    fun info(file: File): Info = RandomAccessFile(file, "r").use(::readInfo)

    private fun readInfo(raf: RandomAccessFile): Info {
        require(raf.length() >= HEADER_SIZE) { "Invalid WAV header" }
        raf.seek(0)
        require(readAscii(raf, 4) == "RIFF") { "Not a RIFF file" }
        raf.skipBytes(4)
        require(readAscii(raf, 4) == "WAVE") { "Not a WAVE file" }
        require(readAscii(raf, 4) == "fmt ") { "Unsupported WAV layout" }
        require(readLeInt(raf) == 16) { "Unsupported WAV fmt size" }
        require(readLeShort(raf) == 1) { "Only PCM WAV is supported" }
        require(readLeShort(raf) == 1) { "Only mono WAV is supported" }
        val sampleRate = readLeInt(raf)
        raf.skipBytes(6)
        require(readLeShort(raf) == 16) { "Only 16-bit PCM WAV is supported" }
        require(readAscii(raf, 4) == "data") { "Unsupported WAV chunks" }
        val dataSize = readLeInt(raf)
        require(dataSize >= 0 && HEADER_SIZE.toLong() + dataSize <= raf.length()) { "Invalid WAV data size" }
        return Info(sampleRate, HEADER_SIZE.toLong(), dataSize)
    }

    private fun writeHeader(raf: RandomAccessFile, sampleRateHz: Int, dataSize: Int) {
        raf.writeBytes("RIFF")
        writeLeInt(raf, 36 + dataSize)
        raf.writeBytes("WAVEfmt ")
        writeLeInt(raf, 16)
        writeLeShort(raf, 1)
        writeLeShort(raf, 1)
        writeLeInt(raf, sampleRateHz)
        writeLeInt(raf, sampleRateHz * 2)
        writeLeShort(raf, 2)
        writeLeShort(raf, 16)
        raf.writeBytes("data")
        writeLeInt(raf, dataSize)
    }

    private fun patchSizes(raf: RandomAccessFile, dataSize: Int) {
        raf.seek(4)
        writeLeInt(raf, 36 + dataSize)
        raf.seek(40)
        writeLeInt(raf, dataSize)
    }

    private fun copyExactly(input: RandomAccessFile, output: RandomAccessFile, byteCount: Int) {
        var remaining = byteCount
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            require(count > 0) { "Unexpected end of WAV data" }
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun readAscii(raf: RandomAccessFile, size: Int): String = ByteArray(size).also(raf::readFully).toString(Charsets.US_ASCII)
    private fun readLeShort(raf: RandomAccessFile): Int = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
    private fun readLeInt(raf: RandomAccessFile): Int = readLeShort(raf) or (readLeShort(raf) shl 16)
    private fun writeLeShort(raf: RandomAccessFile, value: Int) { raf.write(value and 0xff); raf.write((value ushr 8) and 0xff) }
    private fun writeLeInt(raf: RandomAccessFile, value: Int) { writeLeShort(raf, value); writeLeShort(raf, value ushr 16) }
}
