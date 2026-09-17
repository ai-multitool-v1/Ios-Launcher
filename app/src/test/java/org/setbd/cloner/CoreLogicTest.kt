package org.setbd.cloner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.setbd.cloner.core.CloneLifecycleManager
import org.setbd.cloner.data.db.ClonerDatabase
import org.setbd.cloner.data.model.CloneLifecycle
import org.setbd.cloner.update.UpdateManager
import org.junit.Test

/**
 * Pure-JVM tests for the engine-adjacent components that don't need Android
 * framework objects: the lifecycle state machine and version comparison.
 */
class CoreLogicTest {

    @Test
    fun `lifecycle follows the happy path`() {
        val manager = CloneLifecycleManager()
        manager.report(1, CloneLifecycle.CREATED)
        manager.report(1, CloneLifecycle.STARTING)
        manager.report(1, CloneLifecycle.RUNNING)
        manager.report(1, CloneLifecycle.STOPPING)
        manager.report(1, CloneLifecycle.STOPPED)
        assertEquals(CloneLifecycle.STOPPED, manager.current(1))
    }

    @Test
    fun `error is reachable from any state`() {
        val manager = CloneLifecycleManager()
        manager.report(2, CloneLifecycle.CREATED)
        manager.report(2, CloneLifecycle.ERROR)
        assertEquals(CloneLifecycle.ERROR, manager.current(2))
        // And recovery is allowed after an error.
        manager.report(2, CloneLifecycle.STARTING)
        assertEquals(CloneLifecycle.STARTING, manager.current(2))
    }

    @Test
    fun `duplicate reports do not spam transitions`() {
        val manager = CloneLifecycleManager()
        manager.report(3, CloneLifecycle.CREATED)
        manager.report(3, CloneLifecycle.CREATED)
        assertEquals(CloneLifecycle.CREATED, manager.current(3))
    }

    @Test
    fun `running-like aggregates transient states`() {
        assertTrue(CloneLifecycle.RUNNING.isRunningLike)
        assertTrue(CloneLifecycle.STARTING.isRunningLike)
        assertFalse(CloneLifecycle.STOPPED.isRunningLike)
        assertFalse(CloneLifecycle.ERROR.isRunningLike)
    }

    @Test
    fun `version comparison is semantic`() {
        assertEquals(0, UpdateManager.compareVersions("1.0.0", "1.0.0"))
        assertEquals(1, UpdateManager.compareVersions("1.0.1", "1.0.0"))
        assertEquals(-1, UpdateManager.compareVersions("1.2.9", "1.2.10"))
        assertEquals(1, UpdateManager.compareVersions("2.0.0", "1.9.9"))
        assertEquals(1, UpdateManager.compareVersions("1.0.10", "1.0.9"))
    }

    @Test
    fun `version comparison ignores non digits`() {
        assertEquals(0, UpdateManager.compareVersions("v1.0.0", "1.0.0"))
    }

    @Test
    fun `split apk path encoding round-trips`() {
        val paths = listOf(
            "/data/clone_1/apk/base.apk",
            "/data/clone_1/apk/split_config.arm64_v8a.apk",
            "/data/clone_1/apk/split_config.xxhdpi.apk"
        )
        val encoded = ClonerDatabase.encodeSplitPaths(paths)
        assertEquals(paths.joinToString("|"), encoded)
        assertEquals(paths, ClonerDatabase.decodeSplitPaths(encoded))
    }

    @Test
    fun `empty split encoding decodes to empty list`() {
        assertTrue(ClonerDatabase.decodeSplitPaths("").isEmpty())
        assertTrue(ClonerDatabase.decodeSplitPaths("||").isEmpty())
    }
}
