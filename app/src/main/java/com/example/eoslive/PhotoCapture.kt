package com.example.eoslive

import java.io.ByteArrayOutputStream

class PhotoFile(val name: String, val data: ByteArray)

/**
 * Снимок по PTP: выйти из Live View, спустить затвор, дождаться файла и скачать его на телефон.
 * Live View после снимка включается заново. Вызывать из потока камеры (внутри очереди команд).
 */
class PhotoCapture(
    private val p: PtpUsb,
    private val eos: EosState,
    private val log: (String) -> Unit,
    private val progress: (String) -> Unit,
    private val onProps: () -> Unit
) {
    companion object {
        private const val CHUNK = 0x100000
    }

    /** Причина неудачи (для сообщения пользователю), если снимок не получен. */
    var error: String? = null
        private set

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** Читает события камеры и ждёт указанное время. */
    private fun pump(ms: Long) {
        val end = System.nanoTime() + ms * 1_000_000L
        do {
            val r = p.transact(0x9116)
            if (r != null && eos.parse(r.data)) onProps()
            Thread.sleep(100)
        } while (System.nanoTime() < end)
    }

    fun shoot(expSec: Double): List<PhotoFile> {
        val files = ArrayList<PhotoFile>()
        try {
            // 1. выйти из Live View: зеркало должно опуститься, иначе камера занята
            p.op("TerminateViewfinder", 0x9152)
            p.setProp("EVFOutputDevice=0", 0xD1B0, 0)
            pump(700)

            // 2. сохранять снимок в память камеры для компьютера (то есть передавать на телефон)
            val lst = eos.list(0xD11C)
            log("CaptureDestination: сейчас=" + (eos.value(0xD11C)?.let { "0x%X".format(it) } ?: "?") +
                ", допустимо=" + (lst?.joinToString { "0x%X".format(it) } ?: "нет данных"))
            p.setProp("CaptureDestination=телефон", 0xD11C, 4)
            pump(200)

            // 3. спуск затвора
            val rel = p.op("RemoteRelease (спуск затвора)", 0x910F)
            if (rel == null || rel.code != 0x2001) {
                error = if (rel == null) "камера не ответила на спуск (${p.lastError})"
                else "камера не приняла спуск, код 0x%04X (нет карты, объектив на MF, режим «Bulb» или камера занята)".format(rel.code)
                return files
            }
            progress("Экспозиция %.1f с…".format(expSec))

            // 4. ждём событие «файл готов»
            val objs = ArrayList<EosObj>()
            val deadline = System.nanoTime() + ((expSec + 30.0) * 1e9).toLong()
            var firstT = 0L
            var nulls = 0
            var keepT = System.nanoTime()
            val seen = HashSet<String>()
            while (System.nanoTime() < deadline) {
                val ev = p.transact(0x9116)
                if (ev == null) {
                    nulls++
                    if (nulls > 15) { error = "камера перестала отвечать"; break }
                    p.recover()
                    Thread.sleep(200)
                    continue
                }
                nulls = 0
                val d = ev.data
                if (eos.parse(d)) onProps()
                for (o in EosEvents.objects(d)) {
                    if (objs.none { it.oid == o.oid }) {
                        objs.add(o)
                        if (firstT == 0L) firstT = System.nanoTime()
                        log("камера сообщила о файле: имя «${o.name}», размер из события ${o.sizeHint}, " +
                            (if (o.transfer) "забрать (0xC186)" else "на карте (0xC181)") + ", запись: " +
                            o.raw.take(64).joinToString(" ") { "%02X".format(it) })
                    }
                }
                val other = EosEvents.otherTypes(d)
                if (other.isNotEmpty() && seen.add(other)) log("события камеры:$other")
                if (objs.isNotEmpty() && System.nanoTime() - firstT > 1_200_000_000L) break // RAW+JPEG: ждём второй файл
                val now = System.nanoTime()
                if (now - keepT > 3_000_000_000L) { p.transact(0x911D); keepT = System.nanoTime() }
                Thread.sleep(120)
            }
            if (objs.isEmpty()) {
                if (error == null) error = "камера не прислала файл за %.0f с (смотри лог)".format(expSec + 30.0)
                return files
            }

            // 5. скачиваем
            for (o in objs) {
                val data = download(o)
                if (o.transfer) p.transact(0x9117, intArrayOf(o.oid)) // TransferComplete: камера освобождает буфер
                if (data != null && data.isNotEmpty()) {
                    val n = if (o.name.isNotEmpty()) o.name else "IMG_%08X.JPG".format(o.oid)
                    files.add(PhotoFile(n, data))
                }
            }
            if (files.isEmpty() && error == null) error = "файл не удалось скачать (смотри лог)"
        } finally {
            // вернуть Live View
            try {
                p.setProp("EVFMode=1", 0xD1B1, 1)
                p.setProp("EVFOutputDevice=PC", 0xD1B0, 2)
                pump(300)
            } catch (_: Exception) {}
        }
        return files
    }

    private fun plausible(n: Int) = n in 20_000..300_000_000

    /** Размер файла: сначала стандартным GetObjectInfo, потом из записи события. 0 — неизвестен. */
    private fun objectSize(o: EosObj): Int {
        val r = p.transact(0x1008, intArrayOf(o.oid))
        val d = r?.data
        if (r != null && r.code == 0x2001 && d != null && d.size >= 12) {
            val s = le32(d, 8)
            if (plausible(s)) return s
        }
        return o.sizeHint
    }

    private fun endsWithEoi(d: ByteArray): Boolean =
        d.size > 2 && d[d.size - 2] == 0xFF.toByte() && d[d.size - 1] == 0xD9.toByte()

    private fun download(o: EosObj): ByteArray? {
        val size = objectSize(o)
        log("скачиваю «${o.name}»: размер " + (if (size > 0) "$size Б" else "неизвестен"))
        val label = if (o.name.isNotEmpty()) o.name else "файл"
        val out = ByteArrayOutputStream(if (size > 0) size else 4_000_000)
        val t0 = System.nanoTime()
        var off = 0
        while (true) {
            val want = if (size > 0) minOf(CHUNK, size - off) else CHUNK
            if (want <= 0) break
            val r = p.transact(0x9107, intArrayOf(o.oid, off, want)) // GetPartialObject
            val d = r?.data
            if (r == null || r.code != 0x2001 || d == null) {
                log("GetPartialObject: " + (if (r == null) p.lastError else "код 0x%04X".format(r.code)))
                if (out.size() == 0) { error = "камера не отдала файл"; return null }
                break
            }
            if (d.isEmpty()) break
            out.write(d)
            off += d.size
            progress("Получаю $label: " + (if (size > 0) "${off * 100L / size}%" else "${off / 1024} КБ"))
            if (d.size < want) break
        }
        var data = out.toByteArray()
        // JPEG должен кончаться маркером FFD9; если нет — размер определён неверно, докачиваем
        if (PhotoUtil.isJpegName(o.name) && data.isNotEmpty() && !endsWithEoi(data)) {
            var tries = 0
            while (!endsWithEoi(data) && tries < 40) {
                tries++
                val r = p.transact(0x9107, intArrayOf(o.oid, data.size, CHUNK))
                val d = r?.data
                if (r == null || r.code != 0x2001 || d == null || d.isEmpty()) break
                data += d
                if (d.size < CHUNK) break
            }
        }
        val sec = (System.nanoTime() - t0) / 1e9
        log("получено «${o.name}»: ${data.size} Б за %.1f с (%.1f МБ/с)".format(sec, data.size / 1e6 / maxOf(sec, 0.001)))
        return data
    }
}
