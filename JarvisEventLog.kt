package com.example.jarvis

import android.os.Handler
import android.os.Looper

/**
 * A tiny shared log so the app's UI can show what Jarvis is hearing and
 * saying in real time, without a database or extra libraries. Keeps the
 * last [maxEntries] lines; MainActivity subscribes while it's visible.
 */
object JarvisEventLog {

    private const val maxEntries = 100
    private val entries = mutableListOf<String>()
    private var listener: ((List<String>) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun add(line: String) {
        entries.add(line)
        if (entries.size > maxEntries) entries.removeAt(0)
        val snapshot = entries.toList()
        mainHandler.post { listener?.invoke(snapshot) }
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    fun setListener(l: ((List<String>) -> Unit)?) {
        listener = l
    }
}
