package ai.alagent.app

import android.app.Application
import ai.alagent.app.runtime.AppStartup
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AlAgentApplication : Application() {
    @Inject lateinit var startup: AppStartup

    override fun onCreate() {
        super.onCreate()
        startup.initialize()
    }
}
