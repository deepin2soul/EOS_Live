package com.example.eoslive

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    companion object {
        const val CANON_VID = 0x04A9
        const val ACTION_PERM = "com.example.eoslive.USB_PERMISSION"

        /**
         * Множитель ширины для кадров при зуме камеры ×5. 1.0 = пиксели квадратные, кадр показывается как пришёл
         * (768x800). Если круг на ×5 выглядит овалом: шире, чем выше — уменьши (например 0.9),
         * выше, чем шире — увеличь. Действует и на превью, и на запись.
         */
        const val X5_STRETCH = 1.0f


        /** Размер кадра Live View на ×1; к таким пропорциям (3:2) обрезается кадр ×5, если обрезка включена. */
        const val X1_W = 1024
        const val X1_H = 680
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
    private var mic: MicCapture? = null
    // Декодирование JPEG вынесено из потока камеры: пока декодируется кадр N, камера уже отдаёт N+1.
    // В ячейке лежит только самый свежий кадр: если декодер не успевает, старые кадры пропускаются.
    private class PendingFrame(val jpg: ByteArray, val aspect: Float, val crop: Float)
    private val pendingFrame = AtomicReference<PendingFrame?>(null)
    private val decodeExec = Executors.newSingleThreadExecutor()
    private val decAcc = AtomicLong(0L) // замеры декодирования JPEG
    private val decN = AtomicInteger(0)
    private val micLock = Any()
    @Volatile private var worker: Thread? = null
    /** Обрезка при зуме ×5: вкл — кадр режется до 3:2 (и в превью, и в записи), выкл — полный кадр. */
    @Volatile private var cropZoom = true
    private lateinit var btnCrop: Button
    // Запись идёт в отдельном потоке: кадры, которые надо привести к размеру видео, перекодируются там же.
    private val recQueue = LinkedBlockingQueue<Runnable>()
    private val recExec = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, recQueue)
    private val logBuf = StringBuilder()

    // трогаются только из рабочего потока
    private var writer: MjpegAviWriter? = null
    private var mp4: Mp4Recorder? = null
    /** Кто сейчас пишет (writer или mp4): нужен, чтобы поздно открывшийся микрофон не подключился к чужой записи. */
    @Volatile private var recOwner: Any? = null
    @Volatile private var recSettings = RecordSettings()
    private lateinit var btnRecSet: Button

    // --- режимы: видео / фото / длинная выдержка ---
    private enum class Mode { VIDEO, PHOTO, STACK }
    @Volatile private var mode = Mode.VIDEO
    /** Пока true, кадры Live View не перезаписывают картинку в превью (там снимок или результат суммирования). */
    @Volatile private var holdPreview = false
    @Volatile private var photoBusy = false
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var btnMode: Button
    private lateinit var videoPanel: LinearLayout
    private lateinit var photoPanel: LinearLayout
    private lateinit var stackPanel: LinearLayout
    private lateinit var btnShoot: Button
    private lateinit var btnStackMode: Button
    private lateinit var etStackSec: EditText
    private lateinit var btnStackMethod: Button
    private lateinit var btnStackGo: Button
    private lateinit var btnBack: Button
    private lateinit var infoOv: TextView
    @Volatile private var stack: LongExposure? = null
    @Volatile private var stackActive = false
    private var stackT0 = 0L
    private var stackTimed = true
    private var stackSec = 30.0
    private var stackMethod = StackMethod.SUM
    private var stackTargetSec = 30.0
    /** Множитель яркости результата (0 = авто). */
    private var stackGain = 1f
    private val gainSteps = floatArrayOf(1f, 2f, 4f, 8f, 16f, 0f)
    private lateinit var btnStackGain: Button
    private lateinit var btnStackDark: Button
    private var darkFrame: DarkFrame? = null
    private var darkSig = ""
    @Volatile private var darkLe: LongExposure? = null
    @Volatile private var darkBusy = false
    @Volatile private var lastAspect = 0f
    @Volatile private var lastCrop = 0f
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
        recSettings = RecordSettingsStore.load(this)
        loadStackPrefs()
        buildUi()
        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        log("Готово. Подключи 40D по USB (через OTG) и нажми «Старт Live View».")
        log("запись: " + recSettings.summary())
    }

    override fun onDestroy() {
        running = false
        ui.removeCallbacksAndMessages(null)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        decodeExec.shutdownNow()
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
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnLive = Button(this).apply {
            text = "Старт Live View"
            setOnClickListener { onLiveClick() }
        }
        btnMode = Button(this).apply {
            isAllCaps = false
            setOnClickListener { cycleMode() }
        }
        topRow.addView(btnLive, LinearLayout.LayoutParams(0, wrap, 1f))
        topRow.addView(btnMode, LinearLayout.LayoutParams(0, wrap, 1f))

        // панель «Видео»
        btnRec = Button(this).apply {
            text = "● Запись"
            isEnabled = false
            setOnClickListener { onRecClick() }
        }
        btnRecSet = Button(this).apply {
            text = "⚙ Запись"
            isAllCaps = false
            setOnClickListener {
                showRecordSettingsDialog(this@MainActivity, recSettings) { ns ->
                    recSettings = ns
                    RecordSettingsStore.save(this@MainActivity, ns)
                    log("настройки записи: " + ns.summary())
                }
            }
        }
        videoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnRec, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(btnRecSet, LinearLayout.LayoutParams(0, wrap, 0.8f))
        }

        // панель «Фото»
        btnShoot = Button(this).apply {
            text = "📷 Снять"
            isEnabled = false
            setOnClickListener { onShootClick() }
        }
        photoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(btnShoot)
            addView(TextView(this@MainActivity).apply {
                textSize = 11f
                text = "Снимок сразу передаётся на телефон (Pictures/EOSLive) и показывается в превью. " +
                    "На время снимка Live View прерывается и потом включается сам."
            })
        }

        // панель «Выдержка» (суммирование кадров Live View на телефоне)
        btnStackMode = Button(this).apply {
            isAllCaps = false
            textSize = 12f
            setOnClickListener {
                if (!stackActive) { stackTimed = !stackTimed; saveStackPrefs(); updateStackUi() }
            }
        }
        etStackSec = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            textSize = 14f
            gravity = Gravity.CENTER
            hint = "сек"
            setText(if (stackSec == Math.floor(stackSec)) stackSec.toInt().toString() else stackSec.toString())
        }
        btnStackMethod = Button(this).apply {
            isAllCaps = false
            textSize = 12f
            setOnClickListener {
                if (!stackActive) {
                    stackMethod = StackMethod.values()[(stackMethod.ordinal + 1) % StackMethod.values().size]
                    saveStackPrefs()
                    updateStackUi()
                }
            }
        }
        btnStackGo = Button(this).apply {
            isEnabled = false
            setOnClickListener { onStackClick() }
        }
        btnStackGain = Button(this).apply {
            isAllCaps = false
            textSize = 12f
            setOnClickListener { onGainClick() }
        }
        btnStackDark = Button(this).apply {
            isAllCaps = false
            textSize = 12f
            setOnClickListener { onDarkClick() }
        }
        stackPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(btnStackMode, LinearLayout.LayoutParams(0, wrap, 1.3f))
                addView(etStackSec, LinearLayout.LayoutParams(0, wrap, 0.7f))
                addView(btnStackMethod, LinearLayout.LayoutParams(0, wrap, 1f))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btnStackGo, LinearLayout.LayoutParams(0, wrap, 1.3f))
                addView(btnStackGain, LinearLayout.LayoutParams(0, wrap, 1f))
                addView(btnStackDark, LinearLayout.LayoutParams(0, wrap, 1f))
            })
        }

        // картинка + кнопки поверх неё (запись и полный экран)
        zoomView = ZoomView(this).apply { setBackgroundColor(Color.BLACK) }
        btnRecOv = smallBtn("●") { onMainAction() }.apply { isEnabled = false }
        btnFull = smallBtn("⛶") { setFullscreen(!fullscreen) }
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnRecOv)
            addView(btnFull)
        }
        btnBack = smallBtn("✕ К Live View") { onBackClick() }.apply { visibility = View.GONE; textSize = 13f }
        infoOv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 13f
            setPadding(12, 6, 12, 6)
            visibility = View.GONE
        }
        val frame = FrameLayout(this).apply {
            addView(zoomView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            addView(btnBack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
            addView(infoOv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
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
            btnCrop = Button(this@MainActivity).apply {
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    cropZoom = !cropZoom
                    updateCropBtn()
                }
            }
            addView(btnCrop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
            updateCropBtn()
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
        root.addView(videoPanel)
        root.addView(photoPanel)
        root.addView(stackPanel)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f))
        root.addView(status)
        root.addView(settingsRow)
        root.addView(sliderBox)
        root.addView(zoomRow)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btnCopy)
        setContentView(root)
        refreshSettingsUi()
        applyModeUi()
        setRecUi()
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
        applyModeUi()
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
            if (logBuf.length > 60000) logBuf.delete(0, logBuf.length - 45000)
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

    private fun updateCropBtn() {
        btnCrop.text = if (cropZoom) "Обрезка 3:2: вкл" else "Обрезка 3:2: выкл"
    }

    /** Обновляет кнопки всех режимов по текущему состоянию (только из UI-потока). */
    private fun setRecUi() {
        val live = running
        btnRec.text = if (recording) "■ Стоп записи" else "● Запись"
        btnRec.isEnabled = live && !stackActive && !photoBusy
        btnShoot.text = if (photoBusy) "Снимаю…" else "📷 Снять"
        btnShoot.isEnabled = live && !photoBusy && !stackActive && !recording
        btnStackGo.text = if (stackActive) "■ Стоп" else "▶ Старт"
        btnStackGo.isEnabled = live && !photoBusy && !recording && !darkBusy
        btnRecOv.text = when (mode) {
            Mode.VIDEO -> if (recording) "■" else "●"
            Mode.PHOTO -> if (photoBusy) "…" else "📷"
            Mode.STACK -> if (stackActive) "■" else "▶"
        }
        btnRecOv.isEnabled = when (mode) {
            Mode.VIDEO -> btnRec.isEnabled
            Mode.PHOTO -> btnShoot.isEnabled
            Mode.STACK -> btnStackGo.isEnabled
        }
        btnBack.visibility = if (holdPreview) View.VISIBLE else View.GONE
        btnBack.text = if (stackActive) "✕ Отмена" else "✕ К Live View"
        updateStackUi()
    }

    private fun onRecClick() {
        if (!recording && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 77)
            Toast.makeText(this, "Разреши микрофон и нажми запись ещё раз", Toast.LENGTH_LONG).show()
            return
        }
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
            setRecUi()
            refreshSettingsUi()
        }
        // новый сеанс ждёт, пока предыдущий закончит закрытие, иначе они одновременно работают с камерой
        val old = worker
        val t = Thread {
            try { old?.join(10000) } catch (_: Exception) {}
            running = true
            runLive(dev)
        }
        worker = t
        t.start()
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
            var failT0 = 0L
            var notOk = 0
            var okFrames = 0L
            var secFrames = 0
            var secT = System.nanoTime()
            var firstLogged = false
            val startT = System.nanoTime()
            var lastEvT = startT
            var keepT = startT

            // --- замеры: где уходит время (окно 5 с) ---
            var stT = startT
            var stGetOk = 0L; var stGetOkN = 0; var stBytes = 0L
            var stGetEmpty = 0L; var stEmptyN = 0
            var stHandle = 0L; var stEvents = 0L; var stSleep = 0L
            var lastFrameT = 0L; var stMaxGap = 0L
            decAcc.set(0L); decN.set(0)

            while (running) {
                // команды из интерфейса (ползунки, зум камеры)
                var didCmd = false
                while (true) {
                    val c = cmdQ.poll() ?: break
                    c(p)
                    didCmd = true
                }
                if (didCmd) { pollEvents(p); lastEvT = System.nanoTime() }

                val tGet0 = System.nanoTime()
                val r = p.getViewfinder()
                val dtGet = System.nanoTime() - tGet0
                if (r == null) {
                    if (noReply == 0) {
                        failT0 = System.nanoTime()
                        log("GetViewFinderData: нет ответа (${p.lastError}). Возможно, камера занята (например, автофокус по полунажатию спуска). Жду и пробую снова.")
                    }
                    noReply++
                    val still = usb.deviceList.values.any { it.vendorId == CANON_VID }
                    if (!still) { log("Canon пропал из списка USB — камера отключилась"); break }
                    val down = (System.nanoTime() - failT0) / 1e9
                    if (noReply % 10 == 0) log("  всё ещё нет ответа: %.0f с, попыток %d (%s)".format(down, noReply, p.lastError))
                    if (down > 30) {
                        log("камера не отвечает 30 с, стоп. Выключи и включи камеру или переподключи кабель.")
                        break
                    }
                    p.recover()
                    Thread.sleep(300)
                    // раз в ~3 с заново включаем вывод Live View на ПК
                    if (noReply % 10 == 5) p.setProp("EVFOutputDevice=PC", 0xD1B0, 2)
                    continue
                }
                if (noReply > 0) {
                    log("камера снова отвечает, перерыв %.1f с (попыток: %d)".format((System.nanoTime() - failT0) / 1e9, noReply))
                    lastEvT = 0L
                }
                noReply = 0
                val data = r.data
                if (r.code == 0x2001 && data != null && data.isNotEmpty()) {
                    notOk = 0
                    stGetOk += dtGet; stGetOkN++; stBytes += data.size
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
                        val tH = System.nanoTime()
                        handleFrame(jpg, data)
                        stHandle += System.nanoTime() - tH
                        if (lastFrameT != 0L) stMaxGap = maxOf(stMaxGap, tH - lastFrameT)
                        lastFrameT = tH
                    }
                } else {
                    notOk++
                    stGetEmpty += dtGet; stEmptyN++
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
                        val tS = System.nanoTime()
                        Thread.sleep(15)
                        stSleep += System.nanoTime() - tS
                    }
                }
                // keep-alive (как libgphoto2), чтобы камера не уходила в автовыключение
                val tE = System.nanoTime()
                if (tE - keepT > 3_000_000_000L) { p.transact(0x911D); keepT = System.nanoTime() }
                if (System.nanoTime() - lastEvT > 500_000_000L) { pollEvents(p); lastEvT = System.nanoTime() }
                stEvents += System.nanoTime() - tE

                val now = System.nanoTime()
                if (now - secT >= 1_000_000_000L) {
                    val fps = secFrames * 1e9 / (now - secT)
                    secFrames = 0; secT = now
                    val rec = if (isRecActive()) " ● идёт запись" else ""
                    runOnUiThread { status.text = "%.1f к/с, кадр %s, вид %dx%d%s".format(fps, lastFrameDims, zoomView.width, zoomView.height, rec) }
                }
                if (now - stT >= 30_000_000_000L) {
                    val win = (now - stT) / 1e9
                    fun ms(ns: Long, n: Int) = if (n > 0) ns / 1e6 / n else 0.0
                    val kb = if (stGetOkN > 0) stBytes / 1024.0 / stGetOkN else 0.0
                    val mbs = stBytes / 1e6 / win
                    log("замер за %.1f с: кадров %d (%.1f к/с), пауза между кадрами макс. %.0f мс".format(
                        win, stGetOkN, stGetOkN / win, stMaxGap / 1e6))
                    log("  GetViewFinderData с кадром: %.1f мс в среднем (%.0f КБ, %.2f МБ/с); пустых 0xA102: %d, по %.1f мс".format(
                        ms(stGetOk, stGetOkN), kb, mbs, stEmptyN, ms(stGetEmpty, stEmptyN)))
                    log("  обработка кадра: %.1f мс (из них декодирование JPEG %.1f мс ×%d); события+keep-alive: %.1f мс/с; sleep: %.0f мс/с".format(
                        ms(stHandle, stGetOkN), if (decN.get() > 0) decAcc.get() / 1e6 / decN.get() else 0.0, decN.get(),
                        stEvents / 1e6 / win, stSleep / 1e6 / win))
                    val camShare = (stGetOk + stGetEmpty) / 1e9 / win * 100
                    val appShare = (stHandle + stEvents + stSleep) / 1e9 / win * 100
                    log("  доля времени: запросы к камере %.0f%%, обработка+события+sleep %.0f%%".format(camShare, appShare))
                    stT = now
                    stGetOk = 0; stGetOkN = 0; stBytes = 0; stGetEmpty = 0; stEmptyN = 0
                    stHandle = 0; stEvents = 0; stSleep = 0; stMaxGap = 0
                    decAcc.set(0L); decN.set(0)
                }
            }
        } catch (e: Exception) {
            log("ошибка: $e")
        } finally {
            try { if (isRecActive()) finishRecording() } catch (e: Exception) { log("ошибка сохранения: $e") }
            if (stackActive) { log("Live View остановлен во время суммирования — сохраняю то, что набрано"); runOnUiThread { stopStack(true) } }
            photoBusy = false
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
                setRecUi()
            }
        }
    }

    // ------------------------------------------------------------------ режимы: видео / фото / выдержка

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun showInfo(s: String?) = runOnUiThread {
        if (s.isNullOrEmpty()) infoOv.visibility = View.GONE
        else { infoOv.text = s; infoOv.visibility = View.VISIBLE }
    }

    private fun fmtDur(sec: Double): String =
        if (sec >= 60) "%d мин %.1f с".format((sec / 60).toInt(), sec - (sec / 60).toInt() * 60)
        else "%.1f с".format(sec)

    private fun applyModeUi() {
        btnMode.text = "Режим: " + when (mode) { Mode.VIDEO -> "видео"; Mode.PHOTO -> "фото"; Mode.STACK -> "выдержка" }
        videoPanel.visibility = if (!fullscreen && mode == Mode.VIDEO) View.VISIBLE else View.GONE
        photoPanel.visibility = if (!fullscreen && mode == Mode.PHOTO) View.VISIBLE else View.GONE
        stackPanel.visibility = if (!fullscreen && mode == Mode.STACK) View.VISIBLE else View.GONE
    }

    private fun cycleMode() {
        if (stackActive) { toast("Сначала останови суммирование"); return }
        if (recording) { toast("Сначала останови запись"); return }
        if (photoBusy) return
        mode = Mode.values()[(mode.ordinal + 1) % Mode.values().size]
        releaseHold()
        applyModeUi()
        setRecUi()
    }

    /** Большая кнопка на картинке (видно и в полноэкранном режиме): действие зависит от режима. */
    private fun onMainAction() {
        when (mode) {
            Mode.VIDEO -> onRecClick()
            Mode.PHOTO -> onShootClick()
            Mode.STACK -> onStackClick()
        }
    }

    private fun releaseHold() {
        holdPreview = false
        infoOv.visibility = View.GONE
        btnBack.visibility = View.GONE
    }

    private fun onBackClick() {
        if (stackActive) stopStack(false) else { releaseHold(); setRecUi() }
    }

    /** Показать картинку в превью и не давать Live View её перезаписать, пока не нажмут «К Live View». */
    private fun showHeld(bmp: Bitmap, aspect: Float, crop: Float, info: String) = runOnUiThread {
        holdPreview = true
        zoomView.setFrame(bmp, aspect, crop)
        infoOv.text = info
        infoOv.visibility = View.VISIBLE
        setRecUi()
    }

    // ------------------------------------------------------------------ фото

    private fun onShootClick() {
        if (!running) { Toast.makeText(this, "Сначала Старт Live View", Toast.LENGTH_SHORT).show(); return }
        if (photoBusy || stackActive || recording) return
        val tv = eos.value(EosProp.TV)
        if (tv == 0x0C) {
            Toast.makeText(this, "Выдержка Bulb в режиме «Фото» не поддерживается: выбери обычную выдержку (длинную можно набрать в режиме «Выдержка»)", Toast.LENGTH_LONG).show()
            return
        }
        val expSec = if (tv != null) EosFmt.seconds(tv) else 1.0
        photoBusy = true
        releaseHold()
        setRecUi()
        showInfo("Снимаю…")
        cmdQ.add { p ->
            val t0 = System.nanoTime()
            try {
                val pc = PhotoCapture(p, eos, { log(it) }, { showInfo(it) }, { runOnUiThread { refreshSettingsUi() } })
                val files = pc.shoot(expSec)
                val secs = (System.nanoTime() - t0) / 1e9
                val err = pc.error
                // сохранение и декодирование — не в потоке камеры, чтобы Live View вернулся сразу
                Thread { onPhotos(files, secs, err) }.start()
            } catch (e: Exception) {
                log("ошибка снимка: $e")
                showInfo(null)
            } finally {
                photoBusy = false
                runOnUiThread { setRecUi() }
            }
        }
    }

    private fun onPhotos(files: List<PhotoFile>, secs: Double, err: String?) {
        if (files.isEmpty()) {
            val msg = "Снимок не получен" + (if (err != null) ": $err" else "")
            log(msg)
            showInfo(null)
            toast(msg)
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var shown = false
        val lines = ArrayList<String>()
        for (f in files) {
            val jpeg = PhotoUtil.isJpeg(f.data)
            val saved = Gallery.saveBytes(this, f.data, "eos_${stamp}_${f.name}", if (jpeg) "image/jpeg" else "image/x-canon-cr2")
            val mb = "%.1f".format(f.data.size / 1e6)
            log("снимок ${f.name}, $mb МБ -> " + (saved ?: "не удалось сохранить в галерею"))
            lines.add("${f.name}, $mb МБ" + if (saved == null) " (не сохранён!)" else "")
            if (!shown) {
                val jpg = if (jpeg) f.data else PhotoUtil.embeddedJpeg(f.data)
                val bmp = jpg?.let { PhotoUtil.decodeForView(it) }
                if (bmp != null) {
                    shown = true
                    val info = "Снимок: " + lines.joinToString("; ") + " · %.1f с\nСохранён в ${Gallery.DIR}".format(secs)
                    showHeld(bmp, 0f, 0f, info)
                    runOnUiThread { zoomView.setUserZoom(1f) }
                }
            }
        }
        if (!shown) showInfo("Снимок получен: " + lines.joinToString("; ") + " (показать не удалось)")
    }

    // ------------------------------------------------------------------ длинная выдержка (суммирование кадров Live View)

    private fun loadStackPrefs() {
        val p = getSharedPreferences("stack_settings", Context.MODE_PRIVATE)
        stackTimed = p.getBoolean("timed", true)
        stackSec = p.getFloat("sec", 30f).toDouble()
        stackGain = p.getFloat("gain", 1f)
        stackMethod = try { StackMethod.valueOf(p.getString("method", "SUM") ?: "SUM") } catch (_: Exception) { StackMethod.SUM }
    }

    private fun saveStackPrefs() {
        getSharedPreferences("stack_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("timed", stackTimed)
            .putFloat("sec", stackSec.toFloat())
            .putFloat("gain", stackGain)
            .putString("method", stackMethod.name)
            .apply()
    }

    private fun updateStackUi() {
        btnStackMode.text = if (stackTimed) "По времени, с:" else "Бесконечная"
        etStackSec.visibility = if (stackTimed) View.VISIBLE else View.GONE
        etStackSec.isEnabled = !stackActive
        btnStackMethod.text = "Метод: " + stackMethod.label
        btnStackGain.text = "Яркость: " + gainLabel(stackGain)
        btnStackDark.text = "Тёмный: " + (if (darkBusy) "…" else if (darkFrame != null) "есть" else "нет")
        btnStackDark.isEnabled = !stackActive && !darkBusy
    }

    private fun gainLabel(g: Float): String =
        if (g <= 0f) "авто" else "×" + (if (g == Math.floor(g.toDouble()).toFloat()) g.toInt().toString() else g.toString())

    /** Меняется и во время суммирования: результат в превью меняется сразу. */
    private fun onGainClick() {
        val i = gainSteps.indexOfFirst { it == stackGain }
        stackGain = gainSteps[(i + 1) % gainSteps.size]
        saveStackPrefs()
        stack?.gain = stackGain
        updateStackUi()
    }

    private fun camSig(): String = "${eos.value(EosProp.TV)}/${eos.value(EosProp.ISO)}/${eos.value(EosProp.AV)}"

    private fun onDarkClick() {
        if (stackActive || darkBusy) return
        if (!running) { Toast.makeText(this, "Сначала Старт Live View", Toast.LENGTH_SHORT).show(); return }
        val b = AlertDialog.Builder(this)
            .setTitle("Тёмный кадр")
            .setMessage("Убирает вертикальные полосы и «ткань» на длинной выдержке: матрица каждый раз даёт один и тот же рисунок, " +
                "и его можно вычесть.\n\nЗакрой объектив крышкой. ISO, выдержка и диафрагма должны быть такими же, " +
                "как при суммировании. Снимается около 3 секунд.")
            .setPositiveButton("Снять") { _, _ -> startDark() }
            .setNegativeButton("Отмена", null)
        if (darkFrame != null) b.setNeutralButton("Удалить") { _, _ -> darkFrame = null; updateStackUi(); log("тёмный кадр удалён") }
        b.show()
    }

    private fun startDark() {
        if (!running || stackActive || darkBusy) return
        darkBusy = true
        darkSig = camSig()
        val le = LongExposure(StackMethod.AVG, null, { log(it) }) { _ -> }
        darkLe = le
        le.start()
        val t0 = System.nanoTime()
        infoOv.visibility = View.VISIBLE
        setRecUi()
        ui.post(object : Runnable {
            override fun run() {
                val el = (System.nanoTime() - t0) / 1e9
                infoOv.text = "Тёмный кадр: объектив закрыт? Осталось %.0f с, кадров %d".format(maxOf(0.0, 3.0 - el), le.frames)
                if (el >= 3.0) finishDark(le) else ui.postDelayed(this, 150)
            }
        })
    }

    private fun finishDark(le: LongExposure) {
        darkLe = null
        infoOv.text = "Считаю тёмный кадр…"
        Thread {
            le.finish()
            val m = le.darkMaster()
            runOnUiThread {
                darkBusy = false
                if (!holdPreview) infoOv.visibility = View.GONE
                if (m == null || le.frames < 5) {
                    darkFrame = null
                    log("тёмный кадр не снялся, кадров: ${le.frames}")
                    toast("Тёмный кадр не снялся (кадров: ${le.frames})")
                } else {
                    darkFrame = m
                    val lvl = m.meanLevel()
                    log("тёмный кадр: ${le.frames} кадров, средний уровень %.1f из 255 (${darkSig})".format(lvl))
                    toast(if (lvl > 30) "Уровень тёмного кадра высокий (%.0f из 255): объектив точно закрыт крышкой?".format(lvl)
                          else "Тёмный кадр готов. Открой объектив")
                }
                setRecUi()
            }
        }.start()
    }

    private fun onStackClick() {
        if (stackActive) stopStack(true) else startStack()
    }

    private fun startStack() {
        if (!running) { Toast.makeText(this, "Сначала Старт Live View", Toast.LENGTH_SHORT).show(); return }
        if (recording || photoBusy) return
        if (stackTimed) {
            stackSec = etStackSec.text.toString().replace(',', '.').toDoubleOrNull()?.coerceIn(0.5, 36000.0) ?: stackSec
        }
        saveStackPrefs()
        stackTargetSec = stackSec
        if (darkFrame != null && darkSig != camSig()) {
            log("настройки камеры изменились после тёмного кадра (было $darkSig, стало ${camSig()})")
            toast("Выдержка/ISO/диафрагма изменились после тёмного кадра: вычитание может быть неточным. Лучше снять тёмный кадр заново")
        }
        val le = LongExposure(stackMethod, darkFrame, { log(it) }) { bmp ->
            runOnUiThread { if (stackActive) zoomView.setFrame(bmp, lastAspect, lastCrop) }
        }
        le.gain = stackGain
        stack = le
        le.start()
        stackT0 = System.nanoTime()
        holdPreview = true
        stackActive = true
        infoOv.text = "Суммирование…"
        log("  яркость: ${gainLabel(stackGain)}, тёмный кадр: " + (if (darkFrame != null) "да" else "нет"))
        infoOv.visibility = View.VISIBLE
        log("суммирование: старт, метод «${stackMethod.label}», " + if (stackTimed) "по времени ${fmtDur(stackTargetSec)}" else "бесконечная (стоп вручную)")
        setRecUi()
        ui.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!stackActive) return
            val el = (System.nanoTime() - stackT0) / 1e9
            val st = stack
            val n = st?.frames ?: 0
            val d = (st?.dropped ?: 0) + (st?.rejected ?: 0)
            infoOv.text = "Выдержка: ${fmtDur(el)}" + (if (stackTimed) " из ${fmtDur(stackTargetSec)}" else " (без ограничения)") +
                "\nкадров: $n" + (if (d > 0) " (пропущено $d)" else "")
            if (stackTimed && el >= stackTargetSec) { stopStack(true); return }
            ui.postDelayed(this, 150)
        }
    }

    /** save = true: итог показывается в превью и сохраняется в галерею; false: отмена, результат выбрасывается. */
    private fun stopStack(save: Boolean) {
        if (!stackActive) return
        stackActive = false
        ui.removeCallbacks(ticker)
        val elapsed = (System.nanoTime() - stackT0) / 1e9
        val st = stack
        stack = null
        if (st == null) { releaseHold(); setRecUi(); return }
        if (!save) {
            Thread { st.finish() }.start()
            log("суммирование отменено")
            releaseHold()
            setRecUi()
            return
        }
        val asp = lastAspect
        val cr = lastCrop
        val label = stackMethod.label
        infoOv.text = "Завершаю суммирование…"
        setRecUi()
        Thread {
            val res = st.finish()
            val n = st.frames
            if (res == null || n == 0) {
                log("суммирование: кадров не было, нечего сохранять")
                runOnUiThread { releaseHold(); setRecUi() }
                return@Thread
            }
            val skipped = st.dropped + st.rejected
            val name = "eos_stack_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + Math.round(elapsed) + "s.jpg"
            val saved = Gallery.saveJpeg(this, res, name)
            val line = "Готово: ${fmtDur(elapsed)}, кадров $n" + (if (skipped > 0) " (пропущено $skipped)" else "") + ", метод «$label»" + (if (st.darkUsed) ", с тёмным кадром" else "")
            log(line + " -> " + (saved ?: "не удалось сохранить"))
            runOnUiThread {
                holdPreview = true
                zoomView.setFrame(res, asp, cr)
                infoOv.text = line + "\n" + (if (saved != null) "Сохранено: $saved" else "Не удалось сохранить в галерею!")
                infoOv.visibility = View.VISIBLE
                setRecUi()
            }
        }.start()
    }

    private fun isRecActive() = writer != null || mp4 != null

    private fun startMic(owner: Any, rate: Int, onData: (ByteArray, Int) -> Unit, onFail: () -> Unit) {
        // открытие микрофона занимает сотни мс — делаем в отдельном потоке, чтобы не стопорить камеру
        Thread {
            val m = MicCapture(rate, onData)
            val ok = m.start()
            synchronized(micLock) {
                if (ok && recOwner === owner) mic = m
                else {
                    if (ok) m.stop()
                    else { log("микрофон не открылся, запись без звука"); onFail() }
                }
            }
        }.start()
    }

    private fun startRecording(sz: Pair<Int, Int>?, crop: Boolean) {
        val cfg = recSettings
        val hasMic = cfg.audioOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cfg.isMp4) {
            // обрезка включена и запись началась на ×5: видео сразу 3:2, как на ×1
            val vw = if (crop) X1_W else (sz?.first ?: X1_W)
            val vh = if (crop) X1_H else (sz?.second ?: X1_H)
            val f = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
            val m = Mp4Recorder(f, cfg, vw, vh, hasMic) { log(it) }
            if (m.start()) {
                tmpFile = f
                mp4 = m
                recOwner = m
                log("запись началась (MP4): " + m.describe())
                if (hasMic) startMic(m, cfg.audioRate, { buf, n -> m.addAudio(buf, n) }, { m.audioFailed() })
                return
            }
            f.delete()
            log("кодировщик не запустился, пишу в AVI")
        }
        val f = File(cacheDir, "rec_${System.currentTimeMillis()}.avi")
        tmpFile = f
        val w = MjpegAviWriter(f, withAudio = hasMic)
        writer = w
        recOwner = w
        log("запись началась (AVI)" + if (hasMic) " (звук подключается)" else " (без звука)")
        if (hasMic) startMic(w, 44100, { buf, n -> w.addAudio(buf, n) }, {})
    }

    private fun handleFrame(jpg: ByteArray, raw: ByteArray) {
        val sz = Jpeg.size(jpg)
        val zoom = evfRecordInt(raw, 4) ?: 1
        // отображаемые пропорции кадра: у ×1 — как есть (0), у ×5 — с поправкой X5_STRETCH
        val aspect = if (zoom > 1 && sz != null && X5_STRETCH != 1f) sz.first.toFloat() / sz.second * X5_STRETCH else 0f
        val crop = cropZoom && zoom > 1 && sz != null
        lastAspect = aspect
        lastCrop = if (crop) X1_W.toFloat() / X1_H else 0f
        val st = stack
        if (stackActive && st != null) st.offer(jpg)
        darkLe?.offer(jpg)
        if (recording && !isRecActive()) {
            startRecording(sz, crop)
        } else if (!recording && isRecActive()) {
            finishRecording()
        }
        // MP4: кадр уходит в отдельные потоки декодирования и кодирования, поток камеры не ждёт
        mp4?.offerFrame(jpg, aspect, crop)
        val wr = writer
        if (wr != null && recQueue.size < 40) {
            recExec.execute {
                try {
                    // обрезка включена и запись началась на ×5: видео сразу делаем 3:2, как на ×1
                    if (crop && wr.size() == null) wr.setSize(X1_W, X1_H)
                    val have = wr.size()
                    val out = if (have == null || (!crop && sz != null && sz.first == have.first && sz.second == have.second && aspect == 0f)) jpg
                              else fitToSize(jpg, have.first, have.second, aspect, crop) ?: jpg
                    wr.addFrame(out)
                } catch (e: Exception) { log("ошибка записи кадра: $e") }
            }
        }

        val dims = sz?.let { "${it.first}x${it.second}" } ?: "?"
        if (dims != lastFrameDims) {
            lastFrameDims = dims
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpg, 0, jpg.size, o)
            log("размер кадра JPEG: $dims, декодер: ${o.outWidth}x${o.outHeight}, вид: ${zoomView.width}x${zoomView.height}")
            logContainer(raw)
        }

        // на экране снимок или результат суммирования — Live View не декодируем и не показываем
        if (holdPreview) return
        val prev = pendingFrame.getAndSet(PendingFrame(jpg, aspect, if (crop) X1_W.toFloat() / X1_H else 0f))
        if (prev == null) {
            // ячейка была пуста — значит, задача декодирования ещё не поставлена
            try {
                decodeExec.execute {
                    val f = pendingFrame.getAndSet(null) ?: return@execute
                    val tD = System.nanoTime()
                    val bmp = BitmapFactory.decodeByteArray(f.jpg, 0, f.jpg.size)
                    decAcc.addAndGet(System.nanoTime() - tD); decN.incrementAndGet()
                    if (bmp != null && !holdPreview) runOnUiThread { if (!holdPreview) zoomView.setFrame(bmp, f.aspect, f.crop) }
                }
            } catch (_: Exception) { pendingFrame.set(null) }
        }
    }

    /** Первое число записи заданного типа в данных GetViewFinderData (тип 4 — уровень зума камеры). */
    private fun evfRecordInt(d: ByteArray, type: Int): Int? {
        fun le32(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or
            ((d[o + 2].toInt() and 0xFF) shl 16) or ((d[o + 3].toInt() and 0xFF) shl 24)
        var off = 0
        while (off + 8 <= d.size) {
            val size = le32(off)
            if (size < 8 || off + size > d.size) break
            if (le32(off + 4) == type) return if (size >= 12) le32(off + 8) else null
            off += size
        }
        return null
    }

    /**
     * Кладёт кадр на холст tw×th без искажений пропорций и перекодирует в JPEG:
     * fill = true — заполняет весь холст, лишнее обрезается по центру; false — вписывает целиком с чёрными полями.
     * Нужен, когда размер кадра отличается от размера видео (после смены зума камеры во время записи).
     */
    private fun fitToSize(jpg: ByteArray, tw: Int, th: Int, aspect: Float, fill: Boolean): ByteArray? {
        val src = BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: return null
        val a = if (aspect > 0f) aspect else src.width.toFloat() / src.height
        val dw: Float
        val dh: Float
        // fit: целиком с полями; fill: на весь холст с обрезкой лишнего по центру
        val wider = a >= tw.toFloat() / th
        if (wider != fill) { dw = tw.toFloat(); dh = tw / a } else { dh = th.toFloat(); dw = th * a }
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val l = (tw - dw) / 2f
        val t = (th - dh) / 2f
        c.drawBitmap(src, null, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        src.recycle(); out.recycle()
        return bos.toByteArray()
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
        val w = writer
        val m = mp4
        if (w == null && m == null) return
        writer = null
        mp4 = null
        synchronized(micLock) { recOwner = null; mic?.stop(); mic = null }
        val f = tmpFile ?: return
        // финиш и копирование в «Фильмы» идут в потоке записи, после всех уже поставленных кадров
        recExec.execute {
            try {
                val n = m?.finish() ?: w!!.finish()
                if (n == 0) { log("запись пустая"); f.delete() } else saveToMovies(f, n, if (m != null) "mp4" else "avi")
            } catch (e: Exception) { log("ошибка сохранения: $e") }
        }
    }

    private fun saveToMovies(f: File, n: Int, ext: String) {
        try {
        val name = "eos_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + ext
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, if (ext == "mp4") "video/mp4" else "video/x-msvideo")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EOSLive")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
        if (uri == null) { log("не удалось создать файл в Movies"); return }
        val mb = f.length() / 1e6
        contentResolver.openOutputStream(uri)?.use { out ->
            f.inputStream().use { it.copyTo(out) }
        }
        f.delete()
        log("сохранено: Movies/EOSLive/$name, кадров: $n, %.1f МБ".format(mb))
        } catch (e: Exception) { log("ошибка сохранения: $e") }
    }
}
