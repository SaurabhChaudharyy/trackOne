package app.trackone.ui.settings

import android.content.Context

/**
 * Whether the user has opted into the daily portfolio digest notification. Off by default —
 * this is an opt-in feature, not core functionality, so it's never scheduled until the user
 * explicitly enables it (and grants POST_NOTIFICATIONS) from Settings.
 */
object DigestPrefs {
    private const val PREFS_NAME = "digest_prefs"
    private const val KEY_ENABLED = "digest_enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
