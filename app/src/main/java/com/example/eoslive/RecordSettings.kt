package com.example.eoslive

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.media.MediaFormat
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

enum class Container(val label: String) {
    H264("H.264 (MP4) — открывается везде"),
    H265("H.265 (MP4) — файл меньше"),
    AVI("MJPEG (AVI) — без пережатия")
}

enum class Preset(val label: String) {
    ECO("Эконом (4 Мбит/с)"),
    NORMAL("Обычное (8 Мбит/с)"),
    HIGH("Высокое (14 Мбит/с)"),
    MANUAL("Свой битрейт")
}

enum class RateMode(val label: String) {
    VBR("VBR — качество стабильнее"),
    CBR("CBR — ровный поток"),
    CQ("CQ — постоянное качество (если телефон умеет)")
}

/** Настройки записи. Частота кадров не настраивается: в MP4 всегда 25 к/с. */
data class RecordSettings(
    val container: Container = Container.H264,
    val preset: Preset = Preset.NORMAL,
    /** Битрейт для пресета «Свой битрейт», Мбит/с (для выбранного кодека как есть). */
    val manualMbps: Float = 8f,
    val rateMode: RateMode = RateMode.VBR,
    /** Интервал ключевых кадров, секунды. */
    val keyIntervalSec: Int = 1,
    val audioOn: Boolean = true,
    val audioKbps: Int = 128,
    val audioRate: Int = 48000,
    val stereo: Boolean = false
) {
    val isMp4: Boolean get() = container != Container.AVI
    val isHevc: Boolean get() = container == Container.H265

    /** Битрейт видео, бит/с. H.265 при том же качестве нужен ~на 40% меньше (для пресетов). */
    fun videoBitrate(hevc: Boolean = isHevc): Int {
        val mbps = when (preset) {
            Preset.ECO -> 4f
            Preset.NORMAL -> 8f
            Preset.HIGH -> 14f
            Preset.MANUAL -> manualMbps
        }
        val k = if (hevc && preset != Preset.MANUAL) 0.6f else 1f
        return (mbps * k * 1_000_000f).toInt()
    }

    /** Качество 0..100 для режима CQ. */
    fun cqQuality(): Int = when (preset) {
        Preset.ECO -> 55
        Preset.NORMAL -> 75
        Preset.HIGH -> 90
        Preset.MANUAL -> 80
    }

    fun summary(): String {
        if (!isMp4) return "MJPEG AVI, без пережатия" + if (audioOn) ", звук PCM" else ", без звука"
        val v = if (rateMode == RateMode.CQ) "CQ, качество ${cqQuality()}"
        else "%s %.1f Мбит/с".format(rateMode.name, videoBitrate() / 1e6)
        val a = if (audioOn) ", AAC $audioKbps кбит/с ${audioRate / 1000.0} кГц ${if (stereo) "стерео" else "моно"}"
        else ", без звука"
        return "${if (isHevc) "H.265" else "H.264"} MP4, 25 к/с, $v, ключ. кадр ${keyIntervalSec} с$a"
    }
}

object RecordSettingsStore {
    private const val PREFS = "rec_settings"

    private inline fun <reified T : Enum<T>> enumOr(s: String?, def: T): T =
        try { if (s == null) def else enumValueOf<T>(s) } catch (_: Exception) { def }

    fun load(ctx: Context): RecordSettings {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = RecordSettings()
        return RecordSettings(
            container = enumOr(p.getString("container", null), d.container),
            preset = enumOr(p.getString("preset", null), d.preset),
            manualMbps = p.getFloat("manualMbps", d.manualMbps),
            rateMode = enumOr(p.getString("rateMode", null), d.rateMode),
            keyIntervalSec = p.getInt("keyIntervalSec", d.keyIntervalSec),
            audioOn = p.getBoolean("audioOn", d.audioOn),
            audioKbps = p.getInt("audioKbps", d.audioKbps),
            audioRate = p.getInt("audioRate", d.audioRate),
            stereo = p.getBoolean("stereo", d.stereo)
        )
    }

    fun save(ctx: Context, s: RecordSettings) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("container", s.container.name)
            .putString("preset", s.preset.name)
            .putFloat("manualMbps", s.manualMbps)
            .putString("rateMode", s.rateMode.name)
            .putInt("keyIntervalSec", s.keyIntervalSec)
            .putBoolean("audioOn", s.audioOn)
            .putInt("audioKbps", s.audioKbps)
            .putInt("audioRate", s.audioRate)
            .putBoolean("stereo", s.stereo)
            .apply()
    }
}

