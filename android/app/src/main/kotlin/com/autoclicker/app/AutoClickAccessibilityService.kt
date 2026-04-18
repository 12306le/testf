package com.autoclicker.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务:对外提供点击/长按/滑动能力。
 * 开启方式:设置 > 无障碍 > 自动点击器-无障碍
 */
class AutoClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
        NativeBridge.notifyAccessibilityState(true)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 本项目不依赖 UI 事件,这里留空即可
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        NativeBridge.notifyAccessibilityState(false)
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    // ----- 手势执行 -----

    /** 单击 (x, y) */
    fun click(x: Float, y: Float, duration: Long = 30L, onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 0L, duration.coerceAtLeast(1L), onDone)
    }

    /** 长按 (x, y) */
    fun longPress(x: Float, y: Float, duration: Long = 600L, onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 0L, duration.coerceAtLeast(200L), onDone)
    }

    /** 从 (x1,y1) 滑到 (x2,y2) */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300L,
              onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(path, 0L, duration.coerceAtLeast(50L), onDone)
    }

    private fun dispatch(path: Path, startTime: Long, duration: Long, onDone: ((Boolean) -> Unit)?) {
        val stroke = GestureDescription.StrokeDescription(path, startTime, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) {
                mainHandler.post { onDone?.invoke(true) }
            }
            override fun onCancelled(description: GestureDescription?) {
                mainHandler.post { onDone?.invoke(false) }
            }
        }, null)
        if (!ok) mainHandler.post { onDone?.invoke(false) }
    }

    companion object {
        private const val TAG = "AutoClickA11y"
        @Volatile
        var instance: AutoClickAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
