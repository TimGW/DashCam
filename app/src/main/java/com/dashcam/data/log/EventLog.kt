package com.dashcam.data.log

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Persistent, human-readable log of recording events (starts, stops and why, camera problems,
 * storage cleanup), so "why did it stop recording at 14:32?" can be answered afterwards.
 * Writes happen on a background thread; the file is trimmed to its newest half past [maxBytes].
 */
class EventLog(val file: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    private val writer = Executors.newSingleThreadExecutor()

    fun log(message: String) {
        Log.i(TAG, message)
        val time = Date()
        writer.execute { append(time, message) }
    }

    /** Newest entries first. Blocking: call off the main thread. */
    fun read(): List<String> = synchronized(this) {
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.asReversed() else emptyList()
    }

    fun clear() {
        writer.execute { synchronized(this) { file.delete() } }
    }

    internal fun append(time: Date, message: String) = synchronized(this) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(time)
        file.parentFile?.mkdirs()
        file.appendText("$stamp  ${message.replace('\n', ' ')}\n")
        if (file.length() > maxBytes) {
            val lines = file.readLines()
            file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
    }

    private companion object {
        const val TAG = "DashCamEvents"
        const val DEFAULT_MAX_BYTES = 256 * 1024L
    }
}
