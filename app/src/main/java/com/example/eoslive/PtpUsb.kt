package com.example.eoslive

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.ByteArrayOutputStream

class PtpResult(val code: Int, val data: ByteArray?)

/**
 * Минимальный PTP поверх USB bulk-эндпоинтов (Android USB Host API).
 * Контейнеры: 1 = команда, 2 = данные, 3 = ответ.
 */
class PtpUsb(
    private val conn: UsbDeviceConnection,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
    private val ifaceId: Int,
    private val log: (String) -> Unit
) {
    private var tid = 0
    private val rbuf = ByteArray(16384)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
        b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
    }

    private fun send(bytes: ByteArray): Boolean {
        var off = 0
        while (off < bytes.size) {
            val n = minOf(16384, bytes.size - off)
            val w = conn.bulkTransfer(outEp, bytes, off, n, 3000)
            if (w <= 0) return false
            off += w
        }
        return true
    }

    private fun readChunk(): ByteArray? {
        repeat(3) {
            val n = conn.bulkTransfer(inEp, rbuf, rbuf.size, 3000)
            if (n > 0) return rbuf.copyOf(n)
            if (n < 0) return null
        }
        return null
    }

    fun transact(code: Int, params: IntArray = IntArray(0), dataOut: ByteArray? = null): PtpResult? {
        val myTid = tid++
        val cmd = ByteArray(12 + 4 * params.size)
        put32(cmd, 0, cmd.size); put16(cmd, 4, 1); put16(cmd, 6, code); put32(cmd, 8, myTid)
        for (i in params.indices) put32(cmd, 12 + 4 * i, params[i])
        if (!send(cmd)) return null

        if (dataOut != null) {
            val d = ByteArray(12 + dataOut.size)
            put32(d, 0, d.size); put16(d, 4, 2); put16(d, 6, code); put32(d, 8, myTid)
            System.arraycopy(dataOut, 0, d, 12, dataOut.size)
            if (!send(d)) return null
        }

        var data: ByteArray? = null
        var first = readChunk() ?: return null
        var type = le16(first, 4)
        if (type == 2) {
            var total = le32(first, 0)
            if (total < 12) total = 12
            val need = total - 12
            val out = ByteArrayOutputStream(if (need > 0) need else 16)
            out.write(first, 12, first.size - 12)
            while (out.size() < need) {
                val c = readChunk() ?: return null
                out.write(c, 0, c.size)
            }
            data = out.toByteArray()
            first = readChunk() ?: return null
            type = le16(first, 4)
        }
        if (type != 3) {
            log("неожиданный контейнер type=$type")
            return null
        }
        return PtpResult(le16(first, 6), data)
    }

    /** Вызов с записью результата в лог (для редких команд, не для каждого кадра). */
    fun op(name: String, code: Int, params: IntArray = IntArray(0), dataOut: ByteArray? = null): PtpResult? {
        val r = transact(code, params, dataOut)
        val rc = if (r == null) "нет ответа" else "0x%04X".format(r.code)
        val extra = if (r?.data != null) ", данных ${r.data.size} Б" else ""
        log("$name -> $rc$extra")
        return r
    }

    /** Canon EOS SetDevicePropValueEx: данные = [size=12][prop][value]. */
    fun setProp(name: String, prop: Int, value: Int): PtpResult? {
        val d = ByteArray(12)
        put32(d, 0, 12); put32(d, 4, prop); put32(d, 8, value)
        return op("SetProp $name", 0x9110, dataOut = d)
    }

    /** Параметр 0x00100000 нужен всегда (так делает libgphoto2); без него камера «залипает». */
    fun getViewfinder(): PtpResult? = transact(0x9153, intArrayOf(0x00100000))

    /**
     * Вывести канал из зависшей транзакции: PTP Cancel (class request 0x64),
     * сброс STALL на обоих bulk-эндпоинтах и слив остатков из IN.
     */
    fun recover() {
        try {
            val c = ByteArray(6)
            put16(c, 0, 0x4001); put32(c, 2, tid - 1)
            conn.controlTransfer(0x21, 0x64, 0, ifaceId, c, c.size, 1000)
            conn.controlTransfer(0x02, 0x01, 0, inEp.address, null, 0, 1000)
            conn.controlTransfer(0x02, 0x01, 0, outEp.address, null, 0, 1000)
            repeat(8) { if (conn.bulkTransfer(inEp, rbuf, rbuf.size, 200) <= 0) return }
        } catch (_: Exception) {}
    }
}
