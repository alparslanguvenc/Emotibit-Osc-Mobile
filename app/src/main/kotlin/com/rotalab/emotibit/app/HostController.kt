package com.rotalab.emotibit.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.rotalab.emotibit.protocol.EmotiBitHost
import com.rotalab.emotibit.protocol.HostConfig
import com.rotalab.emotibit.protocol.HostEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/**
 * Process-wide owner of the [EmotiBitHost]. [EmotiBitService] starts and stops it; the UI reads its
 * state and sends commands (always off the main thread: commands write to sockets).
 */
object HostController {
    val host = EmotiBitHost(HostConfig())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var prefs: SharedPreferences
    lateinit var log: SessionLog
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("emotibit", Context.MODE_PRIVATE)
        val dir = context.getExternalFilesDir(null)?.let { File(it, "logs") } ?: File(context.filesDir, "logs")
        log = SessionLog(dir)
        scope.launch { host.events.collect { log.append(it) } }
    }

    var savedTarget: String?
        get() = prefs.getString("target", null)
        set(v) { prefs.edit { if (v == null) remove("target") else putString("target", v) } }

    fun connect(deviceId: String) {
        savedTarget = deviceId
        io { host.connect(deviceId) }
    }

    fun forget() {
        savedTarget = null
        io { host.disconnect() }
    }

    fun io(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}

/** Appends events as JSON lines to logs/emotibit-YYYY-MM-DD.jsonl and keeps recent ones for the UI. */
class SessionLog(private val dir: File) {
    private val _recent = MutableStateFlow<List<HostEvent>>(emptyList())
    val recent: StateFlow<List<HostEvent>> = _recent.asStateFlow()

    fun currentFile(): File = File(dir, "emotibit-${LocalDate.now()}.jsonl")

    @Synchronized
    fun append(e: HostEvent) {
        if (e.important) _recent.value = (listOf(e) + _recent.value).take(200)
        try {
            dir.mkdirs()
            currentFile().appendText(toJson(e) + "\n")
        } catch (_: Exception) {
        }
    }

    companion object {
        fun toJson(e: HostEvent): String {
            val sb = StringBuilder("{")
            sb.append("\"time\":").append(q(e.wallTime)).append(",\"monoMs\":").append(e.monoMs)
                .append(",\"type\":").append(q(e.type))
            for ((k, v) in e.fields) sb.append(',').append(q(k)).append(':').append(q(v))
            return sb.append('}').toString()
        }

        private fun q(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
            return sb.append('"').toString()
        }
    }
}
