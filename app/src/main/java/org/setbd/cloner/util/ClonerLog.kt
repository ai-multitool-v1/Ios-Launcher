package org.setbd.cloner.util

import android.util.Log

/**
 * Central logging helper. All engine components tag their logs with
 * "SetBD/…" so clone failures are easy to trace with a single filter.
 */
object ClonerLog {

    const val PREFIX = "SetBD/"

    fun d(tag: String, message: String) {
        Log.d(PREFIX + tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(PREFIX + tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(PREFIX + tag, message, error) else Log.w(PREFIX + tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(PREFIX + tag, message, error) else Log.e(PREFIX + tag, message)
    }
}
