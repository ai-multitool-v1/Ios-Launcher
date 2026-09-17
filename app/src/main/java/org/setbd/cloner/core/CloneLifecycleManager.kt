package org.setbd.cloner.core

import org.setbd.cloner.data.model.CloneLifecycle
import org.setbd.cloner.util.ClonerLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Tracks the lifecycle of every clone.
 *
 * State machine: CREATED → STARTING → RUNNING → STOPPING → STOPPED,
 * with ERROR reachable from any state. The current state is both kept in
 * memory (for instant UI updates) and persisted by the repository.
 */
class CloneLifecycleManager {

    private val states = MutableStateFlow<Map<Long, CloneLifecycle>>(emptyMap())

    val all: StateFlow<Map<Long, CloneLifecycle>> = states.asStateFlow()

    fun observe(cloneId: Long): Flow<CloneLifecycle> =
        states.map { it[cloneId] ?: CloneLifecycle.STOPPED }

    fun current(cloneId: Long): CloneLifecycle = states.value[cloneId] ?: CloneLifecycle.CREATED

    /** Reports a state transition. Illegal transitions are logged, not fatal. */
    fun report(cloneId: Long, next: CloneLifecycle) {
        val previous = current(cloneId)
        if (previous == next) return
        if (!isTransitionAllowed(previous, next)) {
            ClonerLog.w(TAG, "unusual transition clone=$cloneId $previous → $next")
        }
        states.value = states.value + (cloneId to next)
        ClonerLog.i(TAG, "clone=$cloneId $previous → $next")
    }

    fun remove(cloneId: Long) {
        states.value = states.value - cloneId
    }

    private fun isTransitionAllowed(from: CloneLifecycle, to: CloneLifecycle): Boolean {
        if (to == CloneLifecycle.ERROR) return true
        return when (from) {
            CloneLifecycle.CREATED -> to in setOf(CloneLifecycle.STARTING, CloneLifecycle.STOPPED)
            CloneLifecycle.STARTING ->
                to in setOf(CloneLifecycle.RUNNING, CloneLifecycle.STOPPING, CloneLifecycle.STOPPED)
            CloneLifecycle.RUNNING -> to in setOf(CloneLifecycle.STOPPING, CloneLifecycle.STOPPED)
            CloneLifecycle.STOPPING -> to in setOf(CloneLifecycle.STOPPED, CloneLifecycle.RUNNING)
            CloneLifecycle.STOPPED -> to in setOf(CloneLifecycle.STARTING, CloneLifecycle.CREATED)
            CloneLifecycle.ERROR -> true
        }
    }

    private companion object {
        const val TAG = "CloneLifecycle"
    }
}
