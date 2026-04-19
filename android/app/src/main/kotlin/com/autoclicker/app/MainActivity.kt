package com.autoclicker.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : FlutterActivity() {

    private var pendingMediaProjection: MethodChannel.Result? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val messenger = flutterEngine.dartExecutor.binaryMessenger

        EventChannel(messenger, NativeBridge.EVENTS).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    NativeBridge.attach(events)
                }
                override fun onCancel(arguments: Any?) {
                    NativeBridge.attach(null)
                }
            }
        )

        val mc = MethodChannel(messenger, NativeBridge.METHODS)
        NativeBridge.attachMethod(mc)
        mc.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "getStates" -> result.success(mapOf(
                        "overlay" to canDrawOverlays(),
                        "accessibility" to isAccessibilityEnabled(),
                        "capture" to ScreenCaptureService.isRunning(),
                        "floating" to FloatingWindowService.isRunning(),
                        "running" to ScriptRunnerService.isRunning(),
                        "ocr" to OcrPredictor.isReady(),
                        "ocrLoading" to OcrPredictor.isLoading()
                    ))
                    "openOverlaySettings" -> { openOverlaySettings(); result.success(null) }
                    "openAccessibilitySettings" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        result.success(null)
                    }
                    "requestMediaProjection" -> {
                        pendingMediaProjection = result
                        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        startActivityForResult(mgr.createScreenCaptureIntent(), RC_MEDIA_PROJECTION)
                    }
                    "stopCapture" -> { ScreenCaptureService.stop(this); result.success(true) }
                    "startFloating" -> { FloatingWindowService.start(this); result.success(true) }
                    "stopFloating"  -> { FloatingWindowService.stop(this); result.success(true) }
                    "runScript" -> {
                        val json = call.argument<String>("json") ?: ""
                        ScriptRunnerService.run(this, json)
                        result.success(true)
                    }
                    "stopScript" -> { ScriptRunnerService.stop(this); result.success(true) }
                    "captureFrame" -> {
                        val bmp = ScreenCaptureService.instance?.acquireBitmap()
                        if (bmp == null) result.success(null)
                        else result.success(saveBitmap(bmp, "frame_${System.currentTimeMillis()}.png"))
                    }
                    "cropTemplate" -> {
                        val path = call.argument<String>("path")
                        val l = call.argument<Int>("l") ?: 0
                        val t = call.argument<Int>("t") ?: 0
                        val r = call.argument<Int>("r") ?: 0
                        val b = call.argument<Int>("b") ?: 0
                        val saved = cropAndSave(path, l, t, r, b)
                        result.success(saved)
                    }
                    "pickColorAt" -> {
                        val x = call.argument<Int>("x") ?: 0
                        val y = call.argument<Int>("y") ?: 0
                        ScreenCaptureService.pickColor(this, x, y) { argb ->
                            result.success(argb)
                        }
                    }
                    "startPicker" -> {
                        val color = call.argument<Boolean>("color") ?: false
                        // 必须先有悬浮窗权限
                        if (!canDrawOverlays()) { result.success(false); return@setMethodCallHandler }
                        if (!FloatingWindowService.isRunning()) FloatingWindowService.start(this)
                        moveTaskToBack(true)
                        PointPickerOverlay.show(applicationContext, pickColor = color)
                        result.success(true)
                    }
                    "initOcr" -> {
                        NativeBridge.sendEvent("state.ocr", mapOf("ready" to OcrPredictor.isReady(), "loading" to true))
                        CoroutineScope(Dispatchers.IO).launch {
                            val ok = OcrPredictor.init(applicationContext)
                            NativeBridge.sendEvent("state.ocr", mapOf("ready" to ok, "loading" to false))
                        }
                        result.success(true)
                    }
                    "ocrRecognizeFile" -> {
                        val path = call.argument<String>("path") ?: ""
                        val roi = call.argument<List<Int>>("roi")
                        if (!OcrPredictor.isReady()) {
                            OcrPredictor.init(applicationContext)
                        }
                        val bmp = BitmapFactory.decodeFile(path)
                        if (bmp == null) { result.success(null); return@setMethodCallHandler }
                        val outcome = if (roi != null && roi.size == 4) {
                            OcrPredictor.recognizeRoi(bmp, Rect(roi[0], roi[1], roi[2], roi[3]))
                        } else OcrPredictor.recognize(bmp)
                        val offX = roi?.get(0) ?: 0
                        val offY = roi?.get(1) ?: 0
                        val lines = outcome.lines.map { r ->
                            mapOf(
                                "text" to r.text,
                                "score" to r.score,
                                "box" to r.box.mapIndexed { i, v ->
                                    if (i % 2 == 0) v + offX else v + offY
                                }
                            )
                        }
                        result.success(mapOf("elapsedMs" to outcome.elapsedMs, "lines" to lines))
                    }
                    "ocrRecognizeFrame" -> {
                        val roi = call.argument<List<Int>>("roi")
                        if (!OcrPredictor.isReady()) {
                            OcrPredictor.init(applicationContext)
                        }
                        val bmp = ScreenCaptureService.instance?.acquireBitmap()
                        if (bmp == null) { result.success(null); return@setMethodCallHandler }
                        val outcome = if (roi != null && roi.size == 4) {
                            OcrPredictor.recognizeRoi(bmp, Rect(roi[0], roi[1], roi[2], roi[3]))
                        } else OcrPredictor.recognize(bmp)
                        val offX = roi?.get(0) ?: 0
                        val offY = roi?.get(1) ?: 0
                        val lines = outcome.lines.map { r ->
                            mapOf(
                                "text" to r.text,
                                "score" to r.score,
                                "box" to r.box.mapIndexed { i, v ->
                                    if (i % 2 == 0) v + offX else v + offY
                                }
                            )
                        }
                        result.success(mapOf("elapsedMs" to outcome.elapsedMs, "lines" to lines))
                    }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                Log.e(TAG, "method error ${call.method}", e)
                result.error("ERR", e.message, null)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RC_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ScreenCaptureService.start(this, resultCode, data)
                pendingMediaProjection?.success(true)
            } else {
                pendingMediaProjection?.success(false)
            }
            pendingMediaProjection = null
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ---- helpers ----

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun openOverlaySettings() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, AutoClickAccessibilityService::class.java).flattenToString()
        val enabled = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (_: Exception) { 0 }
        if (enabled != 1) return false
        val list = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(list)
        for (s in splitter) {
            if (s.equals(expected, ignoreCase = true)) return true
        }
        return AutoClickAccessibilityService.isRunning()
    }

    private fun saveBitmap(bmp: Bitmap, name: String): String {
        val dir = File(filesDir, "screenshots").apply { mkdirs() }
        val f = File(dir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.absolutePath
    }

    private fun cropAndSave(path: String?, l: Int, t: Int, r: Int, b: Int): String? {
        if (path.isNullOrEmpty()) return null
        val src = BitmapFactory.decodeFile(path) ?: return null
        val w = (r - l).coerceAtLeast(1).coerceAtMost(src.width - l)
        val h = (b - t).coerceAtLeast(1).coerceAtMost(src.height - t)
        if (w <= 0 || h <= 0) return null
        val crop = Bitmap.createBitmap(src, l, t, w, h)
        val dir = File(filesDir, "templates").apply { mkdirs() }
        val f = File(dir, "tpl_${System.currentTimeMillis()}.png")
        FileOutputStream(f).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.absolutePath
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val RC_MEDIA_PROJECTION = 9001
    }
}
