package app.trackone.security

import android.content.Context

/**
 * SharedPreferences-backed [AppLockStore]. Thin Android glue over [AppLockManager]'s storage
 * seam — deliberately untested, same pattern as [app.trackone.ui.settings.ThemePrefs].
 */
class AppLockPrefs(context: Context) : AppLockStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var isLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    override var relockDelayMs: Long
        get() = prefs.getLong(KEY_DELAY_MS, LockDelay.DEFAULT_MS)
        set(value) { prefs.edit().putLong(KEY_DELAY_MS, value).apply() }

    private companion object {
        const val PREFS_NAME = "app_lock_prefs"
        const val KEY_ENABLED = "lock_enabled"
        const val KEY_DELAY_MS = "relock_delay_ms"
    }
}
