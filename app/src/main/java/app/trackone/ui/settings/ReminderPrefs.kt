package app.trackone.ui.settings

import android.content.Context

/**
 * Whether the user has opted into the twice-a-month portfolio update reminder. Off by default,
 * same rationale as [DigestPrefs] — opt-in, never scheduled until Settings enables it.
 */
object ReminderPrefs {
    private const val PREFS_NAME = "portfolio_reminder_prefs"
    private const val KEY_ENABLED = "reminder_enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
