package com.driverlab.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The speed test's picture check: every driver draws the same test card frame (fixed time), the
 * program saves it (--snapshot), and the pictures are compared pixel by pixel with driver A's.
 */
object SpeedPictures {
    /** A pixel counts as different when one colour channel is off by more than this (0–255). */
    const val TOLERANCE = 24
    /** Pictures "match" when fewer than this share of pixels differ (edges can differ slightly). */
    const val MATCH_SHARE = 0.002

    /** Header (uint32 LE width, height) + RGBA rows, as written by --preview and --snapshot. */
    fun readRaw(f: File): Bitmap? = runCatching {
        val bytes = f.readBytes()
        if (bytes.size < 8) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.getInt(0)
        val h = bb.getInt(4)
        if (w !in 1..8192 || h !in 1..8192 || bytes.size < 8 + w * h * 4) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 8, w * h * 4))
        bmp
    }.getOrNull()

    fun savePng(bmp: Bitmap, f: File) {
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    data class Diff(val share: Double, val image: Bitmap)

    /** Share of pixels that differ, and a picture: the reference dimmed, differing pixels in red. */
    fun compare(ref: Bitmap, other: Bitmap): Diff? {
        if (ref.width != other.width || ref.height != other.height) return null
        val w = ref.width
        val h = ref.height
        val a = IntArray(w * h).also { ref.getPixels(it, 0, w, 0, 0, w, h) }
        val b = IntArray(w * h).also { other.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)
        var bad = 0
        for (i in a.indices) {
            val p = a[i]
            val q = b[i]
            val d = maxOf(
                kotlin.math.abs(Color.red(p) - Color.red(q)),
                kotlin.math.abs(Color.green(p) - Color.green(q)),
                kotlin.math.abs(Color.blue(p) - Color.blue(q)),
            )
            if (d > TOLERANCE) {
                bad++
                out[i] = Color.rgb(255, 32, 32)
            } else {
                out[i] = Color.rgb(Color.red(p) / 4, Color.green(p) / 4, Color.blue(p) / 4)
            }
        }
        val img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        img.setPixels(out, 0, w, 0, 0, w, h)
        return Diff(bad.toDouble() / (w * h), img)
    }

    /** One image with the pictures side by side and a label above each, for Downloads. */
    fun sheet(items: List<Pair<String, Bitmap>>): Bitmap? {
        if (items.isEmpty()) return null
        val w = items.maxOf { it.second.width }
        val h = items.maxOf { it.second.height }
        val label = 44
        val gap = 12
        val out = Bitmap.createBitmap(items.size * w + (items.size - 1) * gap, h + label, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f }
        items.forEachIndexed { i, (name, bmp) ->
            val x = i * (w + gap)
            c.drawText(name, x + 6f, 32f, p)
            c.drawBitmap(bmp, x.toFloat(), label.toFloat(), null)
        }
        return out
    }
}
