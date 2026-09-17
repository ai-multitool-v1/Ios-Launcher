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
    /** Engine failed; the user explicitly enabled opening the original app. */
    data object FallbackStarted : LaunchResult()
    data class Failed(val reason: String) : LaunchResult()
}

/**
 * Decides HOW a clone is started.
 *
 * Primary path: the container engine loads the guest APK into this process.
 * When the engine cannot run a guest the DEFAULT behavior is an explicit
 * failure with a clear, actionable reason — the app NEVER silently swaps
 * itself for the original (that default confused users into thinking the
 * clone "worked" while actually opening the original). Only when the user
 * turns on "Compatibility mode" in Settings does a failure additionally
 * open the original installed app, clearly labelled.
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
            ClonerLog.i(TAG, "launch start clone=${clone.cloneId} pkg=${clone.originalPackageName}")
            val runtime = engine.ensureRuntime(clone)
            ClonerLog.i(
                TAG,
                "runtime ready clone=${clone.cloneId} launcher=${runtime.launcherActivityClassName} " +
                    "apks=${runtime.allApkPaths.size} providers=${runtime.guestProviders.size}"
            )
            val guestApplication = runtime.createGuestApplication()
            if (guestApplication == null) {
                ClonerLog.w(TAG, "guest application DEGRADED clone=${clone.cloneId} (continuing without it)")
            }
            repository.markDegraded(clone.cloneId, guestApplication == null)

            val wrapper = runtime.buildStubIntent(runtime.guestLaunchIntent())
            ClonerLog.i(
                TAG,
                "stub intent built clone=${clone.cloneId} stub=${wrapper.component} guest=${wrapper.getStringExtra(org.setbd.cloner.engine.stub.ExtraKeys.GUEST_CLASS)}"
            )
            context.startActivity(wrapper)
            ClonerLog.i(TAG, "clone=${clone.cloneId} (${clone.originalPackageName}) launched in container")
            LaunchResult.Started
        } catch (guest: GuestLoadException) {
            ClonerLog.e(TAG, "clone=${clone.cloneId} cannot virtualize: ${guest.message}", guest)
            lifecycleManager.report(clone.cloneId, CloneLifecycle.ERROR)
            // Drop the broken runtime so a later retry rebuilds it fresh.
            engine.unloadRuntime(clone.cloneId)
            handleEngineFailure(clone, guest.message ?: "guest not supported")
        } catch (t: Throwable) {
            ClonerLog.e(TAG, "clone=${clone.cloneId} launch failed", t)
            lifecycleManager.report(clone.cloneId, CloneLifecycle.ERROR)
            engine.unloadRuntime(clone.cloneId)
            handleEngineFailure(clone, t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun handleEngineFailure(clone: CloneInfo, rawReason: String): LaunchResult {
        val fallbackAllowed = settings.currentFallbackLaunch()
        if (fallbackAllowed) {
            val original = context.packageManager.getLaunchIntentForPackage(clone.originalPackageName)
            if (original != null) {
                Toast.makeText(
                    context,
                    "Clone engine failed — opening original (compatibility mode)",
                    Toast.LENGTH_LONG
                ).show()
                context.startActivity(original.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                lifecycleManager.report(clone.cloneId, CloneLifecycle.RUNNING)
                return LaunchResult.FallbackStarted
            }
        }
        return LaunchResult.Failed(friendlyReason(rawReason))
    }

    /** Translates engine jargon into user-actionable messages. */
    private fun friendlyReason(raw: String): String {
        val lowered = raw.lowercase()
        return when {
            "launchable entry" in lowered || "manifest" in lowered ->
                "The clone APK could not be read. Delete this clone and import the app again."
            "component" in lowered ->
                "The clone's entry point is missing. Delete this clone and import the app again."
            else -> "Engine error: $raw"
        }
    }

    private companion object {
        const val TAG = "LaunchCoordinator"
    }
}
