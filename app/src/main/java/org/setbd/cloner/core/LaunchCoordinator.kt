package org.setbd.cloner.core

import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.setbd.cloner.data.CloneRepository
import org.setbd.cloner.data.SettingsStore
import org.setbd.cloner.data.model.CloneInfo
import org.setbd.cloner.data.model.CloneLifecycle
import org.setbd.cloner.engine.VirtualEngine
import org.setbd.cloner.engine.guest.GuestLoadException
import org.setbd.cloner.util.ClonerLog

/** Result of a clone launch attempt. */
sealed class LaunchResult {
    data object Started : LaunchResult()
    /** Engine failed; user was transparently routed to the original app. */
    data object FallbackStarted : LaunchResult()
    data class Failed(val reason: String) : LaunchResult()
}

/**
 * Decides HOW a clone is started.
 *
 * Primary path: the container engine loads the guest APK into this process.
 * When the engine cannot run a guest (unparsable APK, unsupported construct,
 * hidden-API failure…) the user setting decides between an explicit error or
 * a clearly-labelled compatibility fallback that opens the ORIGINAL installed
 * app. The fallback is honest: it never pretends to be virtualized — a toast
 * tells the user what happened, and the clone is marked degraded.
 */
class LaunchCoordinator(
    private val context: Context,
    private val engine: VirtualEngine,
    private val repository: CloneRepository,
    private val lifecycleManager: CloneLifecycleManager,
    private val settings: SettingsStore
) {

    suspend fun launch(clone: CloneInfo): LaunchResult {
        if (!clone.enabled) {
            return LaunchResult.Failed("Clone is disabled")
        }
        lifecycleManager.report(clone.cloneId, CloneLifecycle.STARTING)
        repository.markLaunched(clone.cloneId)

        return try {
            val runtime = engine.ensureRuntime(clone)
            val guestApplication = runtime.createGuestApplication()
            repository.markDegraded(clone.cloneId, guestApplication == null)

            val wrapper = runtime.buildStubIntent(runtime.guestLaunchIntent())
            context.startActivity(wrapper)
            ClonerLog.i(TAG, "clone=${clone.cloneId} (${clone.originalPackageName}) launched in container")
            LaunchResult.Started
        } catch (guest: GuestLoadException) {
            ClonerLog.e(TAG, "clone=${clone.cloneId} cannot virtualize: ${guest.message}", guest)
            lifecycleManager.report(clone.cloneId, CloneLifecycle.ERROR)
            handleEngineFailure(clone, guest.message ?: "guest not supported")
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "clone=${clone.cloneId} launch failed", t)
            lifecycleManager.report(clone.cloneId, CloneLifecycle.ERROR)
            handleEngineFailure(clone, t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun handleEngineFailure(clone: CloneInfo, reason: String): LaunchResult {
        val fallbackAllowed = settings.currentFallbackLaunch()
        val original = context.packageManager.getLaunchIntentForPackage(clone.originalPackageName)
        if (fallbackAllowed && original != null) {
            Toast.makeText(
                context,
                "Container can't run this app — opening original (compatibility mode)",
                Toast.LENGTH_LONG
            ).show()
            context.startActivity(original.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            lifecycleManager.report(clone.cloneId, CloneLifecycle.RUNNING)
            return LaunchResult.FallbackStarted
        }
        return LaunchResult.Failed(reason)
    }

    private companion object {
        const val TAG = "LaunchCoordinator"
    }
}
