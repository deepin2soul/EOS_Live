package com.example.eoslive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToLong

/** Постоянная частота кадров в MP4. */
const val REC_FPS = 25

/**
 * Запись в MP4: JPEG от камеры -> Bitmap -> холст входной поверхности кодировщика (H.264/H.265),
 * звук -> AAC. Потоки: декодирование JPEG, такты 25 к/с (рисование), вывод кодировщиков в muxer.
 * Поток камеры не блокируется: offerFrame только кладёт самый свежий кадр в ячейку.
 */
class Mp4Recorder(
    private val file: File,
    private val cfg: RecordSettings,
    private val reqW: Int,
    private val reqH: Int,
    private val withAudio: Boolean,
    private val log: (String) -> Unit
) {
    companion object {
        private fun encoders(mime: String): List<MediaCodecInfo> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { ci -> ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }

        fun hasEncoder(mime: String): Boolean =
            try { encoders(mime).isNotEmpty() } catch (_: Exception) { false }
    }

    private class Src(val jpg: ByteArray, val aspect: Float, val fill: Boolean)
    private class Ready(val bmp: Bitmap, val aspect: Float, val fill: Boolean)
    private class Pending(val track: Int, val buf: ByteBuffer, val info: MediaCodec.BufferInfo)

    // --- кодировщики ---
    private var videoEnc: MediaCodec? = null
    private var audioEnc: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var surface: Surface? = null
    private var hevcUsed = false
    private var codecName = ""
    private var modeUsed = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    var width = reqW
        private set
    var height = reqH
        private set

    // --- состояние ---
    @Volatile private var running = false
    @Volatile private var closing = false
    @Volatile private var closeT = 0L
    @Volatile private var t0 = 0L                 // момент первого такта; от него считается и звук
    private val startT = System.nanoTime()
    private var paceThread: Thread? = null
    private var decodeThread: Thread? = null
    private var drainThread: Thread? = null

    private val inQ = LinkedBlockingQueue<Src>(1)
    private val ready = AtomicReference<Ready?>(null)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    // muxer (трогается только из потока drain)
    private var vTrack = -1
    private var aTrack = -1
    private var muxStarted = false
    private val pending = ArrayList<Pending>()
    private var vDone = false
    private var aDone = false
    @Volatile private var audioActive = false

    // видео-метки времени
    private var vBase = -1L
    private var lastSlot = -1L

    // звук
    private val audioLock = Any()
    private var audioClosed = false
    private var audioStarted = false
    private var audioFramesFed = 0L
    private val channels = if (cfg.stereo) 2 else 1

    // статистика
    @Volatile private var offered = 0
    private var fresh = 0
    private var dup = 0
    private var skippedTicks = 0L
    @Volatile private var videoFrames = 0
    private var outBytes = 0L
    private var audioDroppedFrames = 0L

    fun start(): Boolean {
        try {
            if (!setupVideo()) return false
            audioActive = setupAudio()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            log("запуск MP4-записи: $e")
            release()
            return false
        }
        running = true
        decodeThread = Thread({ decodeLoop() }, "rec-decode").also { it.isDaemon = true; it.start() }
        paceThread = Thread({ paceLoop() }, "rec-pace").also { it.isDaemon = true; it.start() }
        drainThread = Thread({ drainLoop() }, "rec-drain").also { it.isDaemon = true; it.start() }
        return true
    }

    fun describe(): String =
        "${if (hevcUsed) "H.265" else "H.264"} $codecName, ${width}x$height, 25 к/с, " + when (modeUsed) {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ -> "CQ ${cfg.cqQuality()}"
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR -> "CBR %.1f Мбит/с".format(cfg.videoBitrate(hevcUsed) / 1e6)
            else -> "VBR %.1f Мбит/с".format(cfg.videoBitrate(hevcUsed) / 1e6)
        } + ", ключ. кадр ${cfg.keyIntervalSec} с" +
            if (audioActive) ", AAC ${cfg.audioKbps} кбит/с ${cfg.audioRate} Гц ${if (cfg.stereo) "стерео" else "моно"}" else ", без звука"

    // ------------------------------------------------------------------ настройка

    private fun setupVideo(): Boolean {
        var hevc = cfg.isHevc
        var mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        if (hevc && encoders(mime).isEmpty()) {
            log("H.265-кодировщика на телефоне нет, пишу в H.264")
            hevc = false
            mime = MediaFormat.MIMETYPE_VIDEO_AVC
        }
        hevcUsed = hevc
        for (ci in encoders(mime)) {
            try {
                val caps = ci.getCapabilitiesForType(mime)
                val size = pickSize(caps.videoCapabilities)
                if (size == null) { log("кодировщик ${ci.name}: размер ${reqW}x$reqH не поддерживается"); continue }
                val mode = pickMode(caps.encoderCapabilities)
                var enc: MediaCodec? = null
                var lastErr: Exception? = null
                for (minimal in listOf(false, true)) {
                    val e = MediaCodec.createByCodecName(ci.name)
                    try {
                        e.configure(buildFormat(mime, size.first, size.second, mode, hevc, minimal),
                            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        enc = e
                        break
                    } catch (ex: Exception) {
                        lastErr = ex
                        try { e.release() } catch (_: Exception) {}
                    }
                }
                if (enc == null) { log("кодировщик ${ci.name}: $lastErr"); continue }
                val surf = enc.createInputSurface()
                enc.start()
                videoEnc = enc
                surface = surf
                width = size.first
                height = size.second
                modeUsed = mode
                codecName = ci.name
                return true
            } catch (ex: Exception) {
                log("кодировщик ${ci.name}: $ex")
            }
        }
        return false
    }

    private fun pickSize(vc: MediaCodecInfo.VideoCapabilities): Pair<Int, Int>? {
        fun up(v: Int, a: Int) = if (a <= 1) v else (v + a - 1) / a * a
        var w = up(reqW, maxOf(2, vc.widthAlignment))
        var h = up(reqH, maxOf(2, vc.heightAlignment))
        if (vc.isSizeSupported(w, h)) return Pair(w, h)
        w = up(reqW, 16); h = up(reqH, 16)
        if (vc.isSizeSupported(w, h)) return Pair(w, h)
        return null
    }

    private fun pickMode(ec: MediaCodecInfo.EncoderCapabilities): Int {
        val want = when (cfg.rateMode) {
            RateMode.VBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            RateMode.CBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            RateMode.CQ -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
        }
        val order = listOf(want, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        val got = order.firstOrNull { ec.isBitrateModeSupported(it) } ?: want
        if (got != want) log("режим ${cfg.rateMode.name} телефон не поддерживает, использую ${if (got == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) "VBR" else "CBR"}")
        return got
    }

    private fun buildFormat(mime: String, w: Int, h: Int, mode: Int, hevc: Boolean, minimal: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(mime, w, h)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, cfg.videoBitrate(hevc))
        f.setInteger(MediaFormat.KEY_FRAME_RATE, REC_FPS)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, cfg.keyIntervalSec)
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
        if (mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) {
            f.setInteger(MediaFormat.KEY_QUALITY, cfg.cqQuality())
        }
        if (!minimal) {
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        return f
    }

    private fun setupAudio(): Boolean {
        if (!withAudio) return false
        return try {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, cfg.audioRate, channels)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, cfg.audioKbps * 1000)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            e.start()
            audioEnc = e
            true
        } catch (ex: Exception) {
            log("AAC-кодировщик: $ex")
            false
        }
    }

    // ------------------------------------------------------------------ видео

    /** Самый свежий кадр камеры. fill = true — кадр заполняет холст с обрезкой, false — вписывается с полями. */
    fun offerFrame(jpg: ByteArray, aspect: Float, fill: Boolean) {
        if (!running) return
        offered++
        inQ.clear()
        inQ.offer(Src(jpg, aspect, fill))
    }

    private fun decodeLoop() {
        try {
            while (running) {
                val s = inQ.poll(50, TimeUnit.MILLISECONDS) ?: continue
                val b = BitmapFactory.decodeByteArray(s.jpg, 0, s.jpg.size) ?: continue
                ready.getAndSet(Ready(b, s.aspect, s.fill))?.bmp?.recycle()
            }
        } catch (e: Exception) {
            log("ошибка декодирования кадра для записи: $e")
        }
    }

    private fun lockCanvas(): Canvas? {
        val s = surface ?: return null
        return try { s.lockCanvas(null) } catch (e: Exception) { s.lockHardwareCanvas() }
    }

    private fun draw(c: Canvas, r: Ready) {
        val b = r.bmp
        val a = if (r.aspect > 0f) r.aspect else b.width.toFloat() / b.height
        val tw = width.toFloat()
        val th = height.toFloat()
        val dw: Float
        val dh: Float
        val wider = a >= tw / th
        if (wider != r.fill) { dw = tw; dh = tw / a } else { dh = th; dw = th * a }
        c.drawColor(Color.BLACK)
        val l = (tw - dw) / 2f
        val t = (th - dh) / 2f
        c.drawBitmap(b, null, RectF(l, t, l + dw, t + dh), paint)
    }

    /** Такты ровно по 40 мс: на каждый такт рисуется последний кадр камеры (или повтор предыдущего). */
    private fun paceLoop() {
        var cur: Ready? = null
        try {
            while (running && cur == null) {
                cur = ready.getAndSet(null)
                if (cur == null) LockSupport.parkNanos(5_000_000L)
            }
            if (cur == null) return
            val period = 1_000_000_000L / REC_FPS
            val t0n = System.nanoTime()
            t0 = t0n
            var n = 0L
            while (running) {
                val now = System.nanoTime()
                val target = t0n + n * period
                if (target > now) { LockSupport.parkNanos(target - now); continue }
                val late = now - target
                if (late >= period) {
                    val k = late / period
                    n += k
                    skippedTicks += k
                    continue
                }
                val r = ready.getAndSet(null)
                if (r != null) { cur!!.bmp.recycle(); cur = r; fresh++ } else dup++
                val c = lockCanvas()
                if (c == null) { log("не удалось получить холст кодировщика"); break }
                try { draw(c, cur!!) } finally { surface?.unlockCanvasAndPost(c) }
                n++
            }
        } catch (e: Exception) {
            log("ошибка отрисовки кадра в кодировщик: $e")
        } finally {
            cur?.bmp?.recycle()
        }
    }

    /** Метка времени кадра, привязанная к сетке 25 к/с (по реальному моменту отправки в кодировщик). */
    private fun videoPts(ptsUs: Long): Long {
        if (vBase < 0) vBase = ptsUs
        val periodUs = 1_000_000L / REC_FPS
        var slot = ((ptsUs - vBase).toDouble() / periodUs).roundToLong()
        if (slot <= lastSlot) slot = lastSlot + 1
        lastSlot = slot
        return slot * periodUs
    }

    // ------------------------------------------------------------------ звук

    /** Микрофон не открылся — пишем без звука. */
    fun audioFailed() { audioActive = false }

    /** PCM 16 бит моно из потока микрофона (буфер можно переиспользовать сразу после возврата). */
    fun addAudio(pcm: ByteArray, len: Int) {
        synchronized(audioLock) {
            val enc = audioEnc
            if (!audioActive || audioClosed || enc == null || len < 2) return
            val t0n = t0
            if (t0n == 0L) return // видео ещё не пошло
            val rate = cfg.audioRate
            var data = pcm
            var n = len and 1.inv()
            if (cfg.stereo) {
                val st = ByteArray(n * 2)
                var i = 0
                var o = 0
                while (i + 1 < n) {
                    st[o] = pcm[i]; st[o + 1] = pcm[i + 1]; st[o + 2] = pcm[i]; st[o + 3] = pcm[i + 1]
                    i += 2; o += 4
                }
                data = st
                n = o
            }
            val fb = 2 * channels
            var off = 0
            if (!audioStarted) {
                val chunkFrames = n / fb
                val durUs = chunkFrames * 1_000_000L / rate
                val leadUs = (System.nanoTime() - t0n) / 1000 - durUs // начало куска относительно t0
                if (leadUs > 0) {
                    val sil = minOf(leadUs * rate / 1_000_000L, 5L * rate).toInt()
                    if (sil > 0) feed(ByteArray(sil * fb), 0, sil * fb)
                } else if (leadUs < 0) {
                    val skip = ((-leadUs) * rate / 1_000_000L).toInt() * fb
                    if (skip >= n) return
                    off = skip
                }
                audioStarted = true
            }
            feed(data, off, n - off)
        }
    }

    private fun feed(d: ByteArray, off: Int, n: Int) {
        val enc = audioEnc ?: return
        val fb = 2 * channels
        var pos = off
        val end = off + n - (n % fb)
        while (pos < end) {
            val i = enc.dequeueInputBuffer(20_000)
            if (i < 0) {
                // кодировщик не принимает данные: считаем эти отсчёты потерянными, но время идёт
                val lost = (end - pos) / fb
                audioDroppedFrames += lost
                audioFramesFed += lost
                return
            }
            val ib = enc.getInputBuffer(i) ?: return
            ib.clear()
            var k = minOf(ib.remaining(), end - pos)
            k -= k % fb
            if (k <= 0) { enc.queueInputBuffer(i, 0, 0, 0, 0); return }
            ib.put(d, pos, k)
            enc.queueInputBuffer(i, 0, k, audioFramesFed * 1_000_000L / cfg.audioRate, 0)
            audioFramesFed += k / fb
            pos += k
        }
    }

    // ------------------------------------------------------------------ вывод в MP4

    private fun drainLoop() {
        try {
            while (true) {
                val v = if (!vDone) drain(videoEnc, false) else 0
                if (v == 2) vDone = true
                val a = if (audioActive && !aDone) drain(audioEnc, true) else 0
                if (a == 2) aDone = true
                // звук не пошёл за 5 с — пишем без него, иначе файл не начнётся
                if (audioActive && aTrack < 0 && !audioStarted && System.nanoTime() - startT > 5_000_000_000L) {
                    audioActive = false
                    log("звук не пошёл, пишу без звука")
                }
                tryStartMuxer()
                if (vDone && (aDone || !audioActive)) break
                if (closing && System.nanoTime() - closeT > 8_000_000_000L) {
                    log("кодировщик не закрылся вовремя")
                    break
                }
                if (v == 0 && a == 0) Thread.sleep(4)
            }
        } catch (e: Exception) {
            log("ошибка кодирования: $e")
        }
    }

    /** 0 — ничего нет, 1 — что-то обработано, 2 — конец потока. */
    private fun drain(enc: MediaCodec?, audio: Boolean): Int {
        if (enc == null) return 0
        val info = MediaCodec.BufferInfo()
        var res = 0
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return res
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                res = 1
                val m = muxer
                if (m != null && !muxStarted) {
                    val t = m.addTrack(enc.outputFormat)
                    if (audio) aTrack = t else vTrack = t
                }
            } else if (idx >= 0) {
                res = 1
                val buf = enc.getOutputBuffer(idx)
                val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (buf != null && info.size > 0 && !config) writeSample(audio, buf, info)
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                enc.releaseOutputBuffer(idx, false)
                if (eos) return 2
            }
        }
    }

    private fun writeSample(audio: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val track = if (audio) aTrack else vTrack
        if (track < 0) return
        val pts = if (audio) info.presentationTimeUs else videoPts(info.presentationTimeUs)
        if (!audio) videoFrames++
        outBytes += info.size
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        if (muxStarted) {
            info.presentationTimeUs = pts
            muxer?.writeSampleData(track, buf, info)
        } else {
            val copy = ByteBuffer.allocate(info.size)
            copy.put(buf)
            copy.flip()
            val bi = MediaCodec.BufferInfo()
            bi.set(0, info.size, pts, info.flags)
            pending.add(Pending(track, copy, bi))
        }
    }

    private fun tryStartMuxer() {
        if (muxStarted || vTrack < 0) return
        if (audioActive && aTrack < 0) return
        val m = muxer ?: return
        m.start()
        muxStarted = true
        for (p in pending) m.writeSampleData(p.track, p.buf, p.info)
        pending.clear()
    }

    // ------------------------------------------------------------------ завершение

    /** Закрывает файл (вызывать из потока записи), возвращает число кадров видео. */
    fun finish(): Int {
        running = false
        try { paceThread?.join(2000) } catch (_: Exception) {}
        try { decodeThread?.join(500) } catch (_: Exception) {}
        ready.getAndSet(null)?.bmp?.recycle()
        synchronized(audioLock) {
            audioClosed = true
            val enc = audioEnc
            if (audioActive && audioStarted && enc != null) {
                try {
                    val i = enc.dequeueInputBuffer(100_000)
                    if (i >= 0) {
                        enc.queueInputBuffer(i, 0, 0, audioFramesFed * 1_000_000L / cfg.audioRate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else audioActive = false
                } catch (_: Exception) { audioActive = false }
            } else {
                audioActive = false
            }
        }
        try { videoEnc?.signalEndOfInputStream() } catch (_: Exception) {}
        closeT = System.nanoTime()
        closing = true
        try { drainThread?.join(10_000) } catch (_: Exception) {}

        val frames = videoFrames
        val started = muxStarted
        try { if (started) muxer?.stop() } catch (e: Exception) { log("muxer.stop: $e") }
        release()

        if (started && frames > 0) {
            val sec = frames / REC_FPS.toDouble()
            log("запись закончена: %d кадров (%.1f с, %d к/с), кадров камеры принято %d, из них в видео %d, повторов %d, пропущено тактов %d%s; поток кодировщика %.1f Мбит/с".format(
                frames, sec, REC_FPS, offered, fresh, dup, skippedTicks,
                if (audioDroppedFrames > 0) ", звук потерян %d мс".format(audioDroppedFrames * 1000 / cfg.audioRate) else "",
                outBytes * 8 / sec / 1e6))
            if (skippedTicks > 0) log("  пропуски тактов: телефон не успевает кодировать — снизь битрейт или выбери H.264")
        }
        return if (started) frames else 0
    }

    private fun release() {
        try { videoEnc?.stop() } catch (_: Exception) {}
        try { videoEnc?.release() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        try { audioEnc?.stop() } catch (_: Exception) {}
        try { audioEnc?.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        videoEnc = null; audioEnc = null; surface = null; muxer = null
    }
}
