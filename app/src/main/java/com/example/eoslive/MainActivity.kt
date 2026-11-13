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
import android.content.pm.ActivityInfo
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    companion object {
        const val CANON_VID = 0x04A9
        const val ACTION_PERM = "com.example.eoslive.USB_PERMISSION"
    }

    private lateinit var usb: UsbManager
    private lateinit var root: LinearLayout
    private lateinit var topRow: LinearLayout
    private lateinit var zoomView: ZoomView
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnLive: Button
    private lateinit var btnRec: Button
    private lateinit var btnRecOv: Button
    private lateinit var btnFull: Button
    private lateinit var btnCopy: Button
    private lateinit var settingsRow: LinearLayout
    private lateinit var sliderBox: LinearLayout
    private lateinit var sliderLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var zoomRow: LinearLayout

    @Volatile private var running = false
    @Volatile private var recording = false
    private val uiBusy = AtomicBoolean(false)
    private val logBuf = StringBuilder()

    // трогаются только из рабочего потока
    private var writer: MjpegAviWriter? = null
    private var tmpFile: File? = null

    // параметры камеры
    private val eos = EosState()
    private val cmdQ = ConcurrentLinkedQueue<(PtpUsb) -> Unit>()
    private val props = listOf(
        EosProp.TV to "Выдержка",
        EosProp.AV to "Диафр.",
        EosProp.ISO to "ISO",
        EosProp.WB to "Баланс бел.",
        EosProp.EXPCOMP to "Эксп. комп."
    )
    private val propBtns = LinkedHashMap<Int, Button>()
    private var selProp = EosProp.ISO
    private var sliderList = IntArray(0)
    private var seeking = false
    private var fullscreen = false
    @Volatile private var lastFrameDims = ""

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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreen) setFullscreen(false) else super.onBackPressed()
    }

    private fun smallBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 16f
        minWidth = 0
        minimumWidth = 0
        alpha = 0.85f
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnLive = Button(this).apply {
            text = "Старт Live View"
            setOnClickListener { onLiveClick() }
        }
        btnRec = Button(this).apply {
            text = "● Запись"
            isEnabled = false
            setOnClickListener { onRecClick() }
        }
        topRow.addView(btnLive, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(btnRec, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // картинка + кнопки поверх неё (запись и полный экран)
        zoomView = ZoomView(this).apply { setBackgroundColor(Color.BLACK) }
        btnRecOv = smallBtn("●") { onRecClick() }.apply { isEnabled = false }
        btnFull = smallBtn("⛶") { setFullscreen(!fullscreen) }
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnRecOv)
            addView(btnFull)
        }
        val frame = FrameLayout(this).apply {
            addView(zoomView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }

        status = TextView(this).apply { text = "—" }

        // кнопки параметров
        settingsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((prop, name) in props) {
            val b = Button(this).apply {
                text = name
                textSize = 11f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(2, 2, 2, 2)
                setOnClickListener { selProp = prop; seeking = false; refreshSettingsUi() }
            }
            propBtns[prop] = b
            settingsRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // ползунок выбранного параметра
        sliderLabel = TextView(this).apply { text = "Параметры появятся после старта Live View" }
        seek = SeekBar(this).apply {
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val list = sliderList
                    if (p in list.indices) sliderLabel.text = nameOf(selProp) + ": " + EosFmt.label(selProp, list[p])
                }
                override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    seeking = false
                    applySlider(sb.progress)
                }
            })
        }
        sliderBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sliderLabel)
            addView(seek)
        }

        // зум самой камеры (5× и 10× как на корпусе)
        zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply { text = "Зум камеры:" })
            for (lvl in intArrayOf(1, 5, 10)) {
                addView(Button(this@MainActivity).apply {
                    text = if (lvl == 10) "×10*" else "×$lvl"
                    minWidth = 0
                    minimumWidth = 0
                    setOnClickListener { onCameraZoom(lvl) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        scroll = ScrollView(this).apply { addView(logView) }
        btnCopy = Button(this).apply {
            text = "Копировать лог"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", synchronized(logBuf) { logBuf.toString() }))
                Toast.makeText(this@MainActivity, "Лог скопирован", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(topRow)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f))
        root.addView(status)
        root.addView(settingsRow)
        root.addView(sliderBox)
        root.addView(zoomRow)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btnCopy)
        setContentView(root)
        refreshSettingsUi()
    }

    private fun nameOf(prop: Int): String = props.firstOrNull { it.first == prop }?.second ?: "?"

    private fun sortedList(prop: Int): IntArray {
        val l = eos.list(prop) ?: return IntArray(0)
        return when (prop) {
            EosProp.EXPCOMP -> l.distinct().sortedBy { (it and 0xFF).toByte().toInt() }.toIntArray()
            EosProp.WB -> l.distinct().toIntArray()
            else -> l.distinct().sorted().toIntArray()
        }
    }

    /** Обновить кнопки параметров и ползунок (только из UI-потока). */
    private fun refreshSettingsUi() {
        for ((prop, name) in props) {
            val b = propBtns[prop] ?: continue
            val v = eos.value(prop)
            b.text = name + "\n" + (if (v == null) "—" else EosFmt.label(prop, v))
            b.alpha = if (prop == selProp) 1f else 0.6f
        }
        if (seeking) return
        val cur = eos.value(selProp)
        val list = sortedList(selProp)
        sliderList = list
        if (cur == null && list.isEmpty()) {
            seek.isEnabled = false
            sliderLabel.text = nameOf(selProp) + ": нет данных (запусти Live View)"
            return
        }
        val curLabel = if (cur == null) "—" else EosFmt.label(selProp, cur)
        if (list.size < 2) {
            seek.isEnabled = false
            seek.max = 1
            seek.progress = 0
            sliderLabel.text = nameOf(selProp) + ": " + curLabel + " (выбора нет — смотри колесо режимов)"
            return
        }
        seek.isEnabled = true
        seek.max = list.size - 1
        val idx = list.indexOf(cur ?: -1)
        seek.progress = if (idx >= 0) idx else 0
        sliderLabel.text = nameOf(selProp) + ": " + curLabel
    }

    private fun applySlider(index: Int) {
        val list = sliderList
        if (index !in list.indices) return
        if (!running) {
            Toast.makeText(this, "Сначала Старт Live View", Toast.LENGTH_SHORT).show()
            return
        }
        val prop = selProp
        val v = list[index]
        val nm = nameOf(prop) + "=" + EosFmt.label(prop, v)
        cmdQ.add { p -> p.setProp(nm, prop, v) }
    }

    /**
     * Камера по PTP принимает зум 1 и 5 (на 40D значение 10 даёт тот же ×5), поэтому
     * «×10» = зум камеры ×5 + цифровое ×2 в приложении.
     */
    private fun onCameraZoom(level: Int) {
        if (!running) {
            Toast.makeText(this, "Сначала Старт Live View", Toast.LENGTH_SHORT).show()
            return
        }
        val camLevel = if (level >= 5) 5 else 1
        val digital = if (level >= 10) 2f else 1f
        zoomView.setUserZoom(digital)
        cmdQ.add { p ->
            val r = p.op("Зум камеры ×$camLevel (кнопка ×$level)", 0x9158, intArrayOf(camLevel))
            if (r == null || r.code != 0x2001) {
                runOnUiThread {
                    Toast.makeText(this, "Камера не приняла зум (код " +
                        (if (r == null) "нет ответа" else "0x%04X".format(r.code)) + ")", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val vis = if (on) View.GONE else View.VISIBLE
        topRow.visibility = vis
        status.visibility = vis
        settingsRow.visibility = vis
        sliderBox.visibility = vis
        zoomRow.visibility = vis
        scroll.visibility = vis
        btnCopy.visibility = vis
        val pad = if (on) 0 else 24
        root.setPadding(pad, pad, pad, pad)
        btnFull.text = if (on) "✕" else "⛶"
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                if (on) {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    it.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on)
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            else View.SYSTEM_UI_FLAG_VISIBLE
        }
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

    private fun setRecUi() {
        val t = if (recording) "■ Стоп записи" else "● Запись"
        btnRec.text = t
        btnRecOv.text = if (recording) "■" else "●"
    }

    private fun onRecClick() {
        recording = !recording
        setRecUi()
    }

    private fun startWorker(dev: UsbDevice) {
        if (running) return
        running = true
        eos.clear()
        cmdQ.clear()
        runOnUiThread {
            btnLive.text = "Стоп Live View"
            btnRec.isEnabled = true
            btnRecOv.isEnabled = true
            refreshSettingsUi()
        }
        Thread { runLive(dev) }.start()
    }

    /** Прочитать события камеры и обновить параметры. */
    private fun pollEvents(p: PtpUsb) {
        val r = p.transact(0x9116)
        if (r != null && eos.parse(r.data)) runOnUiThread { refreshSettingsUi() }
    }

    private fun logProps() {
        val sb = StringBuilder("параметры:")
        for ((prop, name) in props) {
            val v = eos.value(prop)
            val n = eos.list(prop)?.size ?: 0
            sb.append(" $name=").append(if (v == null) "—" else EosFmt.label(prop, v)).append(" (вариантов: $n);")
        }
        log(sb.toString())
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

            val p = PtpUsb(conn, inE, outE, itf.id) { log(it) }
            ptp = p

            p.op("OpenSession", 0x1002, intArrayOf(1))
            p.op("SetRemoteMode(1)", 0x9114, intArrayOf(1))
            p.op("SetEventMode(1)", 0x9115, intArrayOf(1))
            val ev0 = p.op("GetEvent", 0x9116)
            eos.parse(ev0?.data)
            p.setProp("EVFMode=1", 0xD1B1, 1)
            p.setProp("EVFOutputDevice=PC", 0xD1B0, 2)
            // InitiateViewfinder (0x9151) на 40D даёт 0x2005 — не используем, как и libgphoto2.
            // Камера поднимает зеркало и стартует поток 1–3 с: сливаем события и ждём.
            val ev1 = p.op("GetEvent", 0x9116)
            eos.parse(ev1?.data)
            logProps()
            runOnUiThread { refreshSettingsUi() }

            var noReply = 0
            var notOk = 0
            var okFrames = 0L
            var secFrames = 0
            var secT = System.nanoTime()
            var firstLogged = false
            val startT = System.nanoTime()
            var lastEvT = startT
            var keepT = startT

            while (running) {
                // команды из интерфейса (ползунки, зум камеры)
                var didCmd = false
                while (true) {
                    val c = cmdQ.poll() ?: break
                    c(p)
                    didCmd = true
                }
                if (didCmd) { pollEvents(p); lastEvT = System.nanoTime() }

                val r = p.getViewfinder()
                if (r == null) {
                    noReply++
                    val still = usb.deviceList.values.any { it.vendorId == CANON_VID }
                    log("нет ответа на GetViewFinderData ($noReply), Canon в списке USB: ${if (still) "да" else "НЕТ (отключился)"}")
                    if (!still) break
                    p.recover()
                    if (noReply >= 3) { log("камера не отвечает, стоп"); break }
                    continue
                }
                noReply = 0
                val data = r.data
                if (r.code == 0x2001 && data != null && data.isNotEmpty()) {
                    notOk = 0
                    if (!firstLogged) {
                        firstLogged = true
                        val head = data.take(16).joinToString(" ") { "%02X".format(it) }
                        log("первый кадр: ${data.size} Б, начало: $head, через ${(System.nanoTime() - startT) / 1_000_000} мс")
                    }
                    val jpg = Jpeg.extract(data)
                    if (jpg == null) {
                        if (okFrames == 0L) log("в данных нет JPEG")
                    } else {
                        okFrames++
                        secFrames++
                        handleFrame(jpg, data)
                    }
                } else {
                    notOk++
                    if (okFrames == 0L) {
                        // до первого кадра: логируем и ждём до ~15 с, сливая события
                        if (notOk <= 3 || notOk % 20 == 0) log("GetViewFinderData -> 0x%04X (подряд: $notOk)".format(r.code))
                        if (notOk > 150) { log("Live View не запустился за 15 с, стоп"); break }
                        pollEvents(p)
                        lastEvT = System.nanoTime()
                        Thread.sleep(100)
                    } else {
                        // в потоке 0xA102 — просто «нового кадра ещё нет», не шумим в логе
                        if (notOk % 200 == 0) log("0xA102 подряд: $notOk")
                        Thread.sleep(15)
                    }
                }
                // keep-alive (как libgphoto2), чтобы камера не уходила в автовыключение
                if (System.nanoTime() - keepT > 3_000_000_000L) { p.transact(0x911D); keepT = System.nanoTime() }
                if (System.nanoTime() - lastEvT > 500_000_000L) { pollEvents(p); lastEvT = System.nanoTime() }

                val now = System.nanoTime()
                if (now - secT >= 1_000_000_000L) {
                    val fps = secFrames * 1e9 / (now - secT)
                    secFrames = 0; secT = now
                    val rec = if (writer != null) " ● идёт запись" else ""
                    runOnUiThread { status.text = "%.1f к/с, кадр %s, вид %dx%d%s".format(fps, lastFrameDims, zoomView.width, zoomView.height, rec) }
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
                btnRec.isEnabled = false
                btnRecOv.isEnabled = false
                setRecUi()
            }
        }
    }

    private fun handleFrame(jpg: ByteArray, raw: ByteArray) {
        if (recording && writer == null) {
            val f = File(cacheDir, "rec.avi")
            tmpFile = f
            writer = MjpegAviWriter(f)
            log("запись началась")
        } else if (!recording && writer != null) {
            finishRecording()
        }
        writer?.addFrame(jpg)

        val dims = Jpeg.size(jpg)?.let { "${it.first}x${it.second}" } ?: "?"
        if (dims != lastFrameDims) {
            lastFrameDims = dims
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpg, 0, jpg.size, o)
            log("размер кадра JPEG: $dims, декодер: ${o.outWidth}x${o.outHeight}, вид: ${zoomView.width}x${zoomView.height}")
            logContainer(raw)
        }

        if (uiBusy.compareAndSet(false, true)) {
            val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
            val aspect = evfAspect(raw)
            runOnUiThread {
                if (bmp != null) zoomView.setFrame(bmp, aspect)
                uiBusy.set(false)
            }
        }
    }

    /**
     * Соотношение сторон системы координат Live View (запись типа 5: ширина, высота, напр. 1560x1040).
     * Именно в таких пропорциях кадр надо показывать: при зуме ×5 сам JPEG имеет другие пропорции
     * (768x800), и без этой поправки превью выглядит почти квадратным. Если записи нет — 0 (как у JPEG).
     */
    private fun evfAspect(d: ByteArray): Float {
        fun le32(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or
            ((d[o + 2].toInt() and 0xFF) shl 16) or ((d[o + 3].toInt() and 0xFF) shl 24)
        var off = 0
        while (off + 8 <= d.size) {
            val size = le32(off)
            val type = le32(off + 4)
            if (size < 8 || off + size > d.size) break
            if (type == 5 && size >= 16) {
                val w = le32(off + 8)
                val h = le32(off + 12)
                if (w > 0 && h > 0) {
                    val r = w.toFloat() / h
                    if (r in 1.2f..2.0f) return r
                }
                break
            }
            off += size
        }
        return 0f
    }

    /** Структура данных GetViewFinderData: записи [размер][тип][...]. Для диагностики зума. */
    private fun logContainer(d: ByteArray) {
        fun le32(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or
            ((d[o + 2].toInt() and 0xFF) shl 16) or ((d[o + 3].toInt() and 0xFF) shl 24)
        val sb = StringBuilder("записи кадра (всего ${d.size} Б):")
        var off = 0
        var n = 0
        while (off + 8 <= d.size && n < 12) {
            val size = le32(off)
            val type = le32(off + 4)
            if (size < 8 || off + size > d.size) { sb.append(" [стоп на $off: size=$size type=$type]"); break }
            sb.append(" [тип $type, $size Б")
            if (type != 1 && size > 8) {
                val k = minOf(size - 8, 32)
                sb.append(": ").append((0 until k).joinToString(" ") { "%02X".format(d[off + 8 + it]) })
            }
            sb.append("]")
            off += size
            n++
        }
        log(sb.toString())
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
