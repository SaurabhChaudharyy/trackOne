package app.trackone.ui.util

import android.content.Context
import app.trackone.R
import android.view.animation.PathInterpolator
import android.animation.TimeInterpolator
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.Transition
import androidx.transition.TransitionSet
import com.google.android.material.motion.MotionUtils

/**
 * The motion the app uses for things that change state, following Material 3: one easing (emphasized,
 * from the theme) and two durations in its "medium" range, kept as tokens in res/values/integers.xml.
 *  - [selectionDuration]: anything that shows a selection (bottom tabs, chips, tab underline).
 *  - [layoutDuration]: things that open, close or shift (sections, the banner).
 * Larger, ambient motion (the chart sweep, the splash, the skeleton) is data revealing itself, not a
 * response to a tap, and keeps its own longer timings.
 */
object MotionSpec {

    fun selectionDuration(context: Context): Long = context.resources.getInteger(R.integer.motion_duration_selection).toLong()

    fun layoutDuration(context: Context): Long = context.resources.getInteger(R.integer.motion_duration_layout).toLong()

    fun interpolator(context: Context): TimeInterpolator = MotionUtils.resolveThemeInterpolator(
        context, com.google.android.material.R.attr.motionEasingEmphasizedInterpolator,
        PathInterpolator(0.2f, 0f, 0f, 1f)
    )

    /** What views do when they appear, disappear or shift: they fade, and move to their new bounds. */
    fun transition(context: Context): Transition = TransitionSet()
        .addTransition(Fade())
        .addTransition(ChangeBounds())
        .setOrdering(TransitionSet.ORDERING_TOGETHER)
        .setDuration(layoutDuration(context))
        .setInterpolator(interpolator(context))
}
