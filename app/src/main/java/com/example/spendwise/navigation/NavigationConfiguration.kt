package com.example.spendwise.navigation

data class NavigationConfiguration(
    val orderedIds: List<String>,
    val pinnedIds: Set<String>
) {
    val orderedDestinations: List<MainDestination>
        get() = orderedIds.mapNotNull(MainDestination::fromStableId)

    val pinnedDestinations: List<MainDestination>
        get() = orderedDestinations.filter { it.stableId in pinnedIds }

    val unpinnedDestinations: List<MainDestination>
        get() = orderedDestinations.filterNot { it.stableId in pinnedIds }

    val secondaryDestinations: List<MainDestination>
        get() = unpinnedDestinations.filter { it.secondaryVisibility == SecondaryVisibility.WHEN_UNPINNED }

    fun move(id: String, targetIndex: Int): NavigationConfiguration {
        require(id in orderedIds && targetIndex in orderedIds.indices)
        val updated = orderedIds.toMutableList().apply { remove(id); add(targetIndex, id) }
        return copy(orderedIds = updated)
    }

    fun withPinned(id: String, pinned: Boolean): NavigationConfiguration {
        require(id in orderedIds)
        val updated = if (pinned) pinnedIds + id else pinnedIds - id
        require(updated.size in 3..5) { "Keep three to five destinations pinned" }
        return copy(pinnedIds = updated)
    }

    /** Replaces only pinned slots; unpinned destinations keep their full-order positions. */
    fun reorderPinned(reorderedPinnedIds: List<String>): NavigationConfiguration {
        require(reorderedPinnedIds.size == pinnedIds.size && reorderedPinnedIds.toSet() == pinnedIds)
        val replacement = reorderedPinnedIds.iterator()
        return copy(orderedIds = orderedIds.map { id -> if (id in pinnedIds) replacement.next() else id })
    }

    companion object {
        val DEFAULT = NavigationConfiguration(
            MainDestination.configurable.map(MainDestination::stableId),
            MainDestination.configurable.filter(MainDestination::defaultPinned).map(MainDestination::stableId).toSet()
        )

        fun restored(orderedIds: List<String>?, pinnedIds: Set<String>?): NavigationConfiguration {
            val valid = DEFAULT.orderedIds.toSet()
            val suppliedOrder = orderedIds.orEmpty().filter { it in valid }.distinct()
            val order = suppliedOrder + DEFAULT.orderedIds.filterNot { it in suppliedOrder }
            val pins = pinnedIds?.intersect(valid) ?: DEFAULT.pinnedIds
            return NavigationConfiguration(order, pins.takeIf { it.size in 3..5 } ?: DEFAULT.pinnedIds)
        }
    }
}
