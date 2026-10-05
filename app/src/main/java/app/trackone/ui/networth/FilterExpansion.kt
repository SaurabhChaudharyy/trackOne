package app.trackone.ui.networth

import app.trackone.data.database.AssetType

/** How a category section is showing: closed, open on its top rows, or open on all of them. */
internal data class SectionState(val expanded: Boolean, val showAll: Boolean)

/**
 * Picking a category in the allocation filter opens it so its rows can be read, but that opening is
 * borrowed: choosing All, or another category, puts the section back as it was. Left alone, the one
 * you filtered to stayed fully open under All while every other section was collapsed.
 *
 * A section the user opens, closes or expands themselves while filtered is theirs ([userChanged]),
 * and is never put back.
 */
internal class FilterExpansion {

    /** Sections the filter opened, each with the state to put back. */
    private val toRestore = mutableMapOf<AssetType, SectionState>()

    /**
     * The filter now shows [filter] (null is All). Given every section's current [states], returns
     * the sections to change and what to change them to: earlier borrowed ones back, the new one open.
     */
    fun onFilterChanged(filter: AssetType?, states: Map<AssetType, SectionState>): Map<AssetType, SectionState> {
        val changes = mutableMapOf<AssetType, SectionState>()

        for ((type, before) in toRestore.toMap()) {
            if (type == filter) continue
            changes[type] = before
            toRestore.remove(type)
        }

        if (filter != null) {
            val current = states[filter] ?: SectionState(expanded = false, showAll = false)
            if (current != OPEN && filter !in toRestore) toRestore[filter] = current
            if (current != OPEN) changes[filter] = OPEN
        }
        return changes
    }

    /** The user took over [type] themselves, so the filter no longer owns its state. */
    fun userChanged(type: AssetType) {
        toRestore.remove(type)
    }

    private companion object {
        val OPEN = SectionState(expanded = true, showAll = true)
    }
}
