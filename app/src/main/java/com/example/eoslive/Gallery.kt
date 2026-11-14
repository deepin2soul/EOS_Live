package com.example.eoslive

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.provider.MediaStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Сохранение картинок в галерею телефона: Pictures/EOSLive. */
object Gallery {
    const val DIR = "Pictures/EOSLive"

    /** Возвращает путь для лога или null, если сохранить не удалось. */
    fun saveBytes(ctx: Context, data: ByteArray, name: String, mime: String): String? {
        return try {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, DIR)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: return null
            "$DIR/$name"
        } catch (_: Exception) {
            null
        }
    }

    fun saveJpeg(ctx: Context, bmp: Bitmap, name: String, quality: Int = 95): String? {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return saveBytes(ctx, bos.toByteArray(), name, "image/jpeg")
    }
}

object PhotoUtil {
    /** JPEG по содержимому (начало FF D8 FF). */
    fun isJpeg(d: ByteArray): Boolean =
        d.size > 3 && d[0] == 0xFF.toByte() && d[1] == 0xD8.toByte() && d[2] == 0xFF.toByte()

    fun isJpegName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg")
    }

    /** Декодирует JPEG для показа: уменьшает слишком большие кадры и применяет поворот из EXIF. */
    fun decodeForView(jpg: ByteArray, maxSide: Int = 2800): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpg, 0, jpg.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > maxSide) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        var b = BitmapFactory.decodeByteArray(jpg, 0, jpg.size, o2) ?: return null
        val rot = try {
            when (ExifInterface(ByteArrayInputStream(jpg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
        if (rot != 0) {
            val m = Matrix()
            m.postRotate(rot.toFloat())
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (r !== b) b.recycle()
            b = r
        }
        return b
    }

    /** Из RAW (CR2) достаёт самый большой встроенный JPEG-превью, который удаётся декодировать. */
    fun embeddedJpeg(d: ByteArray): ByteArray? {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < d.size) {
            if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte() && d[i + 2] == 0xFF.toByte()) {
                starts.add(i)
                i += 3
            } else {
                i++
            }
        }
        var best: ByteArray? = null
        for ((k, s) in starts.withIndex()) {
            val limit = if (k + 1 < starts.size) starts[k + 1] else d.size
            var e = limit - 2
            while (e > s + 2 && !(d[e] == 0xFF.toByte() && d[e + 1] == 0xD9.toByte())) e--
            if (e <= s + 2) continue
            val len = e + 2 - s
            val cur = best
            if (cur != null && len <= cur.size) continue
            val seg = d.copyOfRange(s, e + 2)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(seg, 0, seg.size, o)
            if (o.outWidth > 0) best = seg
        }
        return best
    }
}
