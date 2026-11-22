package me.lampu.lampcord.rust

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import org.koin.compose.koinInject
import me.lampu.lampcord.shared.settings.Settings
import me.lampu.lampcord.shared.state.EntityStore
import me.lampu.lampcord.shared.state.GuildStore
import me.lampu.lampcord.shared.state.MessageStore
import me.lampu.lampcord.shared.state.NavigationStore
import me.lampu.lampcord.shared.state.PresenceStore
import me.lampu.lampcord.shared.state.ReadStateStore
import me.lampu.lampcord.shared.state.RustCoreMode
import me.lampu.lampcord.shared.state.SessionManager
import me.lampu.lampcord.shared.state.UserStore
import me.lampu.lampcord.shared.utils.Logging

/**
 * Starts the Rust core for the lifetime of the composition and injects its snapshots into the
 * Kotlin stores. Falls back to the Kotlin gateway if the native library cannot be loaded.
 */
@Composable
fun RustCoreHost() {
    val sessionManager: SessionManager = koinInject()
    val entityStore: EntityStore = koinInject()
    val guildStore: GuildStore = koinInject()
    val messageStore: MessageStore = koinInject()
    val navigationStore: NavigationStore = koinInject()
    val userStore: UserStore = koinInject()
    val presenceStore: PresenceStore = koinInject()
    val readStateStore: ReadStateStore = koinInject()

    DisposableEffect(sessionManager) {
        val engine = runCatching { RustCoreEngine(RustClientConfig()) }.getOrNull()
        val token = Settings.shared.discordToken.trim().takeIf { it.isNotBlank() }
        if (engine == null) {
            Logging.w("RustCore", "Failed to start RustCoreEngine; falling back to Kotlin gateway")
            RustCoreMode.enabled = false
            if (token != null) sessionManager.connect(token)
            onDispose {}
        } else {
            Logging.i("RustCore", "RustCoreEngine started")
            val integration = RustCoreIntegration(
                engine,
                entityStore,
                guildStore,
                messageStore,
                navigationStore,
                userStore,
                presenceStore,
                readStateStore,
            )
            integration.start()
            if (token != null) engine.connectToken(token)
            onDispose {
                integration.close()
                engine.close()
                Logging.i("RustCore", "RustCoreEngine stopped")
            }
        }
    }
}
