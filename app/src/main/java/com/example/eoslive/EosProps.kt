package com.example.eoslive

/** Свойства Canon EOS (коды из libgphoto2 / EDSDK). */
object EosProp {
    const val AV = 0xD101
    const val TV = 0xD102
    const val ISO = 0xD103
    const val EXPCOMP = 0xD104
    const val WB = 0xD109
}

/**
 * Текущие значения свойств и списки допустимых значений.
 * Всё приходит от камеры в событиях GetEvent (0x9116):
 * 0xC189 = значение изменилось, 0xC18A = изменился список допустимых значений.
 */
class EosState {
    private val values = HashMap<Int, Int>()
    private val avail = HashMap<Int, IntArray>()

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    @Synchronized fun clear() { values.clear(); avail.clear() }
    @Synchronized fun value(p: Int): Int? = values[p]
    @Synchronized fun list(p: Int): IntArray? = avail[p]

    /** Разбирает данные GetEvent. Возвращает true, если что-то изменилось. */
    @Synchronized fun parse(d: ByteArray?): Boolean {
        if (d == null) return false
        var off = 0
        var changed = false
        while (off + 8 <= d.size) {
            val size = le32(d, off)
            val type = le32(d, off + 4)
            if (size < 8 || off + size > d.size) break
            when (type) {
                0xC189 -> if (size >= 16) {
                    val prop = le32(d, off + 8)
                    val v = le32(d, off + 12)
                    if (values[prop] != v) { values[prop] = v; changed = true }
                }
                0xC18A -> if (size >= 20) {
                    val prop = le32(d, off + 8)
                    val cnt = le32(d, off + 16)
                    if (cnt in 1..512) {
                        val stride = (size - 20) / cnt
                        if (stride == 1 || stride == 2 || stride == 4) {
                            val arr = IntArray(cnt) { i ->
                                val o = off + 20 + i * stride
                                when (stride) {
                                    1 -> d[o].toInt() and 0xFF
                                    2 -> (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
                                    else -> le32(d, o)
                                }
                            }
                            avail[prop] = arr
                            changed = true
                        }
                    }
                }
            }
            off += size
        }
        return changed
    }
}

/** Человекочитаемые подписи значений. */
object EosFmt {
    private val iso = mapOf(
        0x00 to "Auto", 0x40 to "50", 0x48 to "100", 0x4B to "125", 0x4D to "160", 0x50 to "200",
        0x53 to "250", 0x55 to "320", 0x58 to "400", 0x5B to "500", 0x5D to "640", 0x60 to "800",
        0x63 to "1000", 0x65 to "1250", 0x68 to "1600", 0x6B to "2000", 0x6D to "2500", 0x70 to "3200",
        0x73 to "4000", 0x75 to "5000", 0x78 to "6400", 0x7B to "8000", 0x7D to "10000", 0x80 to "12800",
        0x88 to "25600", 0x90 to "51200", 0x98 to "102400"
    )

    private val av = mapOf(
        0x08 to "1.0", 0x0B to "1.1", 0x0C to "1.2", 0x0D to "1.2", 0x10 to "1.4", 0x13 to "1.6",
        0x14 to "1.8", 0x15 to "1.8", 0x18 to "2.0", 0x1B to "2.2", 0x1C to "2.5", 0x1D to "2.5",
        0x20 to "2.8", 0x23 to "3.2", 0x24 to "3.5", 0x25 to "3.5", 0x28 to "4.0", 0x2B to "4.5",
        0x2C to "4.5", 0x2D to "5.0", 0x30 to "5.6", 0x33 to "6.3", 0x34 to "6.7", 0x35 to "7.1",
        0x38 to "8.0", 0x3B to "9.0", 0x3C to "9.5", 0x3D to "10", 0x40 to "11", 0x43 to "13",
        0x44 to "13", 0x45 to "14", 0x48 to "16", 0x4B to "18", 0x4C to "19", 0x4D to "20",
        0x50 to "22", 0x53 to "25", 0x54 to "27", 0x55 to "29", 0x58 to "32"
    )

    private val wb = mapOf(
        0 to "Авто", 1 to "Солнце", 2 to "Облачно", 3 to "Лампа накал.", 4 to "Люминесц.",
        5 to "Вспышка", 6 to "Ручной", 8 to "Тень", 9 to "Цв. темп.", 10 to "ПК WB 1",
        11 to "ПК WB 2", 12 to "ПК WB 3"
    )

