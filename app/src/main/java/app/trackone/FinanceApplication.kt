package app.trackone

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.trackone.notifications.NotificationHelper
import app.trackone.security.AppLockManager
import app.trackone.ui.lock.LockActivity
import app.trackone.ui.settings.ReminderPrefs
import app.trackone.ui.settings.ThemePrefs
import app.trackone.workers.DigestScheduler
import app.trackone.workers.PortfolioReminderWorker
import com.google.firebase.crashlytics.FirebaseCrashlytics
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
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
        // Applied before any Activity is created, so the user's chosen theme (rather than
        // just the system default) is already in effect on cold start.
        AppCompatDelegate.setDefaultNightMode(ThemePrefs.getMode(this))
        registerActivityLifecycleCallbacks(AppLockWatcher(appLockManager))

        NotificationHelper.ensureChannelsCreated(this)
        DigestScheduler.apply(this, keepExisting = true)
        if (ReminderPrefs.isEnabled(this)) {
            PortfolioReminderWorker.schedule(this)
        }
    }
}

/**
 * Tracks how many Activities are currently started, across the whole process, purely so
 * [AppLockManager.onAppBackgrounded] can be told when the last Activity stops.
 *
 * Every newly-started Activity is gated against [AppLockManager.shouldShowLockScreen] — not just
 * ones arriving from a fully-backgrounded app. This must NOT be narrowed to "count was zero"
 * transitions: [LockActivity] itself keeps the count above zero for as long as it's on screen, so
 * an Activity that gets started *while still locked* (e.g. SplashActivity's timer-driven
 * `navigateToMain()`, which fires on its own schedule with no idea whether the gate is up) would
 * never trip the old zero-check and would land on top of the lock screen, fully visible, with no
 * unlock having happened.
 */
private class AppLockWatcher(
    private val appLockManager: AppLockManager
) : Application.ActivityLifecycleCallbacks {

    private var startedActivityCount = 0

    override fun onActivityStarted(activity: Activity) {
        // First Activity back in front: the manager decides here whether the time away was long
        // enough to lock, so the check just below sees the answer.
        if (startedActivityCount++ == 0) appLockManager.onAppForegrounded()

        if (activity !is LockActivity && appLockManager.shouldShowLockScreen()) {
            // REORDER_TO_FRONT lifts the lock that is already in the task above this Activity,
            // leaving this Activity alive (stopped) underneath until the unlock reveals it.
            // A singleTask lock would instead clear this Activity out of the task.
            activity.startActivity(
                Intent(activity, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
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
