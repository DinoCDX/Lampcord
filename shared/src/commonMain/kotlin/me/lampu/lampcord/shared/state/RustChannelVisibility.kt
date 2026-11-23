package me.lampu.lampcord.shared.state

/**
 * Channel ids the current user may see, as evaluated by the Rust core (VIEW_CHANNEL after overwrites).
 * Replaced whole on every channel snapshot, so readers always see one consistent set.
 */
object RustChannelVisibility {
    private var viewable: Set<String> = emptySet()

    fun replace(channelIds: List<String>) {
        viewable = channelIds.toSet()
    }

    fun canView(channelId: String): Boolean = channelId in viewable
}