/** Окно «Настройки записи»: все параметры в одном списке. Действуют со следующей записи. */
fun showRecordSettingsDialog(ctx: Context, cur: RecordSettings, onSave: (RecordSettings) -> Unit) {
    var s = cur
    val dp = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    fun vbox() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val box = vbox().apply { setPadding(px(16), px(8), px(16), px(8)) }
    var refresh: () -> Unit = {}

    fun head(t: String) = TextView(ctx).apply {
        text = t
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, px(16), 0, px(2))
    }
    fun cap(t: String) = TextView(ctx).apply {
        text = t
        textSize = 12f
        setPadding(0, px(8), 0, 0)
    }
    fun <T> spinner(items: List<T>, names: List<String>, current: T, onPick: (T) -> Unit): Spinner =
        Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(items.indexOf(current).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    onPick(items[pos])
                    refresh()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

    val hevcOk = Mp4Recorder.hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)
    val containers = Container.values().toList()
    val containerNames = containers.map {
        if (it == Container.H265 && !hevcOk) it.label + " — нет на этом телефоне" else it.label
    }

    // --- формат ---
    box.addView(head("Видео"))
    box.addView(cap("Формат файла"))
    box.addView(spinner(containers, containerNames, s.container) { s = s.copy(container = it) })

    val aviNote = TextView(ctx).apply {
        textSize = 12f
        setPadding(0, px(8), 0, 0)
        text = "Кадры камеры пишутся как есть (JPEG), звук без сжатия (PCM). Файл большой (~250 МБ в минуту), " +
            "частота кадров в нём усреднённая, звук может уплывать."
    }
    box.addView(aviNote)

    val videoGroup = vbox()
    videoGroup.addView(cap("Качество"))
    videoGroup.addView(spinner(Preset.values().toList(), Preset.values().map { it.label }, s.preset) {
        s = s.copy(preset = it)
    })
    val manualEt = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText("%.1f".format(s.manualMbps))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) { refresh() }
        })
    }
    fun parseMbps(): Float =
        manualEt.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(0.5f, 60f) ?: s.manualMbps
    val manualRow = vbox().apply {
        addView(cap("Битрейт видео, Мбит/с (0.5–60; для выбранного кодека как есть)"))
        addView(manualEt)
    }
    videoGroup.addView(manualRow)
    videoGroup.addView(cap("Режим битрейта"))
    videoGroup.addView(spinner(RateMode.values().toList(), RateMode.values().map { it.label }, s.rateMode) {
        s = s.copy(rateMode = it)
    })
    videoGroup.addView(cap("Ключевой кадр раз в (меньше — точнее монтаж, но файл больше)"))
    val keys = listOf(1, 2, 4)
    videoGroup.addView(spinner(keys, keys.map { "$it с" }, s.keyIntervalSec) { s = s.copy(keyIntervalSec = it) })
    videoGroup.addView(cap("Частота кадров: 25 к/с, постоянная (дубликаты, если камера не успела)"))
    box.addView(videoGroup)

    // --- звук ---
    box.addView(head("Звук"))
    val audioCb = CheckBox(ctx).apply {
        text = "Записывать звук с микрофона"
        isChecked = s.audioOn
        setOnCheckedChangeListener { _, v -> s = s.copy(audioOn = v); refresh() }
    }
    box.addView(audioCb)
    val audioGroup = vbox()
    audioGroup.addView(cap("Битрейт звука AAC"))
    val kbpsList = listOf(96, 128, 192, 256)
    audioGroup.addView(spinner(kbpsList, kbpsList.map { "$it кбит/с" }, s.audioKbps) { s = s.copy(audioKbps = it) })
    audioGroup.addView(cap("Частота дискретизации (48 кГц лучше подходит для видео)"))
    val rates = listOf(44100, 48000)
    audioGroup.addView(spinner(rates, listOf("44,1 кГц", "48 кГц"), s.audioRate) { s = s.copy(audioRate = it) })
    audioGroup.addView(CheckBox(ctx).apply {
        text = "Стерео (микрофон один — каналы будут одинаковыми)"
        isChecked = s.stereo
        setOnCheckedChangeListener { _, v -> s = s.copy(stereo = v); refresh() }
    })
    box.addView(audioGroup)

    val info = TextView(ctx).apply {
        textSize = 13f
        setPadding(0, px(16), 0, 0)
    }
    box.addView(info)
    box.addView(TextView(ctx).apply {
        textSize = 12f
        setPadding(0, px(8), 0, 0)
        text = "Настройки действуют со следующей записи."
    })

    refresh = {
        val mp4 = s.container != Container.AVI
        aviNote.visibility = if (mp4) View.GONE else View.VISIBLE
        videoGroup.visibility = if (mp4) View.VISIBLE else View.GONE
        manualRow.visibility = if (s.preset == Preset.MANUAL) View.VISIBLE else View.GONE
        audioGroup.visibility = if (mp4 && s.audioOn) View.VISIBLE else View.GONE
        val cs = if (s.preset == Preset.MANUAL) s.copy(manualMbps = parseMbps()) else s
        info.text = if (!mp4) {
            "Размер файла: ~250 МБ в минуту."
        } else if (cs.rateMode == RateMode.CQ) {
            "Размер файла зависит от сцены (режим CQ)."
        } else {
            val total = cs.videoBitrate() + (if (cs.audioOn) cs.audioKbps * 1000 else 0)
            "Ориентировочно: %.0f МБ в минуту (%.1f Мбит/с видео).".format(
                total / 8.0 * 60 / 1e6, cs.videoBitrate() / 1e6)
        }
    }
    refresh()

    AlertDialog.Builder(ctx)
        .setTitle("Настройки записи")
        .setView(ScrollView(ctx).apply { addView(box) })
        .setPositiveButton("Сохранить") { _, _ ->
            onSave(if (s.preset == Preset.MANUAL) s.copy(manualMbps = parseMbps()) else s)
        }
        .setNegativeButton("Отмена", null)
        .show()
}
