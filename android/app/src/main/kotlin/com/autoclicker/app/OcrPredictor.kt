package com.autoclicker.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** 单例:懒加载 PP-OCRv3 模型,提供识别/找字接口。 */
object OcrPredictor {
    private const val TAG = "OcrPredictor"
    @Volatile private var ctx: Long = 0L
    @Volatile private var loading: Boolean = false
    private val lock = Any()

    fun isReady(): Boolean = ctx != 0L
    fun isLoading(): Boolean = loading

    /**
     * 初始化(阻塞)。首次调用会把 assets 里的模型拷到私有目录再加载。
     * @return 是否成功
     */
    fun init(appContext: Context, threadNum: Int = 4,
             powerMode: String = "LITE_POWER_HIGH"): Boolean {
        synchronized(lock) {
            if (ctx != 0L) return true
            loading = true
            try {
                val base = File(appContext.filesDir, "ocr_models").apply { mkdirs() }
                val det = copyAsset(appContext, "models/ch_PP-OCRv3_det_slim_infer.nb", File(base, "det.nb"))
                val rec = copyAsset(appContext, "models/ch_PP-OCRv3_rec_slim_infer.nb", File(base, "rec.nb"))
                val cls = copyAsset(appContext, "models/ch_ppocr_mobile_v2.0_cls_slim_opt.nb", File(base, "cls.nb"))
                val cfg = copyAsset(appContext, "models/config.txt", File(base, "config.txt"))
                val dict = copyAsset(appContext, "models/ppocr_keys_v1.txt", File(base, "dict.txt"))
                val ptr = OcrNative.nativeInit(
                    det.absolutePath, cls.absolutePath, rec.absolutePath,
                    cfg.absolutePath, dict.absolutePath,
                    threadNum, powerMode
                )
                if (ptr == 0L) {
                    Log.e(TAG, "nativeInit returned 0")
                    return false
                }
                ctx = ptr
                Log.i(TAG, "OCR ready")
                return true
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                return false
            } finally { loading = false }
        }
    }

    fun release() {
        synchronized(lock) {
            if (ctx != 0L) {
                OcrNative.nativeRelease(ctx)
                ctx = 0L
            }
        }
    }

    /** 识别整张图,返回所有文本框。耗时毫秒 = elapsed。 */
    data class Outcome(val lines: List<OcrNative.Result>, val elapsedMs: Long)

    fun recognize(bmp: Bitmap): Outcome {
        if (ctx == 0L) return Outcome(emptyList(), 0L)
        val input = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                    else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val t0 = System.nanoTime()
        val r = OcrNative.nativeRunBitmap(ctx, input) ?: emptyArray()
        val dt = (System.nanoTime() - t0) / 1_000_000L
        return Outcome(r.toList(), dt)
    }

    /** 在 bmp 里找包含/等于 pattern 的第一行。忽略大小写。 */
    fun findText(bmp: Bitmap, pattern: String, contains: Boolean = true): OcrNative.Result? {
        val res = recognize(bmp).lines
        val needle = pattern.trim()
        return res.firstOrNull {
            val s = it.text
            if (contains) s.contains(needle, ignoreCase = true) else s.equals(needle, ignoreCase = true)
        }
    }

    /** 仅识别 ROI 区域(传 Rect 截屏全图坐标)。返回的坐标是 ROI 内的,需要外面加 offset。 */
    fun recognizeRoi(full: Bitmap, roi: Rect): Outcome {
        if (ctx == 0L) return Outcome(emptyList(), 0L)
        val l = roi.left.coerceAtLeast(0)
        val t = roi.top.coerceAtLeast(0)
        val r = roi.right.coerceAtMost(full.width)
        val b = roi.bottom.coerceAtMost(full.height)
        if (r - l <= 0 || b - t <= 0) return Outcome(emptyList(), 0L)
        val sub = Bitmap.createBitmap(full, l, t, r - l, b - t)
        return recognize(sub)
    }

    private fun copyAsset(ctx: Context, assetPath: String, dst: File): File {
        if (dst.exists() && dst.length() > 0) return dst
        ctx.assets.open(assetPath).use { inp ->
            FileOutputStream(dst).use { out -> inp.copyTo(out) }
        }
        return dst
    }
}
