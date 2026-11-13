package com.example.eoslive

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    companion object {
        const val CANON_VID = 0x04A9
        const val ACTION_PERM = "com.example.eoslive.USB_PERMISSION"
    }

    private lateinit var usb: UsbManager
    private lateinit var img: ImageView
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnLive: Button
    private lateinit var btnRec: Button

    @Volatile private var running = false
    @Volatile private var recording = false
    private val uiBusy = AtomicBoolean(false)
    private val logBuf = StringBuilder()

    // трогаются только из рабочего потока
    private var writer: MjpegAviWriter? = null
    private var tmpFile: File? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != ACTION_PERM) return
            @Suppress("DEPRECATION")
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                startWorker(dev)
            } else {
                log("Доступ к USB не выдан")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        log("Готово. Подключи 40D по USB (через OTG) и нажми «Старт Live View».")
    }

    override fun onDestroy() {
        running = false
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnLive = Button(this).apply {
            text = "Старт Live View"
            setOnClickListener { onLiveClick() }
        }
        btnRec = Button(this).apply {
            text = "● Запись"
            isEnabled = false
            setOnClickListener { onRecClick() }
        }
        row.addView(btnLive, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btnRec, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        img = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        status = TextView(this).apply { text = "—" }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        scroll = ScrollView(this).apply { addView(logView) }
        val btnCopy = Button(this).apply {
            text = "Копировать лог"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", synchronized(logBuf) { logBuf.toString() }))
                Toast.makeText(this@MainActivity, "Лог скопирован", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(row)
        root.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btnCopy)
        setContentView(root)
    }

    private fun log(s: String) {
        val line = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + s
        val text = synchronized(logBuf) {
            logBuf.append(line).append('\n')
            if (logBuf.length > 20000) logBuf.delete(0, logBuf.length - 15000)
            logBuf.toString()
        }
        runOnUiThread {
            logView.text = text
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun onLiveClick() {
        if (running) { running = false; return }
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == CANON_VID }
        if (dev == null) {
            val all = usb.deviceList.values.joinToString { "%04x:%04x".format(it.vendorId, it.productId) }
            log("Canon не найден. USB-устройства: " + (if (all.isEmpty()) "нет" else all))
            return
        }
        log("Найдено: ${dev.productName ?: "?"} " + "%04x:%04x".format(dev.vendorId, dev.productId))
        if (usb.hasPermission(dev)) {
            startWorker(dev)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags)
            usb.requestPermission(dev, pi)
        }
    }

    private fun onRecClick() {
        recording = !recording
        btnRec.text = if (recording) "■ Стоп записи" else "● Запись"
    }

    private fun startWorker(dev: UsbDevice) {
        if (running) return
        running = true
        runOnUiThread {
            btnLive.text = "Стоп Live View"
            btnRec.isEnabled = true
        }
        Thread { runLive(dev) }.start()
    }

    private fun runLive(dev: UsbDevice) {
        var conn: UsbDeviceConnection? = null
        var ptp: PtpUsb? = null
        var itfClaimed: UsbInterface? = null
        try {
            conn = usb.openDevice(dev)
            if (conn == null) { log("openDevice вернул null"); return }

            var itf: UsbInterface? = null
            var inE: UsbEndpoint? = null
            var outE: UsbEndpoint? = null
            for (i in 0 until dev.interfaceCount) {
                val c = dev.getInterface(i)
                log("интерфейс $i: класс ${c.interfaceClass}, эндпоинтов ${c.endpointCount}")
                var ei: UsbEndpoint? = null
                var eo: UsbEndpoint? = null
                for (e in 0 until c.endpointCount) {
                    val ep = c.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) ei = ep else eo = ep
                    }
                }
                if (c.interfaceClass == 6 && ei != null && eo != null) {
                    itf = c; inE = ei; outE = eo
                    break
                }
            }
            if (itf == null || inE == null || outE == null) { log("PTP-интерфейс не найден"); return }
            if (!conn.claimInterface(itf, true)) { log("claimInterface не удался"); return }
            itfClaimed = itf

            val p = PtpUsb(conn, inE, outE) { log(it) }
            ptp = p

            p.op("OpenSession", 0x1002, intArrayOf(1))
            p.op("SetRemoteMode(1)", 0x9114, intArrayOf(1))
            p.op("SetEventMode(1)", 0x9115, intArrayOf(1))
            p.op("GetEvent", 0x9116)
            p.setProp("EVFMode=1", 0xD1B1, 1)
            p.setProp("EVFOutputDevice=PC", 0xD1B0, 2)
            p.op("InitiateViewfinder", 0x9151)
            Thread.sleep(500)
            p.op("GetEvent", 0x9116)

            var useParam = true
            var noReply = 0
            var notOk = 0
            var okFrames = 0L
            var secFrames = 0
            var secT = System.nanoTime()
            var firstLogged = false

            while (running) {
                val r = p.getViewfinder(useParam)
                if (r == null) {
                    noReply++
                    if (noReply > 5) { log("камера не отвечает, стоп"); break }
                    continue
                }
                noReply = 0
                val data = r.data
                if (r.code == 0x2001 && data != null) {
                    notOk = 0
                    if (!firstLogged) {
                        firstLogged = true
                        val head = data.take(16).joinToString(" ") { "%02X".format(it) }
                        log("первый кадр: ${data.size} Б, начало: $head")
                    }
                    val jpg = Jpeg.extract(data)
                    if (jpg == null) {
                        if (okFrames == 0L) log("в данных нет JPEG")
                    } else {
                        okFrames++
                        secFrames++
                        handleFrame(jpg)
                    }
                } else {
                    notOk++
                    if (notOk <= 5 || notOk % 20 == 0) log("GetViewFinderData -> 0x%04X (подряд: $notOk)".format(r.code))
                    if (notOk == 5 && useParam) { useParam = false; log("пробую без параметра") }
                    if (notOk > 100) { log("Live View не запустился, стоп"); break }
                    Thread.sleep(100)
                }
                if (okFrames > 0 && okFrames % 40 == 0L) p.transact(0x9116)

                val now = System.nanoTime()
                if (now - secT >= 1_000_000_000L) {
                    val fps = secFrames * 1e9 / (now - secT)
                    secFrames = 0; secT = now
                    val rec = if (writer != null) " ● идёт запись" else ""
                    runOnUiThread { status.text = "%.1f к/с, кадров всего: %d%s".format(fps, okFrames, rec) }
                }
            }
        } catch (e: Exception) {
            log("ошибка: $e")
        } finally {
            try { if (writer != null) finishRecording() } catch (e: Exception) { log("ошибка сохранения: $e") }
            try {
                ptp?.op("TerminateViewfinder", 0x9152)
                ptp?.setProp("EVFOutputDevice=0", 0xD1B0, 0)
                ptp?.op("SetRemoteMode(0)", 0x9114, intArrayOf(0))
                ptp?.op("CloseSession", 0x1003)
            } catch (_: Exception) {}
            try { if (itfClaimed != null) conn?.releaseInterface(itfClaimed) } catch (_: Exception) {}
            try { conn?.close() } catch (_: Exception) {}
            running = false
            recording = false
            log("Live View остановлен")
            runOnUiThread {
                btnLive.text = "Старт Live View"
                btnRec.text = "● Запись"
                btnRec.isEnabled = false
            }
        }
    }

    private fun handleFrame(jpg: ByteArray) {
        if (recording && writer == null) {
            val f = File(cacheDir, "rec.avi")
            tmpFile = f
            writer = MjpegAviWriter(f)
            log("запись началась")
        } else if (!recording && writer != null) {
            finishRecording()
        }
        writer?.addFrame(jpg)

        if (uiBusy.compareAndSet(false, true)) {
            val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
            runOnUiThread {
                if (bmp != null) img.setImageBitmap(bmp)
                uiBusy.set(false)
            }
        }
    }

    private fun finishRecording() {
        val w = writer ?: return
        writer = null
        val n = w.finish()
        val f = tmpFile ?: return
        if (n == 0) { log("запись пустая"); f.delete(); return }
        val name = "eos_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".avi"
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/x-msvideo")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EOSLive")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
        if (uri == null) { log("не удалось создать файл в Movies"); return }
        contentResolver.openOutputStream(uri)?.use { out ->
            f.inputStream().use { it.copyTo(out) }
        }
        f.delete()
        log("сохранено: Movies/EOSLive/$name, кадров: $n")
    }
}
