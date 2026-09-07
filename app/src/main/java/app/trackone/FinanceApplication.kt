package app.trackone

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.trackone.security.AppLockManager
import app.trackone.ui.lock.LockActivity
import app.trackone.ui.settings.ThemePrefs
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FinanceApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var appLockManager: AppLockManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Applied before any Activity is created, so the user's chosen theme (rather than
        // just the system default) is already in effect on cold start.
        AppCompatDelegate.setDefaultNightMode(ThemePrefs.getMode(this))
        registerActivityLifecycleCallbacks(AppLockWatcher(appLockManager))
    }
}

/**
 * Tracks how many Activities are currently started, across the whole process, to detect the
 * app-wide foreground/background transitions [AppLockManager] cares about — a transition
 * between two of the app's own Activities (e.g. MainActivity -> StockDetailActivity) starts the
 * new one before stopping the old one, so the count never touches zero and no re-lock happens.
 */
private class AppLockWatcher(
    private val appLockManager: AppLockManager
) : Application.ActivityLifecycleCallbacks {

    private var startedActivityCount = 0

    override fun onActivityStarted(activity: Activity) {
        val cameFromBackground = startedActivityCount == 0
        startedActivityCount++

        if (cameFromBackground && activity !is LockActivity && appLockManager.shouldShowLockScreen()) {
            activity.startActivity(Intent(activity, LockActivity::class.java))
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount--
        if (startedActivityCount == 0) {
            appLockManager.onAppBackgrounded()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
