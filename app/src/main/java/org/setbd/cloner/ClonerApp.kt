package org.setbd.cloner

import android.app.Application
import org.setbd.cloner.di.AppContainer
import org.setbd.cloner.util.ClonerLog

/**
 * SETBD Cloner — parallel-app container host.
 *
 * The engine is wired at process start so the instrumentation hook is in
 * place before any activity (host or guest) can launch.
 */
class ClonerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.initializeEngine()
        ClonerLog.i("ClonerApp", "SETBD Cloner ${BuildConfig.VERSION_NAME} started")
    }
}
