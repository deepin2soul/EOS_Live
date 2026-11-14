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
 * Тёмный кадр: средний кадр Live View с закрытым объективом (в 1/16 уровня на канал).
 * Содержит постоянный «рисунок» матрицы (вертикальные полосы), который вычитается из каждого кадра суммирования.
 */
class DarkFrame(val w: Int, val h: Int, val q: IntArray) {
    /** Средний уровень 0..255 (для проверки, что объектив был закрыт). */
    fun meanLevel(): Double {
        var s = 0L
        for (v in q) s += v
        return s / 16.0 / q.size
    }
}

/**
 * Длинная выдержка на телефоне: суммирует кадры Live View, которые и так приходят от камеры.
 *  - Сумма: яркости складываются (как настоящая длинная выдержка), светлые места пересвечиваются до белого.
 *  - Среднее: кадры усредняются (убирает шум, размывает движение, яркость НЕ растёт).
 *  - Максимум: остаётся самый яркий пиксель за всё время (трассы звёзд, огней), яркость тоже не растёт.
 * Яркость результата умножается на [gain] (0 = подобрать автоматически), можно менять в процессе.
 * Размер кадра фиксируется по первому кадру; кадры другого размера (после смены зума камеры) пропускаются.
 * Всё считается в отдельном потоке, поток камеры только кладёт кадр в очередь.
 */
class LongExposure(
    private val method: StackMethod,
    private val dark: DarkFrame?,
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
    /** true, если тёмный кадр подошёл по размеру и вычитался. */
    @Volatile var darkUsed = false
        private set
    /** Множитель яркости результата; 0 или меньше — автоматически. */
    @Volatile var gain = 1f
    @Volatile private var result: Bitmap? = null
    private var thread: Thread? = null

    private var w = 0
    private var h = 0
    /** Накопленные значения в 1/16 уровня на канал: R, G, B подряд для каждого пикселя. */
    private var acc: IntArray? = null
    private var pix: IntArray? = null
    private var darkQ: IntArray? = null
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

    /** Для тёмного кадра: среднее по всем кадрам (вызывать после finish, метод должен быть «Среднее»). */
    fun darkMaster(): DarkFrame? {
        val a = acc ?: return null
        val n = frames
        if (n == 0) return null
        return DarkFrame(w, h, IntArray(a.size) { a[it] / n })
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
                if (dark != null) {
                    if (dark.w == w && dark.h == h) {
                        darkQ = dark.q
                        darkUsed = true
                    } else {
                        log("тёмный кадр ${dark.w}x${dark.h} не подходит к кадрам ${w}x$h, вычитание отключено")
                    }
                }
            } else if (bmp.width != w || bmp.height != h) {
                rejected++
                return
            }
            val a = acc ?: return
            val p = pix ?: return
            val dq = darkQ
            bmp.getPixels(p, 0, w, 0, 0, w, h)
            val n = w * h
            val isMax = method == StackMethod.MAX
            var j = 0
            for (i in 0 until n) {
                val c = p[i]
                var r = ((c shr 16) and 255) shl 4
                var g = ((c shr 8) and 255) shl 4
                var b = (c and 255) shl 4
                if (dq != null) {
                    r -= dq[j]; g -= dq[j + 1]; b -= dq[j + 2]
                    if (r < 0) r = 0
                    if (g < 0) g = 0
                    if (b < 0) b = 0
                }
                if (isMax) {
                    if (r > a[j]) a[j] = r
                    if (g > a[j + 1]) a[j + 1] = g
                    if (b > a[j + 2]) a[j + 2] = b
                } else {
                    a[j] += r
                    a[j + 1] += g
                    a[j + 2] += b
                }
                j += 3
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
        val cnt = maxOf(frames, 1)
        val u = 1f / 16f
        // «единица»: во сколько раз надо умножить накопленное, чтобы получить уровень 0..255 без усиления
        // (сумма — как есть, среднее — делим на число кадров, максимум — как есть)
        val unit = if (method == StackMethod.SUM || method == StackMethod.MAX) u else u / cnt
        val g = gain
        val kk: Float
        if (g > 0f) {
            kk = g * unit
        } else {
            // авто: яркие 99,5% пикселей подтягиваем примерно к 235
            val norm = if (method == StackMethod.SUM) u / cnt else unit
            val hist = IntArray(256)
            var j = 0
            for (i in 0 until n) {
                var m = a[j]
                if (a[j + 1] > m) m = a[j + 1]
                if (a[j + 2] > m) m = a[j + 2]
                val v = (m * norm).toInt()
                hist[if (v > 255) 255 else v]++
                j += 3
            }
            var cum = 0
            var p = 255
            val lim = (n * 0.995).toInt()
            for (k in 0..255) { cum += hist[k]; if (cum >= lim) { p = k; break } }
            val f = (235f / maxOf(p, 1)).coerceIn(0.5f, 64f)
            kk = f * norm
        }
        val out = IntArray(n)
        var j = 0
        for (i in 0 until n) {
            var r = (a[j] * kk + 0.5f).toInt()
            var gg = (a[j + 1] * kk + 0.5f).toInt()
            var b = (a[j + 2] * kk + 0.5f).toInt()
            if (r > 255) r = 255
            if (gg > 255) gg = 255
            if (b > 255) b = 255
            out[i] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
            j += 3
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
