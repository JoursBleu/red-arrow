package com.redarrow.proxy.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用日志收集器
 * 同时输出到 Android Logcat 并收集到 StateFlow 供 UI 展示
 */
object AppLog {
    private const val MAX_LINES = 200
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("D", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("W", tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        append("E", tag, "$msg${t?.let { ": ${it.message}" } ?: ""}")
    }

    private fun append(level: String, tag: String, msg: String) {
        val time = dateFormat.format(Date())
        val line = "$time [$level/$tag] $msg"
        synchronized(this) {
            val current = _logs.value.toMutableList()
            current.add(line)
            if (current.size > MAX_LINES) {
                _logs.value = current.takeLast(MAX_LINES)
            } else {
                _logs.value = current
            }
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
