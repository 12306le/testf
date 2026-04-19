package com.autoclicker.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

/**
 * 全局崩溃上报。
 *  - Java 未捕获异常:同步写文件 + 同步上报 + 调原有 handler
 *  - Native crash:C++ 层 signal handler 写 native.txt,APP 下次启动时由 Java 侧上报
 *
 * 上报 endpoint 是临时的 webhook.site 地址,7 天过期。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val ENDPOINT =
        "https://webhook.site/09edc5b0-386c-429e-ba1f-fb0d86858b63"

    fun install(appContext: Context) {
        val crashDir = File(appContext.filesDir, "crashes").apply { mkdirs() }

        // 1. 先尝试上传之前留下的崩溃文件(包括 native 的)
        Thread {
            try { uploadAll(appContext, crashDir) } catch (_: Throwable) {}
        }.start()

        // 2. 注册 Java 未捕获异常处理器
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                val file = File(crashDir, "java_${System.currentTimeMillis()}.txt")
                file.writeText(buildReport(appContext, "java", thread.name, err))
                Log.e(TAG, "saved java crash: ${file.name}")
                uploadFile(appContext, file)   // 同步上报
            } catch (t: Throwable) {
                Log.e(TAG, "save/upload failed", t)
            }
            if (prev != null) prev.uncaughtException(thread, err)
            else exitProcess(2)
        }

        // 3. 让 native 把崩溃文件写到固定路径;load libNative 若成功即自动装 handler
        //    如果 Native 加载失败,Java handler 会捕获 UnsatisfiedLinkError
        try {
            System.loadLibrary("Native")
            OcrNative.installCrashHandler(File(crashDir, "native.txt").absolutePath)
        } catch (t: Throwable) {
            val f = File(crashDir, "loadlib_${System.currentTimeMillis()}.txt")
            f.writeText(buildReport(appContext, "loadlib", "main", t))
            uploadFile(appContext, f)
            Log.e(TAG, "loadLibrary failed", t)
        }
    }

    private fun buildReport(ctx: Context, tag: String, thread: String, err: Throwable): String =
        buildString {
            appendLine("=== $tag crash ===")
            appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}  (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("App: ${ctx.packageName}")
            appendLine("Thread: $thread")
            appendLine()
            appendLine(err.stackTraceToString())
        }

    private fun uploadAll(ctx: Context, dir: File) {
        val files = dir.listFiles() ?: return
        for (f in files) uploadFile(ctx, f)
    }

    private fun uploadFile(ctx: Context, file: File) {
        if (!file.exists() || file.length() == 0L) { file.delete(); return }
        try {
            val body = file.readBytes()
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.setRequestProperty("X-Crash-File", file.name)
            conn.setRequestProperty("X-Package", ctx.packageName)
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                file.delete()
                Log.i(TAG, "uploaded ${file.name}")
            } else {
                Log.w(TAG, "upload failed code=$code for ${file.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "upload exception for ${file.name}: ${t.message}")
        }
    }
}
