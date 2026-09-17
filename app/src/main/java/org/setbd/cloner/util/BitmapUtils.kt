package org.setbd.cloner.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bitmap helpers used for clone icons: capture at import time, compose the
 * numbered badge that distinguishes instances, and persist custom icons.
 */
object BitmapUtils {

    const val ICON_SIZE = 192

    fun drawableToBitmap(drawable: Drawable, size: Int = ICON_SIZE): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap.width == size) {
            return drawable.bitmap
        }
        val width = max(drawable.intrinsicWidth.takeIf { it > 0 } ?: size, 1)
        val height = max(drawable.intrinsicHeight.takeIf { it > 0 } ?: size, 1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        val scale = size.toFloat() / max(width, height)
        canvas.save()
        canvas.scale(scale, scale)
        drawable.draw(canvas)
        canvas.restore()
        return bitmap
    }

    fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    fun drawableToPngBytes(drawable: Drawable, size: Int = ICON_SIZE): ByteArray =
        bitmapToPngBytes(drawableToBitmap(drawable, size))

    fun decodeFile(file: File): Bitmap? =
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()

    /**
     * Composes a clone icon: base app icon + a rounded badge with the clone
     * number, so instances are distinguishable at a glance everywhere
     * (launcher grid, shortcuts, recents-style lists).
     */
    fun composeCloneIcon(
        context: Context,
        base: Bitmap?,
        cloneNumber: Int
    ): Bitmap {
        val size = ICON_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (base != null) {
            val src = Rect(0, 0, base.width, base.height)
            val dst = Rect(0, 0, size, size)
            canvas.drawBitmap(base, src, dst, null)
        } else {
            canvas.drawColor(Color.TRANSPARENT)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101324.toInt() }
            canvas.drawCircle(size / 2f, size / 2f, size / 2.4f, paint)
        }

        val badgeRadius = size * 0.19f
        val badgeCenterX = size - badgeRadius - size * 0.06f
        val badgeCenterY = badgeRadius + size * 0.06f

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6FD3F2.toInt()
        }
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF101324.toInt()
            style = Paint.Style.STROKE
            strokeWidth = size * 0.025f
        }
        canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius, badgePaint)
        canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius, ringPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF101324.toInt()
            textSize = badgeRadius * 1.1f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val label = if (cloneNumber > 99) "99" else cloneNumber.toString()
        val textY = badgeCenterY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, badgeCenterX, textY, textPaint)
        return bitmap
    }
}
