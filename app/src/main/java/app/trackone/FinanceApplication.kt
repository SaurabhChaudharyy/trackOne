package app.trackone

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.trackone.ui.settings.ThemePrefs
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FinanceApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Applied before any Activity is created, so the user's chosen theme (rather than
        // just the system default) is already in effect on cold start.
        AppCompatDelegate.setDefaultNightMode(ThemePrefs.getMode(this))
    }
}
