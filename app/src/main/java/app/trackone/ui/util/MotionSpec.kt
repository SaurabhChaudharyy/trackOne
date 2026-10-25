package app.trackone.ui.util

import android.content.Context
import app.trackone.R
import android.view.animation.PathInterpolator
import android.animation.TimeInterpolator
import androidx.recyclerview.widget.RecyclerView
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

    /**
     * What views do when they appear, disappear or shift: they fade, and move to their new bounds.
     *
     * A RecyclerView is not a target. A transition that animates the list itself interferes with its
     * item animator: when a section opens or closes while the list is gaining rows, the add animation
     * finishes on a row that is no longer attached and the app crashes ("Tmp detached view should be
     * removed from RecyclerView before it can be recycled"). Tapping the Assets filter chips quickly
     * did it every time. Excluding the list's rows from the transition did not stop it; excluding the
     * list itself does. The rows still animate through the list's item animator, and the section
     * headers and neighbours still fade and move.
     */
    fun transition(context: Context): Transition = TransitionSet()
        .addTransition(Fade().excludeTarget(RecyclerView::class.java, true))
        .addTransition(ChangeBounds().excludeTarget(RecyclerView::class.java, true))
        .setOrdering(TransitionSet.ORDERING_TOGETHER)
        .setDuration(layoutDuration(context))
        .setInterpolator(interpolator(context))
}
