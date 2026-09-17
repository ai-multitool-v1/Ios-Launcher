package org.setbd.cloner.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort diagnostics: a global uncaught-exception handler that writes a
 * full crash report (thread, stack trace, recent engine log breadcrumbs) to
 * the app's private storage BEFORE the default handler kills the process.
 *
 * A guest application runs inside the host process, so a crash inside guest
 * code kills the whole cloner. The report gives the next feedback round the
 * exact stack trace without adb/logcat.
 *
 * The report builder is pure and JVM-testable; the handler simply chains to
 * the previous default handler so framework death behavior is preserved.
 */
object CrashCapture {

    private const val FILE_NAME = "setbd-crash.log"
    private const val MAX_REPORT_BYTES = 512 * 1024

    /** Installs the global handler. Call once at process start. */
    fun install(appContext: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(appContext.applicationContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
        ClonerLog.i("CrashCapture", "global crash handler installed")
    }

    /** Appends the crash report to the on-device log file. */
    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val file = File(context.filesDir, FILE_NAME)
        file.parentFile?.mkdirs()
        // Keep the file bounded: start a fresh file when it grows too large.
        if (file.length() > MAX_REPORT_BYTES) file.delete()
        file.appendText(buildReport(thread, throwable, ClonerLog.snapshot(200)))
    }

    /** Pure report builder — also used by unit tests. */
    fun buildReport(thread: Thread, throwable: Throwable, breadcrumbs: List<String>): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("════════ SETBD CLONER CRASH ════════")
            appendLine("time    : $time")
            appendLine("thread  : ${thread.name} (id=${thread.id})")
            appendLine("type    : ${throwable.javaClass.name}")
            appendLine("message : ${throwable.message ?: "<none>"}")
            if (breadcrumbs.isNotEmpty()) {
                appendLine("── engine breadcrumbs (last ${breadcrumbs.size}) ──")
                breadcrumbs.forEach { appendLine(it) }
            }
            appendLine("── stack trace ──")
            append(stack)
            appendLine("═══════════════════════════════════")
            appendLine()
        }
    }

    /** Reads the current crash log, or null when the file does not exist. */
    fun readReport(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.isFile) file.readText() else null
    }
}
