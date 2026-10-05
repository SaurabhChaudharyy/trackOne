package app.trackone.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeTextTest {

    @Test
    fun `a gain line and the invested figure share a row only at normal text sizes`() {
        assertFalse(LargeText.stackSideBySideRows(fontScale = 1.0f))
        assertFalse(LargeText.stackSideBySideRows(fontScale = 1.15f))
        assertTrue(LargeText.stackSideBySideRows(fontScale = 1.3f))
        assertTrue(LargeText.stackSideBySideRows(fontScale = 2.0f))
    }

    @Test
    fun `a fixed-width card grows with the text so its figures are never cut off`() {
        assertEquals(116f, LargeText.scaledWidth(baseDp = 116f, fontScale = 1.0f), 0.001f)
        assertEquals(174f, LargeText.scaledWidth(baseDp = 116f, fontScale = 1.5f), 0.001f)
    }

    @Test
    fun `a card never shrinks below its base width, and growth is capped`() {
        assertEquals(116f, LargeText.scaledWidth(baseDp = 116f, fontScale = 0.85f), 0.001f)
        assertEquals(116f * 1.8f, LargeText.scaledWidth(baseDp = 116f, fontScale = 3.0f), 0.001f)
    }
}
