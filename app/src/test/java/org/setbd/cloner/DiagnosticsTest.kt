package org.setbd.cloner

import org.junit.Assert.assertTrue
import org.junit.Test
import org.setbd.cloner.util.ClonerLog
import org.setbd.cloner.util.CrashCapture

/**
 * Pure-JVM tests for the crash diagnostics net: the breadcrumb ring buffer
 * and the crash report builder. android.util.Log calls are no-ops under
 * unit tests (returnDefaultValues), so ClonerLog is safe on the JVM.
 */
class DiagnosticsTest {

    @Test
    fun `ring buffer keeps most recent entries`() {
        ClonerLog.clearBuffer()
        for (i in 1..50) ClonerLog.i("Test", "entry-$i")

        val snapshot = ClonerLog.snapshot(10)

        assertTrue("expected exactly 10 entries", snapshot.size == 10)
        assertTrue("oldest of the last 10 must be entry-41", snapshot.first().contains("entry-41"))
        assertTrue("newest must be entry-50", snapshot.last().contains("entry-50"))
    }

    @Test
    fun `ring buffer respects capacity and stays bounded`() {
        ClonerLog.clearBuffer()
        for (i in 1..400) ClonerLog.d("Test", "line-$i")

        val snapshot = ClonerLog.snapshot(1000)
        assertTrue("buffer must stay bounded at 300", snapshot.size <= 300)
        assertTrue("oldest surviving entry must be line-101", snapshot.first().contains("line-101"))
        assertTrue(snapshot.last().contains("line-400"))
    }

    @Test
    fun `crash report carries thread trace and breadcrumbs`() {
        ClonerLog.clearBuffer()
        ClonerLog.e("Engine", "runtime exploded")
        val boom = IllegalStateException("guest went boom")
        val report = CrashCapture.buildReport(
            Thread.currentThread(),
            boom,
            ClonerLog.snapshot(200)
        )

        assertTrue(report.contains("SETBD CLONER CRASH"))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("guest went boom"))
        assertTrue(report.contains(Thread.currentThread().name))
        assertTrue(report.contains("runtime exploded"))
        assertTrue(report.contains("stack trace"))
    }

    @Test
    fun `crash report without breadcrumbs still renders`() {
        val report = CrashCapture.buildReport(
            Thread.currentThread(),
            OutOfMemoryError("heap"),
            emptyList()
        )
        assertTrue(report.contains("OutOfMemoryError"))
        assertTrue(report.contains("heap"))
    }
}
