package com.autoclicker.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 图色工具:找色 / 判色 / 模板找图。
 * 纯 Kotlin 实现,无 OpenCV。算法不追求极致,适合 < 1080p 屏幕的实时使用。
 */
object ImageMatcher {

    data class Point(val x: Int, val y: Int, val score: Float)

    /** 判断 (x, y) 处颜色是否与 target 接近 */
    fun checkColor(bmp: Bitmap, x: Int, y: Int, target: Int, tolerance: Int = 10): Boolean {
        if (x !in 0 until bmp.width || y !in 0 until bmp.height) return false
        return colorClose(bmp.getPixel(x, y), target, tolerance)
    }

    /** 在 roi 中找第一个颜色匹配的像素,默认全屏。tolerance 是 R/G/B 各通道最大允许差 */
    fun findColor(bmp: Bitmap, target: Int, tolerance: Int = 10, roi: Rect? = null): Point? {
        val r = clampRoi(roi, bmp)
        val pixels = IntArray(r.width() * r.height())
        bmp.getPixels(pixels, 0, r.width(), r.left, r.top, r.width(), r.height())
        val tR = Color.red(target); val tG = Color.green(target); val tB = Color.blue(target)
        for (i in pixels.indices) {
            val p = pixels[i]
            if (abs(((p shr 16) and 0xFF) - tR) <= tolerance &&
                abs(((p shr 8) and 0xFF) - tG) <= tolerance &&
                abs((p and 0xFF) - tB) <= tolerance) {
                val x = r.left + i % r.width()
                val y = r.top + i / r.width()
                return Point(x, y, 1f)
            }
        }
        return null
    }

    /**
     * 模板找图:金字塔降采样 + SAD 匹配。
     * @param threshold 0~1,1 表示完全一致。典型 0.85~0.95。
     * @return 左上角坐标;配合 template 宽高可算中心
     */
    fun findTemplate(screen: Bitmap, template: Bitmap, threshold: Float = 0.9f,
                     roi: Rect? = null, scale: Int = 4): Point? {
        if (template.width < 8 || template.height < 8) return null
        val r = clampRoi(roi, screen)
        if (r.width() < template.width || r.height() < template.height) return null

        // 1. 缩小
        val s = max(1, scale)
        val scrSmall = Bitmap.createBitmap(screen, r.left, r.top, r.width(), r.height())
            .let { if (s == 1) it else Bitmap.createScaledBitmap(it, it.width / s, it.height / s, true) }
        val tplSmall = if (s == 1) template
                       else Bitmap.createScaledBitmap(template, template.width / s, template.height / s, true)

        val coarse = sadSearch(scrSmall, tplSmall) ?: return null

        // 2. 映射回原图位置并在 ±s 附近精搜
        val cx = coarse.x * s + r.left
        val cy = coarse.y * s + r.top
        val pad = s * 2
        val rx = (cx - pad).coerceAtLeast(r.left)
        val ry = (cy - pad).coerceAtLeast(r.top)
        val rx2 = (cx + pad + template.width).coerceAtMost(r.right)
        val ry2 = (cy + pad + template.height).coerceAtMost(r.bottom)
        if (rx2 - rx < template.width || ry2 - ry < template.height) return null

        val scrCrop = Bitmap.createBitmap(screen, rx, ry, rx2 - rx, ry2 - ry)
        val fine = sadSearch(scrCrop, template) ?: return null

        val finalX = fine.x + rx
        val finalY = fine.y + ry
        return if (fine.score >= threshold) Point(finalX, finalY, fine.score) else null
    }

    // ---- 内部:SAD 暴力搜索,返回最佳位置及相似度 ----
    private fun sadSearch(scr: Bitmap, tpl: Bitmap): Point? {
        val sw = scr.width; val sh = scr.height
        val tw = tpl.width; val th = tpl.height
        if (sw < tw || sh < th) return null

        val scrPix = IntArray(sw * sh); scr.getPixels(scrPix, 0, sw, 0, 0, sw, sh)
        val tplPix = IntArray(tw * th); tpl.getPixels(tplPix, 0, tw, 0, 0, tw, th)

        var bestSad = Long.MAX_VALUE
        var bx = 0; var by = 0
        val maxY = sh - th
        val maxX = sw - tw

        // 步长随分辨率调整,太小屏每像素扫描,否则 2
        val step = if (sw <= 480) 1 else 2

        var y = 0
        while (y <= maxY) {
            var x = 0
            while (x <= maxX) {
                var sad = 0L
                // 采样:每 2 行/列取一点加速,对图色匹配影响可接受
                val rowStep = 2; val colStep = 2
                var ty = 0
                while (ty < th) {
                    val sRow = (y + ty) * sw + x
                    val tRow = ty * tw
                    var tx = 0
                    while (tx < tw) {
                        val sp = scrPix[sRow + tx]
                        val tp = tplPix[tRow + tx]
                        sad += abs(((sp shr 16) and 0xFF) - ((tp shr 16) and 0xFF)) +
                               abs(((sp shr 8) and 0xFF) - ((tp shr 8) and 0xFF)) +
                               abs((sp and 0xFF) - (tp and 0xFF))
                        tx += colStep
                    }
                    ty += rowStep
                }
                if (sad < bestSad) { bestSad = sad; bx = x; by = y }
                x += step
            }
            y += step
        }
        // 归一化 score:0 差异 -> 1,最大差异 -> 0
        val samples = (tw / 2) * (th / 2)
        val maxSad = 255L * 3L * samples
        val score = 1f - bestSad.toFloat() / maxSad
        return Point(bx, by, score.coerceIn(0f, 1f))
    }

    private fun clampRoi(roi: Rect?, bmp: Bitmap): Rect {
        if (roi == null) return Rect(0, 0, bmp.width, bmp.height)
        return Rect(
            max(0, roi.left), max(0, roi.top),
            min(bmp.width, roi.right), min(bmp.height, roi.bottom)
        )
    }

    private fun colorClose(a: Int, b: Int, tol: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) <= tol &&
        abs(Color.green(a) - Color.green(b)) <= tol &&
        abs(Color.blue(a) - Color.blue(b)) <= tol
}
