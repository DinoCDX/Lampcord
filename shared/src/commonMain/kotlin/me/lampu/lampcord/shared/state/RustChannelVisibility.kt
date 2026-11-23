package me.lampu.lampcord.shared.state

object RustChannelVisibility {
    private var viewable: Set<String> = emptySet()

    fun replace(channelIds: List<String>) {
        viewable = channelIds.toSet()
    }

    fun canView(channelId: String): Boolean = channelId in viewable
}
