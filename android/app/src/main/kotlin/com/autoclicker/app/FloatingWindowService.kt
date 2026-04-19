package com.autoclicker.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * 悬浮控制面板。折叠时是一个小球(可拖动),展开后是卡片面板。
 * 面板按钮:运行/停止/拾取坐标/拾取颜色/取字(OCR)/OCR 测试页/回到应用
 */
class FloatingWindowService : Service() {

    private lateinit var wm: WindowManager
    private var rootView: View? = null
    private var ballView: View? = null
    private var panelView: View? = null
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
        rootView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        rootView = null
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
            x = 16; y = 200
        }
        params = lp

        val root = FrameLayout(ctx)

        // 小球
        val ball = TextView(ctx).apply {
            text = "⚡"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            val s = dp(52f).toInt()
            layoutParams = FrameLayout.LayoutParams(s, s)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#6366F1"), Color.parseColor("#8B5CF6"))
                orientation = GradientDrawable.Orientation.TL_BR
            }
            elevation = dp(4f)
            setOnClickListener { toggle() }
        }
        ballView = ball
        root.addView(ball)

        // 面板
        val panel = makePanel(ctx).apply { visibility = View.GONE }
        panelView = panel
        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(56f).toInt() })

        attachDrag(ball, lp)

        wm.addView(root, lp)
        rootView = root
    }

    private fun toggle() {
        expanded = !expanded
        panelView?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun makePanel(ctx: Context): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(18f)
                setColor(Color.parseColor("#F2181A2B"))
            }
            elevation = dp(8f)
        }

        fun row() = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val r1 = row()
        val r2 = row()

        // 主操作
        r1.addView(makeButton(ctx, "▶", "运行", Color.parseColor("#22C55E")) {
            NativeBridge.sendEvent("floating.run")
        })
        r1.addView(makeButton(ctx, "■", "停止", Color.parseColor("#EF4444")) {
            NativeBridge.sendEvent("floating.stop")
        })
        r1.addView(makeButton(ctx, "⌂", "主界面", Color.parseColor("#64748B")) {
            val i = Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
            toggle()
        })

        // 取点/取色/取字
        r2.addView(makeButton(ctx, "✛", "坐标", Color.parseColor("#F59E0B")) {
            PointPickerOverlay.show(applicationContext, pickColor = false)
            toggle()
        })
        r2.addView(makeButton(ctx, "◉", "颜色", Color.parseColor("#A855F7")) {
            PointPickerOverlay.show(applicationContext, pickColor = true)
            toggle()
        })
        r2.addView(makeButton(ctx, "文", "找字", Color.parseColor("#0EA5E9")) {
            NativeBridge.sendEvent("floating.ocrTest")
            val i = Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
            toggle()
        })

        container.addView(r1)
        container.addView(r2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6f).toInt() })
        return container
    }

    private fun makeButton(ctx: Context, icon: String, label: String, color: Int,
                           onClick: () -> Unit): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
            val size = dp(62f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(3f).toInt(); marginEnd = dp(3f).toInt() }
        }
        val iconBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val ripple = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#40FFFFFF")),
            iconBg, null)
        val iconView = TextView(ctx).apply {
            text = icon
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val s = dp(40f).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            background = ripple
        }
        val labelView = TextView(ctx).apply {
            text = label
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, dp(3f).toInt(), 0, 0)
        }
        col.addView(iconView)
        col.addView(labelView)
        col.setOnClickListener { onClick() }
        return col
    }

    private fun attachDrag(target: View, lp: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        target.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    if (moved) {
                        lp.x = startX + dx; lp.y = startY + dy
                        try { wm.updateViewLayout(rootView, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    true
                }
                else -> false
            }
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

        fun setVisible(visible: Boolean) {
            val s = instance ?: return
            s.rootView?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }
}
