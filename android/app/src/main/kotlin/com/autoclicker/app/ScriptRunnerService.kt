package com.autoclicker.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/** 脚本执行前台服务。持有 ScriptRunner 生命周期。 */
class ScriptRunnerService : Service() {

    private var runner: ScriptRunner? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("就绪"))
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_RUN -> {
                val json = intent.getStringExtra(EXTRA_SCRIPT) ?: return START_NOT_STICKY
                runScript(json)
            }
            ACTION_STOP -> stopScript()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runner?.stop()
        runner = null
        instance = null
        super.onDestroy()
    }

    private fun runScript(json: String) {
        runner?.stop()
        val obj = try { JSONObject(json) } catch (e: Exception) {
            NativeBridge.sendEvent("runner.log", mapOf("line" to "✖ 脚本 JSON 解析失败"))
            return
        }
        updateNotif("运行中: ${obj.optString("name", "脚本")}")
        NativeBridge.sendEvent("runner.state", mapOf("running" to true))
        runner = ScriptRunner(
            applicationContext, obj,
            onLog = { line -> NativeBridge.sendEvent("runner.log", mapOf("line" to line)) },
            onFinished = { ok ->
                NativeBridge.sendEvent("runner.state", mapOf("running" to false, "ok" to ok))
                updateNotif("就绪")
            }
        ).also { it.start() }
    }

    private fun stopScript() {
        runner?.stop()
        runner = null
        NativeBridge.sendEvent("runner.state", mapOf("running" to false, "ok" to false))
        updateNotif("就绪")
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val chId = "script_runner"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(chId, "脚本执行", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, chId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("自动点击器")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1003
        const val ACTION_RUN = "com.autoclicker.runner.run"
        const val ACTION_STOP = "com.autoclicker.runner.stop"
        const val EXTRA_SCRIPT = "script"

        @Volatile var instance: ScriptRunnerService? = null
            private set

        fun run(ctx: Context, scriptJson: String) {
            val i = Intent(ctx, ScriptRunnerService::class.java).apply {
                action = ACTION_RUN
                putExtra(EXTRA_SCRIPT, scriptJson)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, ScriptRunnerService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }

        fun isRunning(): Boolean = instance?.runner != null
    }
}
