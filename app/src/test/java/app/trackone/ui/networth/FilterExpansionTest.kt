package app.trackone.ui.networth

import app.trackone.data.database.AssetType
import app.trackone.data.database.AssetType.GOLD
import app.trackone.data.database.AssetType.STOCK_IN
import app.trackone.data.database.AssetType.STOCK_US
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FilterExpansionTest {

    private val collapsed = SectionState(expanded = false, showAll = false)
    private val topThree = SectionState(expanded = true, showAll = false)
    private val fullyOpen = SectionState(expanded = true, showAll = true)

    private lateinit var filter: FilterExpansion
    /** What the screen currently shows; each result of the filter is applied to it, as the fragment does. */
    private lateinit var states: MutableMap<AssetType, SectionState>

    @Before
    fun setUp() {
        filter = FilterExpansion()
        states = AssetType.entries.associateWith { collapsed }.toMutableMap()
    }

    private fun pick(type: AssetType?): Map<AssetType, SectionState> =
        filter.onFilterChanged(type, states.toMap()).also { states.putAll(it) }

    @Test
    fun `picking a category opens it fully, and picking All puts it back as it was`() {
        pick(STOCK_IN)
        assertEquals(fullyOpen, states[STOCK_IN])

        pick(null)
        assertEquals(collapsed, states[STOCK_IN])
    }

    @Test
    fun `moving from one category to another closes the first and opens the second`() {
        pick(STOCK_US)
        pick(STOCK_IN)

        assertEquals(collapsed, states[STOCK_US])
        assertEquals(fullyOpen, states[STOCK_IN])

        pick(null)
        assertEquals(collapsed, states[STOCK_IN])
    }

    @Test
    fun `a section the user had fully open stays open after All`() {
        states[STOCK_IN] = fullyOpen

        pick(STOCK_IN)
        val onAll = pick(null)

        assertTrue("nothing to undo, the filter opened nothing", onAll.isEmpty())
        assertEquals(fullyOpen, states[STOCK_IN])
    }

    @Test
    fun `a section showing its top three goes back to its top three`() {
        states[STOCK_IN] = topThree

        pick(STOCK_IN)
        assertEquals(fullyOpen, states[STOCK_IN])

        pick(null)
        assertEquals(topThree, states[STOCK_IN])
    }

    @Test
    fun `picking the same category again does not forget what it was`() {
        pick(STOCK_US)
        pick(STOCK_US)
        pick(null)

        assertEquals(collapsed, states[STOCK_US])
    }

    @Test
    fun `a section the user opens or closes while filtered is theirs and is left alone`() {
        pick(STOCK_IN)
        // The user closes the filtered section's header, then asks for All.
        states[STOCK_IN] = collapsed
        filter.userChanged(STOCK_IN)
        val onAll = pick(null)

        assertTrue(onAll.isEmpty())
        assertEquals(collapsed, states[STOCK_IN])
    }

    @Test
    fun `sections the filter never touched are never changed`() {
        states[GOLD] = topThree

        pick(STOCK_IN)
        pick(STOCK_US)
        pick(null)

        assertEquals(topThree, states[GOLD])
    }
}
