package com.example.eoslive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

enum class StackMethod(val label: String) {
    SUM("Сумма"),
    AVG("Среднее"),
    MAX("Максимум")
}

/**
 * Длинная выдержка на телефоне: суммирует кадры Live View, которые и так приходят от камеры.
 *  - Сумма: яркости складываются (как настоящая длинная выдержка), светлые места пересвечиваются до белого.
 *  - Среднее: кадры усредняются (убирает шум, размывает движение, яркость не растёт).
 *  - Максимум: остаётся самый яркий пиксель за всё время (трассы звёзд, огней).
 * Размер кадра фиксируется по первому кадру; кадры другого размера (после смены зума камеры) пропускаются.
 * Всё считается в отдельном потоке, поток камеры только кладёт кадр в очередь.
 */
class LongExposure(
    private val method: StackMethod,
    private val log: (String) -> Unit,
    /** Вызывается из потока суммирования примерно 5 раз в секунду с текущим результатом. */
    private val onPreview: (Bitmap) -> Unit
) {
    private val queue = ArrayBlockingQueue<ByteArray>(6)
    @Volatile private var stop = false
    @Volatile var frames = 0
        private set
    /** Кадры, которые не успели обработать (очередь была полна). */
    @Volatile var dropped = 0
        private set
    /** Кадры другого размера, чем первый. */
    @Volatile var rejected = 0
        private set
    @Volatile private var result: Bitmap? = null
    private var thread: Thread? = null

    private var w = 0
    private var h = 0
    private var acc: IntArray? = null
    private var pix: IntArray? = null
    private var lastPreviewT = 0L

    fun start() {
        thread = Thread({ loop() }, "stack").also { it.isDaemon = true; it.start() }
    }

    /** Из потока камеры: не блокируется. */
    fun offer(jpg: ByteArray) {
        if (stop) return
        if (!queue.offer(jpg)) dropped++
    }

    /** Останавливает, дожидается хвоста очереди и возвращает итоговую картинку (null, если кадров не было). */
    fun finish(): Bitmap? {
        stop = true
        try { thread?.join(8000) } catch (_: Exception) {}
        return result
    }

    private fun loop() {
        try {
            while (true) {
                val jpg = queue.poll(100, TimeUnit.MILLISECONDS)
                if (jpg == null) {
                    if (stop) break else continue
                }
                addFrame(jpg)
            }
            result = render()
        } catch (e: Throwable) {
            log("суммирование: ошибка $e")
            try { result = render() } catch (_: Throwable) {}
        }
    }

    private fun addFrame(jpg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: return
        try {
            if (acc == null) {
                w = bmp.width
                h = bmp.height
                acc = IntArray(w * h * 3)
                pix = IntArray(w * h)
            } else if (bmp.width != w || bmp.height != h) {
                rejected++
                return
            }
            val a = acc ?: return
            val p = pix ?: return
            bmp.getPixels(p, 0, w, 0, 0, w, h)
            val n = w * h
            var j = 0
            if (method == StackMethod.MAX) {
                for (i in 0 until n) {
                    val c = p[i]
                    val r = (c shr 16) and 255
                    val g = (c shr 8) and 255
                    val b = c and 255
                    if (r > a[j]) a[j] = r
                    if (g > a[j + 1]) a[j + 1] = g
                    if (b > a[j + 2]) a[j + 2] = b
                    j += 3
                }
            } else {
                for (i in 0 until n) {
                    val c = p[i]
                    a[j] += (c shr 16) and 255
                    a[j + 1] += (c shr 8) and 255
                    a[j + 2] += c and 255
                    j += 3
                }
            }
            frames++
        } finally {
            bmp.recycle()
        }
        val now = System.nanoTime()
        if (frames == 1 || now - lastPreviewT > 200_000_000L) {
            lastPreviewT = now
            render()?.let { onPreview(it) }
        }
    }

    private fun render(): Bitmap? {
        val a = acc ?: return null
        val n = w * h
        val out = IntArray(n)
        val cnt = maxOf(frames, 1)
        var j = 0
        when (method) {
            StackMethod.SUM -> for (i in 0 until n) {
                val r = if (a[j] > 255) 255 else a[j]
                val g = if (a[j + 1] > 255) 255 else a[j + 1]
                val b = if (a[j + 2] > 255) 255 else a[j + 2]
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                j += 3
            }
            StackMethod.AVG -> for (i in 0 until n) {
                out[i] = (0xFF shl 24) or ((a[j] / cnt) shl 16) or ((a[j + 1] / cnt) shl 8) or (a[j + 2] / cnt)
                j += 3
            }
            StackMethod.MAX -> for (i in 0 until n) {
                out[i] = (0xFF shl 24) or (a[j] shl 16) or (a[j + 1] shl 8) or a[j + 2]
                j += 3
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