    private val tvSlow = doubleArrayOf(30.0, 25.0, 20.0, 15.0, 13.0, 10.0, 8.0, 6.0, 5.0, 4.0, 3.2, 2.5, 2.0, 1.6, 1.3, 1.0, 0.8, 0.6, 0.5, 0.4, 0.3)
    private val tvFast = intArrayOf(
        2, 3, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 125, 160, 200, 250, 320,
        400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000
    )

    private fun tv(code: Int): String {
        if (code == 0x0C) return "Bulb"
        val t = Math.pow(2.0, (0x38 - code) / 8.0)
        return if (t >= 0.28) {
            val n = tvSlow.minByOrNull { Math.abs(it - t) } ?: t
            if (n == Math.floor(n)) "${n.toInt()}\"" else "$n\""
        } else {
            val den = 1.0 / t
            "1/" + (tvFast.minByOrNull { Math.abs(it - den) } ?: den.toInt())
        }
    }

    private fun comp(code: Int): String {
        val ev = (code and 0xFF).toByte().toInt() / 8.0
        val r = Math.round(ev * 3) / 3.0
        return if (r == 0.0) "0" else "%+.1f".format(r)
    }

    /** Выдержка в секундах по коду Tv (для Bulb не использовать). */
    fun seconds(code: Int): Double = Math.pow(2.0, (0x38 - code) / 8.0)

    fun label(prop: Int, v: Int): String = when (prop) {
        EosProp.ISO -> iso[v] ?: "0x%02X".format(v)
        EosProp.AV -> av[v] ?: "f/%.1f".format(Math.pow(2.0, (v - 8) / 16.0))
        EosProp.TV -> tv(v)
        EosProp.EXPCOMP -> comp(v)
        EosProp.WB -> wb[v] ?: "WB $v"
        else -> "0x%X".format(v)
    }
}

/** Файл, о котором камера сообщила в событии (после снимка). */
class EosObj(
    val oid: Int,
    val name: String,
    /** Размер из записи события, 0 — если определить не удалось. */
    val sizeHint: Int,
    /** true — камера просит забрать файл (0xC186, сохранение в память камеры для ПК), false — файл на карте (0xC181). */
    val transfer: Boolean,
    val raw: ByteArray
)

object EosEvents {
    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun plausible(n: Int) = n in 20_000..300_000_000

    private val nameRe = Regex("[A-Za-z0-9_\\-]{1,16}\\.(JPG|JPEG|CR2|CRW|THM)", RegexOption.IGNORE_CASE)

    /** Записи «появился файл» (0xC181) и «забери файл» (0xC186). Размер и имя ищутся с запасом на разные варианты формата. */
    fun objects(d: ByteArray?): List<EosObj> {
        val res = ArrayList<EosObj>()
        if (d == null) return res
        var off = 0
        while (off + 8 <= d.size) {
            val size = le32(d, off)
            val type = le32(d, off + 4)
            if (size < 8 || off + size > d.size) break
            if ((type == 0xC181 || type == 0xC186) && size >= 0x20) {
                val transfer = type == 0xC186
                val s14 = if (size >= 0x18) le32(d, off + 0x14) else 0
                val s1c = if (size >= 0x20) le32(d, off + 0x1c) else 0
                val hint = if (transfer) { if (plausible(s14)) s14 else if (plausible(s1c)) s1c else 0 }
                           else { if (plausible(s1c)) s1c else 0 }
                val sb = StringBuilder()
                for (k in 8 until size) {
                    val c = d[off + k].toInt() and 0xFF
                    sb.append(if (c in 32..126) c.toChar() else '\u0000')
                }
                val name = nameRe.find(sb)?.value ?: ""
                res.add(EosObj(le32(d, off + 8), name, hint, transfer, d.copyOfRange(off, off + size)))
            }
            off += size
        }
        return res
    }

    /** Типы событий, кроме изменений параметров: для лога. */
    fun otherTypes(d: ByteArray?): String {
        if (d == null) return ""
        val sb = StringBuilder()
        var off = 0
        while (off + 8 <= d.size) {
            val size = le32(d, off)
            val type = le32(d, off + 4)
            if (size < 8 || off + size > d.size) break
            if (type != 0xC189 && type != 0xC18A && type != 0) sb.append(" 0x%X(%d Б)".format(type, size))
            off += size
        }
        return sb.toString()
    }
}
