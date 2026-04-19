package com.autoclicker.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 脚本执行器。
 *
 * 脚本 JSON 格式示例:
 * {
 *   "name": "demo",
 *   "loop": 3,                 // 0 = 无限
 *   "actions": [
 *     { "type": "click", "x": 100, "y": 200 },
 *     { "type": "sleep", "ms": 500 },
 *     { "type": "findColor", "argb": -16711936, "tolerance": 8,
 *       "roi": [0, 0, 1080, 500], "clickOnFound": true,
 *       "onNotFound": [ { "type": "sleep", "ms": 300 } ] },
 *     { "type": "findImage", "path": "/data/.../tpl.png", "threshold": 0.9,
 *       "clickOnFound": true, "onFound": [...], "onNotFound": [...] }
 *   ]
 * }
 */
class ScriptRunner(
    private val ctx: Context,
    private val script: JSONObject,
    private val onLog: (String) -> Unit = {},
    private val onFinished: (Boolean) -> Unit = {}
) {
    private val cancelFlag = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        cancelFlag.set(false)
        job = CoroutineScope(Dispatchers.Default).launch {
            val ok = try {
                val loopTimes = script.optInt("loop", 1)
                val actions = script.optJSONArray("actions") ?: JSONArray()
                var iter = 0
                while (!cancelFlag.get()) {
                    iter++
                    log("▶ 第 $iter 轮")
                    runActions(actions)
                    if (loopTimes in 1..iter) break
                }
                true
            } catch (e: CancellationException) {
                log("⏹ 已停止")
                false
            } catch (e: Exception) {
                log("✖ 异常: ${e.message}")
                Log.e(TAG, "runner error", e)
                false
            }
            withContext(Dispatchers.Main) { onFinished(ok) }
        }
    }

    fun stop() {
        cancelFlag.set(true)
        job?.cancel()
    }

    private suspend fun runActions(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            if (cancelFlag.get()) return
            val a = arr.optJSONObject(i) ?: continue
            runAction(a)
        }
    }

    private suspend fun runAction(a: JSONObject) {
        when (a.optString("type")) {
            "click"     -> doClick(a)
            "longPress" -> doLongPress(a)
            "swipe"     -> doSwipe(a)
            "sleep"     -> delay(a.optLong("ms", 100))
            "sleepRandom" -> {
                val lo = a.optLong("min", 100); val hi = a.optLong("max", lo + 100)
                delay((lo..hi).random())
            }
            "checkColor" -> doCheckColor(a)
            "findColor"  -> doFindColor(a)
            "findImage"  -> doFindImage(a)
            "findText"   -> doFindText(a)
            "loop" -> {
                val n = a.optInt("times", 1)
                val inner = a.optJSONArray("actions") ?: return
                repeat(n) { if (!cancelFlag.get()) runActions(inner) }
            }
            "stop" -> cancelFlag.set(true)
            else -> log("未知动作: ${a.optString("type")}")
        }
    }

    private suspend fun doClick(a: JSONObject) {
        val svc = AutoClickAccessibilityService.instance
        if (svc == null) { log("✖ 无障碍未开启"); return }
        val x = a.optDouble("x").toFloat()
        val y = a.optDouble("y").toFloat()
        val dur = a.optLong("duration", 30L)
        log("点击 ($x, $y)")
        awaitGesture { done -> svc.click(x, y, dur) { done(it) } }
    }

    private suspend fun doLongPress(a: JSONObject) {
        val svc = AutoClickAccessibilityService.instance ?: run { log("✖ 无障碍未开启"); return }
        val x = a.optDouble("x").toFloat()
        val y = a.optDouble("y").toFloat()
        val dur = a.optLong("duration", 600L)
        log("长按 ($x, $y) ${dur}ms")
        awaitGesture { done -> svc.longPress(x, y, dur) { done(it) } }
    }

    private suspend fun doSwipe(a: JSONObject) {
        val svc = AutoClickAccessibilityService.instance ?: run { log("✖ 无障碍未开启"); return }
        val x1 = a.optDouble("x1").toFloat()
        val y1 = a.optDouble("y1").toFloat()
        val x2 = a.optDouble("x2").toFloat()
        val y2 = a.optDouble("y2").toFloat()
        val dur = a.optLong("duration", 300L)
        log("滑动 ($x1,$y1)→($x2,$y2)")
        awaitGesture { done -> svc.swipe(x1, y1, x2, y2, dur) { done(it) } }
    }

    private suspend fun doCheckColor(a: JSONObject) {
        val cap = ScreenCaptureService.instance ?: run { log("✖ 未授权截屏"); return }
        val bmp = cap.acquireBitmap() ?: run { log("✖ 截屏失败"); return }
        val x = a.optInt("x"); val y = a.optInt("y")
        val argb = a.optInt("argb")
        val tol = a.optInt("tolerance", 10)
        val match = ImageMatcher.checkColor(bmp, x, y, argb, tol)
        log("判色 ($x,$y) = ${if (match) "✓" else "✗"}")
        val branch = if (match) "onMatch" else "onMiss"
        a.optJSONArray(branch)?.let { runActions(it) }
    }

    private suspend fun doFindColor(a: JSONObject) {
        val cap = ScreenCaptureService.instance ?: run { log("✖ 未授权截屏"); return }
        val bmp = cap.acquireBitmap() ?: run { log("✖ 截屏失败"); return }
        val argb = a.optInt("argb")
        val tol = a.optInt("tolerance", 10)
        val roi = parseRoi(a.optJSONArray("roi"))
        val p = ImageMatcher.findColor(bmp, argb, tol, roi)
        if (p != null) {
            log("找色 → (${p.x},${p.y})")
            if (a.optBoolean("clickOnFound")) clickAt(p.x.toFloat(), p.y.toFloat())
            a.optJSONArray("onFound")?.let { runActions(it) }
        } else {
            log("找色 未命中")
            a.optJSONArray("onNotFound")?.let { runActions(it) }
        }
    }

    private suspend fun doFindImage(a: JSONObject) {
        val cap = ScreenCaptureService.instance ?: run { log("✖ 未授权截屏"); return }
        val scr = cap.acquireBitmap() ?: run { log("✖ 截屏失败"); return }
        val path = a.optString("path")
        val tpl = BitmapFactory.decodeFile(path) ?: run { log("✖ 模板读取失败: $path"); return }
        val th = a.optDouble("threshold", 0.9).toFloat()
        val roi = parseRoi(a.optJSONArray("roi"))
        val p = ImageMatcher.findTemplate(scr, tpl, th, roi)
        if (p != null) {
            val cx = p.x + tpl.width / 2f
            val cy = p.y + tpl.height / 2f
            log("找图 → (${cx.toInt()},${cy.toInt()}) score=${"%.2f".format(p.score)}")
            if (a.optBoolean("clickOnFound")) clickAt(cx, cy)
            a.optJSONArray("onFound")?.let { runActions(it) }
        } else {
            log("找图 未命中")
            a.optJSONArray("onNotFound")?.let { runActions(it) }
        }
    }

    private suspend fun doFindText(a: JSONObject) {
        val cap = ScreenCaptureService.instance ?: run { log("✖ 未授权截屏"); return }
        val scr = cap.acquireBitmap() ?: run { log("✖ 截屏失败"); return }
        if (!OcrPredictor.isReady()) {
            log("OCR 模型首次加载中…")
            if (!OcrPredictor.init(ctx)) { log("✖ OCR 加载失败"); return }
        }
        val pat = a.optString("text")
        if (pat.isBlank()) { log("✖ 找字:缺 text 参数"); return }
        val contains = a.optBoolean("contains", true)
        val roi = parseRoi(a.optJSONArray("roi"))

        val outcome = if (roi != null) OcrPredictor.recognizeRoi(scr, roi)
                      else OcrPredictor.recognize(scr)
        val needle = pat.trim()
        val hit = outcome.lines.firstOrNull {
            if (contains) it.text.contains(needle, ignoreCase = true)
            else it.text.trim().equals(needle, ignoreCase = true)
        }
        if (hit == null) {
            log("找字 '${pat}' 未命中 (${outcome.elapsedMs}ms)")
            a.optJSONArray("onNotFound")?.let { runActions(it) }
            return
        }
        val offX = roi?.left ?: 0; val offY = roi?.top ?: 0
        val cx = (hit.centerX() + offX).toFloat()
        val cy = (hit.centerY() + offY).toFloat()
        log("找字 '${hit.text}' → (${cx.toInt()},${cy.toInt()}) ${outcome.elapsedMs}ms")
        if (a.optBoolean("clickOnFound")) clickAt(cx, cy)
        a.optJSONArray("onFound")?.let { runActions(it) }
    }

    private suspend fun clickAt(x: Float, y: Float) {
        val svc = AutoClickAccessibilityService.instance ?: return
        awaitGesture { done -> svc.click(x, y, 30L) { done(it) } }
    }

    private suspend fun awaitGesture(block: (done: (Boolean) -> Unit) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                block { ok -> if (cont.isActive) cont.resumeWith(Result.success(ok)) }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWith(Result.success(false))
            }
        }

    private fun parseRoi(arr: JSONArray?): Rect? {
        if (arr == null || arr.length() < 4) return null
        return Rect(arr.optInt(0), arr.optInt(1), arr.optInt(2), arr.optInt(3))
    }

    private fun log(s: String) {
        onLog(s)
        Log.d(TAG, s)
    }

    companion object { private const val TAG = "ScriptRunner" }
}
