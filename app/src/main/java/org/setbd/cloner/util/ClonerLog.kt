package org.setbd.cloner.util

import android.util.Log
import java.util.ArrayDeque

/**
 * Central logging helper. All engine components tag their logs with
 * "SetBD/…" so clone failures are easy to trace with a single filter.
 *
 * Every entry is also kept in a bounded in-memory ring buffer. When the
 * process dies (guest code can crash the host — that is why [CrashCapture]
 * exists), the last entries are flushed into the crash report so a failure
 * can be reconstructed without adb/logcat.
 */
object ClonerLog {

    const val PREFIX = "SetBD/"

    private const val BUFFER_CAPACITY = 300

    private val buffer = ArrayDeque<String>(BUFFER_CAPACITY)

    private fun record(line: String) {
        synchronized(buffer) {
            if (buffer.size >= BUFFER_CAPACITY) buffer.pollFirst()
            buffer.addLast(line)
        }
    }

    /** Last [max] engine log lines, oldest first. Thread-safe. */
    fun snapshot(max: Int = BUFFER_CAPACITY): List<String> = synchronized(buffer) {
        buffer.toList().takeLast(max)
    }

    fun clearBuffer() = synchronized(buffer) { buffer.clear() }

    fun d(tag: String, message: String) {
        record("D/${PREFIX}$tag: $message")
        Log.d(PREFIX + tag, message)
    }

    fun i(tag: String, message: String) {
        record("I/${PREFIX}$tag: $message")
        Log.i(PREFIX + tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        record("W/${PREFIX}$tag: $message${error?.let { " — $it" } ?: ""}")
        if (error != null) Log.w(PREFIX + tag, message, error) else Log.w(PREFIX + tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        record("E/${PREFIX}$tag: $message${error?.let { " — $it" } ?: ""}")
        if (error != null) Log.e(PREFIX + tag, message, error) else Log.e(PREFIX + tag, message)
    }
}
