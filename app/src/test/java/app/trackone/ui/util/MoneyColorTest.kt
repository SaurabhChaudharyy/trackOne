package app.trackone.ui.util

import app.trackone.R
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyColorTest {

    @Test
    fun `a rise is gain and a fall is loss`() {
        assertEquals(R.color.gain, MoneyColor.forChange(12.5))
        assertEquals(R.color.loss, MoneyColor.forChange(-0.01))
    }

    @Test
    fun `no move is neutral, not a gain`() {
        assertEquals(R.color.text_secondary, MoneyColor.forChange(0.0))
        assertEquals(R.color.text_secondary, MoneyColor.forChange(-0.0))
    }

    @Test
    fun `a move that shows as zero on screen is neutral`() {
        assertEquals(R.color.text_secondary, MoneyColor.forChange(0.004))
        assertEquals(R.color.text_secondary, MoneyColor.forChange(-0.004))
        assertEquals(R.color.gain, MoneyColor.forChange(0.005))
    }

    @Test
    fun `a missing quote is neutral`() {
        assertEquals(R.color.text_secondary, MoneyColor.forChange(Double.NaN))
    }
}
