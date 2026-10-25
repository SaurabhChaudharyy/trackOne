package app.trackone.ui.networth

import app.trackone.R
import app.trackone.data.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptySectionTest {

    @Test
    fun `the card shows only for a section that is open and holds nothing`() {
        assertTrue(showEmptyCard(expanded = true, holdings = 0))
        assertFalse(showEmptyCard(expanded = false, holdings = 0))   // collapsed: the compact row only
        assertFalse(showEmptyCard(expanded = true, holdings = 1))    // it has something to list
        assertFalse(showEmptyCard(expanded = false, holdings = 3))
    }

    @Test
    fun `gold reads as the design draws it`() {
        assertEquals(
            EmptySectionCopy(
                title = "No gold yet",
                body = "Add a coin, bar or Gold ETF to count it in your net worth.",
                action = "Add gold"
            ),
            emptySectionCopy(AssetType.GOLD)
        )
    }

    @Test
    fun `every category says what is missing and offers to add it`() {
        for (type in AssetType.entries) {
            val copy = emptySectionCopy(type)
            assertTrue("$type title: ${copy.title}", copy.title.startsWith("No ") && copy.title.endsWith(" yet"))
            assertTrue("$type body: ${copy.body}", copy.body.endsWith("to count it in your net worth."))
            assertTrue("$type action: ${copy.action}", copy.action.startsWith("Add "))
        }
    }

    @Test
    fun `no two categories share the same copy`() {
        val titles = AssetType.entries.map { emptySectionCopy(it).title }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `every category has its own illustration`() {
        val art = AssetType.entries.map { emptySectionArt(it) }
        assertEquals("each category has distinct art", art.size, art.toSet().size)
    }

    @Test
    fun `gold is drawn as bars and silver as coins, not the generic coin stack`() {
        assertEquals(R.drawable.ill_empty_gold, emptySectionArt(AssetType.GOLD))
        assertEquals(R.drawable.ill_empty_silver, emptySectionArt(AssetType.SILVER))
        // The coin stack stays for the portfolio-wide "Nothing tracked yet" card.
        assertTrue(emptySectionArt(AssetType.GOLD) != R.drawable.ill_empty_section)
    }
}
