package com.naveenapps.expensemanager.commerce

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

internal object CommerceLogo {
    /** Bound decoding before allocating pixels; a small compressed file can have enormous dimensions. */
    fun encode(data: ByteArray): String {
        require(data.size <= 2_000_000) { "حجم الشعار حتى 2 ميغابايت" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "صورة غير صالحة" }
        val options = BitmapFactory.Options()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        options.inSampleSize = sample
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: error("صورة غير صالحة")
        var resized: Bitmap? = null
        try {
            val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
            val image = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            resized = image
            val bytes = ByteArrayOutputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) { "تعذر حفظ الشعار" }
                it.toByteArray()
            }
            require(bytes.size <= 2_000_000) { "حجم الشعار حتى 2 ميغابايت" }
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            if (resized !== bitmap) resized?.recycle()
            bitmap.recycle()
        }
    }
}
