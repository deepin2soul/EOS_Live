package com.example.eoslive

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Jpeg {
    /** Вырезает JPEG (от первого FFD8 до последнего FFD9) из произвольных данных. */
    fun extract(d: ByteArray): ByteArray? {
        var s = -1
        for (i in 0 until d.size - 1) {
            if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte()) { s = i; break }
        }
        if (s < 0) return null
        var e = -1
        for (i in d.size - 2 downTo s + 2) {
            if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD9.toByte()) { e = i + 2; break }
        }
        if (e < 0) return null
        return d.copyOfRange(s, e)
    }

    /** Ширина и высота из маркера SOF. */
    fun size(j: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < j.size) {
            if (j[i] != 0xFF.toByte()) { i++; continue }
            val m = j[i + 1].toInt() and 0xFF
            if (m == 0xD8 || m == 0x01 || (m in 0xD0..0xD7)) { i += 2; continue }
            val len = ((j[i + 2].toInt() and 0xFF) shl 8) or (j[i + 3].toInt() and 0xFF)
            val isSof = m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC
            if (isSof) {
                val h = ((j[i + 5].toInt() and 0xFF) shl 8) or (j[i + 6].toInt() and 0xFF)
                val w = ((j[i + 7].toInt() and 0xFF) shl 8) or (j[i + 8].toInt() and 0xFF)
                return Pair(w, h)
            }
            i += 2 + len
        }
        return null
    }
}

/**
 * Пишет Motion JPEG AVI: каждый кадр = готовый JPEG без перекодирования.
 * Если withAudio = true, добавляется второй поток — несжатый PCM 16 бит, моно (звук с микрофона телефона).
 */
class MjpegAviWriter(file: File, private val withAudio: Boolean = false, private val sampleRate: Int = 44100) {
    private val raf = RandomAccessFile(file, "rw")
    private val lock = Any()
    private var w = 0
    private var h = 0
    private var frames = 0
    private var audioBytes = 0L
    private var maxChunk = 0
    private val offs = ArrayList<Int>()
    private val sizes = ArrayList<Int>()
    private val isAudio = ArrayList<Boolean>()
    private var t0 = 0L
    private var started = false

    private companion object {
        const val VIDEO_W = 1200
        const val VIDEO_H = 800
    }

    private val headerSize = if (withAudio) 324 else 224
    private val moviTag = (headerSize - 4).toLong() // позиция слова 'movi' в файле

    private fun writeChunk(id: String, data: ByteArray, len: Int, audio: Boolean) {
        val pos = raf.filePointer
        offs.add((pos - moviTag).toInt())
        sizes.add(len)
        isAudio.add(audio)
        val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put(id.toByteArray(Charsets.US_ASCII)).putInt(len)
        raf.write(hdr.array())
        raf.write(data, 0, len)
        if (len % 2 == 1) raf.write(0)
    }

    fun addFrame(jpeg: ByteArray) {
      synchronized(lock) {
        if (!started) {
            // Размер в заголовке фиксированный 3:2 и не зависит от первого кадра: камера на ×1 шлёт 1024x680,
            // на ×5 — 768x800 (непрямоугольные пиксели). Плеер растягивает любой кадр до размера заголовка,
            // поэтому при заголовке 3:2 оба режима выглядят правильно, даже если зум менялся во время записи.
            w = VIDEO_W; h = VIDEO_H
            raf.setLength(0)
            raf.write(header(25.0, 0, 0))
            started = true
            t0 = System.nanoTime()
        }
        writeChunk("00dc", jpeg, jpeg.size, false)
        frames++
        if (jpeg.size > maxChunk) maxChunk = jpeg.size
      }
    }

    /** PCM 16 бит моно; вызывается из потока микрофона. До первого видеокадра данные отбрасываются. */
    fun addAudio(pcm: ByteArray, len: Int) {
      synchronized(lock) {
        if (!withAudio || !started || len <= 0) return@synchronized
        writeChunk("01wb", pcm, len, true)
        audioBytes += len
      }
    }

    /** Закрывает файл, возвращает число кадров. */
    fun finish(): Int = synchronized(lock) {
        finishLocked()
    }

