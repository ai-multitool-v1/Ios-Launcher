package org.setbd.cloner

import android.app.Application
import org.setbd.cloner.di.AppContainer
import org.setbd.cloner.util.ClonerLog
import org.setbd.cloner.util.CrashCapture

/**
 * SETBD Cloner — parallel-app container host.
 *
 * The engine is wired at process start so the instrumentation hook is in
 * place before any activity (host or guest) can launch. The crash capture
 * net goes up FIRST: guests run inside this process, so any guest crash
 * must leave a readable report before the process goes down.
 */
class ClonerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashCapture.install(this)
        container = AppContainer(this)
        container.initializeEngine()
        ClonerLog.i("ClonerApp", "SETBD Cloner ${BuildConfig.VERSION_NAME} started")
    }
}
