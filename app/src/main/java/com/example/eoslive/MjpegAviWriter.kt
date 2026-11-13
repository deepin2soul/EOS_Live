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

/** Пишет Motion JPEG AVI: каждый кадр = готовый JPEG без перекодирования. */
class MjpegAviWriter(file: File) {
    private val raf = RandomAccessFile(file, "rw")
    private var w = 0
    private var h = 0
    private var frames = 0
    private var maxChunk = 0
    private val offs = ArrayList<Int>()
    private val sizes = ArrayList<Int>()
    private var t0 = 0L
    private var started = false

    private companion object {
        const val VIDEO_W = 1200
        const val VIDEO_H = 800
    }

    private val moviTag = 220L // позиция слова 'movi' в файле

    fun addFrame(jpeg: ByteArray) {
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
        val pos = raf.filePointer
        offs.add((pos - moviTag).toInt())
        sizes.add(jpeg.size)
        val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("00dc".toByteArray(Charsets.US_ASCII)).putInt(jpeg.size)
        raf.write(hdr.array())
        raf.write(jpeg)
        if (jpeg.size % 2 == 1) raf.write(0)
        frames++
        if (jpeg.size > maxChunk) maxChunk = jpeg.size
    }

    /** Закрывает файл, возвращает число кадров. */
    fun finish(): Int {
        if (!started) { raf.close(); return 0 }
        val elapsed = (System.nanoTime() - t0) / 1e9
        val fps = if (elapsed > 0.0 && frames > 1) frames / elapsed else 25.0
        val moviEnd = raf.filePointer
        val idx = ByteBuffer.allocate(8 + frames * 16).order(ByteOrder.LITTLE_ENDIAN)
        idx.put("idx1".toByteArray(Charsets.US_ASCII)).putInt(frames * 16)
        for (i in 0 until frames) {
            idx.put("00dc".toByteArray(Charsets.US_ASCII))
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
        val b = ByteBuffer.allocate(224).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(s: String) { b.put(s.toByteArray(Charsets.US_ASCII)) }
        tag("RIFF"); b.putInt(riffSize); tag("AVI ")
        tag("LIST"); b.putInt(192); tag("hdrl")
        tag("avih"); b.putInt(56)
        b.putInt((1_000_000.0 / fps).toInt())
        b.putInt(maxChunk * fps.toInt())
        b.putInt(0)
        b.putInt(0x10)
        b.putInt(frames)
        b.putInt(0)
        b.putInt(1)
        b.putInt(maxChunk)
        b.putInt(w); b.putInt(h)
        repeat(4) { b.putInt(0) }
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
        tag("LIST"); b.putInt(moviSize); tag("movi")
        return b.array()
    }
}
