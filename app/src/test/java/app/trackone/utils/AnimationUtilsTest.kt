package app.trackone.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationUtilsTest {

    @Test
    fun `the final frame lands exactly on the target, not a float-rounded neighbour`() {
        // Regression: interpolating in Float turned 738439.70 into 738439.6875 (shown as .69),
        // so Home disagreed with the Assets screen by a paisa after the count-up finished.
        val target = 738_439.70

        assertEquals(target, AnimationUtils.interpolate(0.0, target, 1f), 0.0)
    }

    @Test
    fun `interpolation is linear between the endpoints`() {
        assertEquals(0.0, AnimationUtils.interpolate(0.0, 100.0, 0f), 0.0)
        assertEquals(50.0, AnimationUtils.interpolate(0.0, 100.0, 0.5f), 1e-9)
        assertEquals(150.0, AnimationUtils.interpolate(100.0, 200.0, 0.5f), 1e-9)
    }
}