    private fun finishLocked(): Int {
        if (!started) { raf.close(); return 0 }
        val elapsed = (System.nanoTime() - t0) / 1e9
        val fps = if (elapsed > 0.0 && frames > 1) frames / elapsed else 25.0
        val moviEnd = raf.filePointer
        val n = offs.size
        val idx = ByteBuffer.allocate(8 + n * 16).order(ByteOrder.LITTLE_ENDIAN)
        idx.put("idx1".toByteArray(Charsets.US_ASCII)).putInt(n * 16)
        for (i in 0 until n) {
            idx.put((if (isAudio[i]) "01wb" else "00dc").toByteArray(Charsets.US_ASCII))
            idx.putInt(0x10).putInt(offs[i]).putInt(sizes[i])
        }
        raf.write(idx.array())
        val end = raf.filePointer
        raf.seek(0)
        raf.write(header(fps, (end - 8).toInt(), (moviEnd - moviTag).toInt()))
        raf.close()
        return frames
    }

    private fun header(fps: Double, riffSize: Int, moviSize: Int): ByteArray {
        val b = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(s: String) { b.put(s.toByteArray(Charsets.US_ASCII)) }
        val hdrlSize = if (withAudio) 292 else 192
        tag("RIFF"); b.putInt(riffSize); tag("AVI ")
        tag("LIST"); b.putInt(hdrlSize); tag("hdrl")
        tag("avih"); b.putInt(56)
        b.putInt((1_000_000.0 / fps).toInt())
        b.putInt(maxChunk * fps.toInt())
        b.putInt(0)
        b.putInt(0x10)
        b.putInt(frames)
        b.putInt(0)
        b.putInt(if (withAudio) 2 else 1)
        b.putInt(maxChunk)
        b.putInt(w); b.putInt(h)
        repeat(4) { b.putInt(0) }
        // поток 0: видео MJPG
        tag("LIST"); b.putInt(116); tag("strl")
        tag("strh"); b.putInt(56)
        tag("vids"); tag("MJPG")
        b.putInt(0)
        b.putShort(0); b.putShort(0)
        b.putInt(0)
        b.putInt(1000)
        b.putInt((fps * 1000).toInt())
        b.putInt(0)
        b.putInt(frames)
        b.putInt(maxChunk)
        b.putInt(-1)
        b.putInt(0)
        b.putShort(0); b.putShort(0); b.putShort(w.toShort()); b.putShort(h.toShort())
        tag("strf"); b.putInt(40)
        b.putInt(40); b.putInt(w); b.putInt(h)
        b.putShort(1); b.putShort(24)
        tag("MJPG")
        b.putInt(w * h * 3)
        repeat(4) { b.putInt(0) }
        // поток 1: звук PCM
        if (withAudio) {
            tag("LIST"); b.putInt(92); tag("strl")
            tag("strh"); b.putInt(56)
            tag("auds"); b.putInt(0)
            b.putInt(0)
            b.putShort(0); b.putShort(0)
            b.putInt(0)
            b.putInt(1)
            b.putInt(sampleRate)
            b.putInt(0)
            b.putInt((audioBytes / 2).toInt())
            b.putInt(8192)
            b.putInt(-1)
            b.putInt(2)
            b.putShort(0); b.putShort(0); b.putShort(0); b.putShort(0)
            tag("strf"); b.putInt(16)
            b.putShort(1)            // WAVE_FORMAT_PCM
            b.putShort(1)            // моно
            b.putInt(sampleRate)
            b.putInt(sampleRate * 2)
            b.putShort(2)            // block align
            b.putShort(16)           // бит на отсчёт
        }
        tag("LIST"); b.putInt(moviSize); tag("movi")
        return b.array()
    }
}

/** Захват звука с микрофона телефона (PCM 16 бит, моно) в отдельном потоке. */
class MicCapture(private val rate: Int, private val onData: (ByteArray, Int) -> Unit) {
    @Volatile private var run = false
    private var rec: android.media.AudioRecord? = null
    private var th: Thread? = null

    /** false — микрофон не удалось открыть. */
    @android.annotation.SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = android.media.AudioRecord.getMinBufferSize(
            rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC, rate,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, 16384))
        } catch (e: Exception) { return false }
        if (r.state != android.media.AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        rec = r
        run = true
        r.startRecording()
        th = Thread {
            val buf = ByteArray(4096)
            while (run) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) onData(buf, n) else if (n < 0) break
            }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    fun stop() {
        run = false
        try { th?.join(500) } catch (_: Exception) {}
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        rec = null
    }
}
