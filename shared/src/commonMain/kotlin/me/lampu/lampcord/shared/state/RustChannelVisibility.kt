package me.lampu.lampcord.shared.state

object RustChannelVisibility {
    private var hidden: Set<String> = emptySet()

    fun replaceHidden(channelIds: List<String>) {
        hidden = channelIds.toSet()
    }

    fun canView(channelId: String): Boolean = channelId !in hidden
}
