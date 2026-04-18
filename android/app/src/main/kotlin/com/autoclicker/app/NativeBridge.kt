package com.autoclicker.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * 把各原生服务的事件推给 Flutter。
 * MainActivity 注册 EventChannel 时会把 sink 注入进来。
 */
object NativeBridge {
    private const val EVENT_CHANNEL = "auto_clicker/events"
    private const val METHOD_CHANNEL = "auto_clicker/methods"

    @Volatile private var sink: EventChannel.EventSink? = null
    @Volatile private var methodChannel: MethodChannel? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(sink: EventChannel.EventSink?) { this.sink = sink }
    fun attachMethod(ch: MethodChannel) { methodChannel = ch }

    fun sendEvent(name: String, payload: Map<String, Any?>? = null) {
        val data: Map<String, Any?> = mapOf("event" to name, "data" to (payload ?: emptyMap<String, Any?>()))
        main.post {
            try { sink?.success(data) } catch (_: Exception) {}
        }
    }

    fun notifyAccessibilityState(enabled: Boolean) =
        sendEvent("state.accessibility", mapOf("enabled" to enabled))

    fun notifyCaptureState(running: Boolean) =
        sendEvent("state.capture", mapOf("running" to running))

    const val EVENTS: String = EVENT_CHANNEL
    const val METHODS: String = METHOD_CHANNEL
}
