package app.trackone.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Persists the user's chosen app theme (System default / Light / Dark) so it survives process
 * death, independent of the device's own system dark-mode setting. Stored as one of
 * AppCompatDelegate's own MODE_NIGHT_* ints so it can be handed straight to
 * setDefaultNightMode() without a separate mapping layer.
 */
object ThemePrefs {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_MODE = "night_mode"

    fun getMode(context: Context): Int =
        prefs(context).getInt(KEY_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
