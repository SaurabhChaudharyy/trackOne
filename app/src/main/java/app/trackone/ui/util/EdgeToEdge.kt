package app.trackone.ui.util

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams

/**
 * Draws this Activity's window edge-to-edge and keeps the status/nav bar icons legible for the
 * current theme.
 *
 * Newer Android builds ignore `android:statusBarColor` / `android:windowLightStatusBar` theme
 * attributes outright (the OS enforces edge-to-edge and paints its own compat scrim over the
 * status bar instead of respecting them), so bar-appearance has to be decided here at runtime.
 *
 * [topInsetView] — typically a toolbar/app bar — gets the top system-bar inset added as extra
 * top padding so its content isn't drawn under the status bar; it grows via its own
 * `wrap_content` height, so nothing else needs to shift.
 *
 * [bottomInsetView] — typically a bottom nav / bottom action bar with a *fixed* height — gets
 * the bottom system-bar inset added to both its height and its bottom padding, so its content
 * (icons/buttons) stays exactly where it was and the extra space is just more of its own
 * background extending under the gesture bar, instead of the gesture bar overlapping it.
 *
 * [navBarIsConstantDark] — true only where the nav bar sits over a constant-black background in
 * both themes (MainActivity's `@color/nav_bar_bg`, per values/colors.xml). Everywhere else the
 * nav bar sits over the theme-flipping `@color/background`, so its icon appearance needs to flip
 * with the theme too, same as the status bar.
 */
fun Activity.applyEdgeToEdge(topInsetView: View?, bottomInsetView: View?, navBarIsConstantDark: Boolean = false) {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    // Independent of windowLightStatusBar/windowLightNavigationBar, the platform by default
    // paints its own translucent contrast scrim behind both bars for legibility over arbitrary
    // content — that's the purple wash seen over the status bar in dark mode even though the
    // window's own background is already the correct black underneath. Since both bars already
    // sit over a deliberately high-contrast background (@color/background / @color/nav_bar_bg)
    // and get their own icon-appearance handling below, that scrim is redundant here.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    @Suppress("DEPRECATION")
    run {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    WindowInsetsControllerCompat(window, window.decorView).apply {
        // Status bar sits over @color/background, which flips with the theme, so its icons
        // need to flip too. Nav bar icons flip along with it unless navBarIsConstantDark says
        // this activity's nav bar sits over the constant-black @color/nav_bar_bg instead.
        isAppearanceLightStatusBars = !isNight
        isAppearanceLightNavigationBars = if (navBarIsConstantDark) false else !isNight
    }

    topInsetView?.let { view ->
        val basePadding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(basePadding[0], basePadding[1] + bars.top, basePadding[2], basePadding[3])
            insets
        }
    }
    bottomInsetView?.let { view ->
        val basePadding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        val baseHeight = view.layoutParams.height
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(basePadding[0], basePadding[1], basePadding[2], basePadding[3] + bars.bottom)
            if (baseHeight > 0) {
                v.updateLayoutParams { height = baseHeight + bars.bottom }
            }
            insets
        }
    }
}

/**
 * Adds the bottom system-bar inset as extra bottom padding on a scrolling content view that
 * sits behind a fixed bottom bar — e.g. a RecyclerView/FrameLayout whose existing
 * `paddingBottom` already clears that bar's own height, but not the gesture-nav area below it
 * once that bar has grown to cover it (see [applyEdgeToEdge]'s `bottomInsetView` handling).
 */
fun applyBottomContentInset(view: View) {
    val basePadding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(basePadding[0], basePadding[1], basePadding[2], basePadding[3] + bars.bottom)
        insets
    }
}
