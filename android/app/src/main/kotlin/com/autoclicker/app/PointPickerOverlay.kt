package com.autoclicker.app

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout

/**
 * 全屏透明覆盖层:用户点击即回调坐标。
 * pickColor = true 时改为十字准星拖动选点,确认后请求截屏并取色。
 */
class PointPickerOverlay private constructor(ctx: Context, private val pickColor: Boolean)
    : FrameLayout(ctx) {

    private var cx = -1f
    private var cy = -1f
    private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val paintTip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(14f); isFakeBoldText = true
    }
    private val paintTipBg = Paint().apply { color = Color.parseColor("#CC000000") }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.parseColor("#33000000"))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val tip = if (pickColor) "拖动十字到目标,双击确认取色,长按取消"
                  else "点击屏幕任意位置采集坐标,长按取消"
        val pad = dp(10f)
        val tw = paintTip.measureText(tip)
        canvas.drawRect(pad, pad, pad + tw + pad * 2, pad * 4, paintTipBg)
        canvas.drawText(tip, pad * 2, pad * 3, paintTip)

        if (pickColor && cx >= 0) {
            canvas.drawLine(cx - dp(20f), cy, cx + dp(20f), cy, paintCross)
            canvas.drawLine(cx, cy - dp(20f), cx, cy + dp(20f), paintCross)
            canvas.drawCircle(cx, cy, dp(14f), paintCross)
        }
    }

    private var lastTap = 0L
    private var longPressed = false
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                cx = e.x; cy = e.y
                invalidate()
                postDelayed({
                    if (e.eventTime == e.downTime && isAttachedToWindow) {
                        // long press -> cancel
                    }
                }, 800)
                longPressed = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pickColor) { cx = e.x; cy = e.y; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dur = e.eventTime - e.downTime
                if (dur > 700) { dismiss(); return true }
                if (pickColor) {
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 350) {
                        confirmColor(cx, cy)
                        return true
                    }
                    lastTap = now
                } else {
                    // 单击即采点
                    confirmPoint(e.x, e.y)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun confirmPoint(x: Float, y: Float) {
        NativeBridge.sendEvent("picker.point", mapOf("x" to x.toInt(), "y" to y.toInt()))
        dismiss()
        bringAppToFront()
    }

    private fun confirmColor(x: Float, y: Float) {
        val xi = x.toInt(); val yi = y.toInt()
        dismiss()
        ScreenCaptureService.pickColor(context.applicationContext, xi, yi) { argb ->
            if (argb == null) {
                NativeBridge.sendEvent("picker.color.failed", mapOf("x" to xi, "y" to yi))
            } else {
                NativeBridge.sendEvent("picker.color", mapOf(
                    "x" to xi, "y" to yi,
                    "argb" to argb,
                    "hex" to String.format("#%08X", argb)
                ))
            }
            bringAppToFront()
        }
    }

    private fun bringAppToFront() {
        val i = android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                      android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                      android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(i)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun dismiss() {
        FloatingWindowService.setVisible(true)
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(this)
        } catch (_: Exception) {}
        current = null
    }

    companion object {
        private var current: PointPickerOverlay? = null

        fun show(ctx: Context, pickColor: Boolean = false) {
            if (current != null) return
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            val overlay = PointPickerOverlay(ctx, pickColor)
            FloatingWindowService.setVisible(false)
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(overlay, lp)
            current = overlay
        }
    }
}
