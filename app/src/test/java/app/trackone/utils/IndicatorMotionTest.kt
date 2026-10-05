package app.trackone.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** The numbers behind the bottom tabs' selection pill (Material's NavigationBarItemView), reused for the range chips. */
class IndicatorMotionTest {

    private val delta = 0.0001f

    @Test
    fun `the pill grows horizontally from 40 percent to full width`() {
        assertEquals(0.4f, IndicatorMotion.scaleX(0f), delta)
        assertEquals(0.7f, IndicatorMotion.scaleX(0.5f), delta)
        assertEquals(1f, IndicatorMotion.scaleX(1f), delta)
    }

    @Test
    fun `a pill being selected fades in over the first fifth of the motion`() {
        assertEquals(0f, IndicatorMotion.alpha(progress = 0f, target = 1f), delta)
        assertEquals(0.5f, IndicatorMotion.alpha(progress = 0.1f, target = 1f), delta)
        assertEquals(1f, IndicatorMotion.alpha(progress = 0.2f, target = 1f), delta)
        assertEquals(1f, IndicatorMotion.alpha(progress = 0.7f, target = 1f), delta)
    }

    @Test
    fun `a pill being deselected shrinks first and is gone by the time it is a fifth smaller`() {
        // Progress runs 1 -> 0 on the way out; it stays opaque until 0.8 then fades to nothing.
        assertEquals(1f, IndicatorMotion.alpha(progress = 1f, target = 0f), delta)
        assertEquals(0.5f, IndicatorMotion.alpha(progress = 0.9f, target = 0f), delta)
        assertEquals(0f, IndicatorMotion.alpha(progress = 0.8f, target = 0f), delta)
        assertEquals(0f, IndicatorMotion.alpha(progress = 0.3f, target = 0f), delta)
    }
}
