package com.autoclicker.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * MediaProjection 截屏前台服务。
 * Activity 拿到用户授权后 startForegroundService + startCapture(resultCode, data)。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var density = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                if (data != null && code != 0) startCapture(code, data)
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture(code: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, mainHandler)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "auto_clicker_capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, mainHandler
        )
        NativeBridge.notifyCaptureState(true)
        Log.i(TAG, "截屏启动 ${width}x${height}")
    }

    private fun stopCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null; imageReader = null; projection = null
        NativeBridge.notifyCaptureState(false)
    }

    /** 抓取一帧,返回 Bitmap(RGBA_8888) */
    fun acquireBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "acquireBitmap failed: ${e.message}")
            null
        } finally {
            try { image?.close() } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "screen_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(channelId, "屏幕捕获", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_vp_phone_call)
            .setContentTitle("屏幕捕获中")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCapture"
        private const val NOTIF_ID = 1002
        const val ACTION_START = "com.autoclicker.capture.start"
        const val ACTION_STOP = "com.autoclicker.capture.stop"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        @Volatile var instance: ScreenCaptureService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ScreenCaptureService::class.java).apply { action = ACTION_STOP })
        }

        /** 取 (x, y) 处像素颜色 (ARGB int)。异步回调。 */
        fun pickColor(ctx: Context, x: Int, y: Int, cb: (Int?) -> Unit) {
            val svc = instance
            if (svc == null) { cb(null); return }
            // 让系统刷新一帧再取
            Handler(Looper.getMainLooper()).postDelayed({
                val bmp = svc.acquireBitmap()
                if (bmp == null || x !in 0 until bmp.width || y !in 0 until bmp.height) {
                    cb(null)
                } else {
                    cb(bmp.getPixel(x, y))
                }
            }, 120)
        }
    }
}
