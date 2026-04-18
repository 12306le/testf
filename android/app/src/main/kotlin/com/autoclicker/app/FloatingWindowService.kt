package com.autoclicker.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * 悬浮控制面板:一个可拖动的按钮,展开后显示 开始/停止/拾取坐标/拾取颜色/编辑 等入口
 */
class FloatingWindowService : Service() {

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        showPanel()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- UI ----------
    private fun showPanel() {
        val ctx: Context = this
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }
        params = lp

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24f)
                setColor(Color.parseColor("#E6222222"))
            }
            setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
        }

        val ball = makeButton("●", Color.parseColor("#4CAF50"))
        root.addView(ball)

        val expand = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        root.addView(expand)

        val btnRun = makeButton("▶", Color.parseColor("#2196F3"))
        val btnStop = makeButton("■", Color.parseColor("#F44336"))
        val btnPick = makeButton("✛", Color.parseColor("#FF9800"))
        val btnColor = makeButton("◉", Color.parseColor("#9C27B0"))
        val btnHome = makeButton("⌂", Color.parseColor("#607D8B"))
        listOf(btnRun, btnStop, btnPick, btnColor, btnHome).forEach { expand.addView(it) }

        ball.setOnClickListener {
            expanded = !expanded
            expand.visibility = if (expanded) View.VISIBLE else View.GONE
        }

        btnRun.setOnClickListener { NativeBridge.sendEvent("floating.run") }
        btnStop.setOnClickListener { NativeBridge.sendEvent("floating.stop") }
        btnPick.setOnClickListener { PointPickerOverlay.show(applicationContext) }
        btnColor.setOnClickListener { PointPickerOverlay.show(applicationContext, pickColor = true) }
        btnHome.setOnClickListener {
            val i = Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        }

        // 拖动:只绑 ball,避免多 view 绑定事件冲突
        attachDrag(ball, lp)

        wm.addView(root, lp)
        panel = root
    }

    private fun attachDrag(target: View, lp: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        target.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y; moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    try { wm.updateViewLayout(panel, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { v.performClick() }
                    moved
                }
                else -> false
            }
        }
    }

    private fun makeButton(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        val size = dp(40f).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginStart = dp(4f).toInt()
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun buildNotification(): Notification {
        val channelId = "floating_panel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "悬浮面板", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("自动点击器")
            .setContentText("悬浮面板运行中")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001
        @Volatile var instance: FloatingWindowService? = null
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingWindowService::class.java))
        }

        fun isRunning(): Boolean = instance != null

        /** 在拾取坐标/色点时临时隐藏面板,避免遮挡 */
        fun setVisible(visible: Boolean) {
            val s = instance ?: return
            s.panel?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }
}
