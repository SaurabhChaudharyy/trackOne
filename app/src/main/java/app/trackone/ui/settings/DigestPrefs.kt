package app.trackone.ui.settings

import android.content.Context

/** How often the portfolio digest goes out. */
enum class DigestFrequency {
    /** One notification after each market closes (Indian, then US), on weekdays. */
    DAILY,

    /** One summary on Sunday morning. */
    WEEKLY
}

/**
 * Whether the user has opted into the portfolio digest notification, and how often. Off by default —
 * this is an opt-in feature, not core functionality, so it's never scheduled until the user
 * explicitly enables it (and grants POST_NOTIFICATIONS) from Settings.
 */
object DigestPrefs {
    private const val PREFS_NAME = "digest_prefs"
    private const val KEY_ENABLED = "digest_enabled"
    private const val KEY_FREQUENCY = "digest_frequency"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Daily unless the user picked weekly; an unreadable stored value is the default too. */
    fun frequency(context: Context): DigestFrequency =
        DigestFrequency.entries.firstOrNull { it.name == prefs(context).getString(KEY_FREQUENCY, null) }
            ?: DigestFrequency.DAILY

    fun setFrequency(context: Context, frequency: DigestFrequency) {
        prefs(context).edit().putString(KEY_FREQUENCY, frequency.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
