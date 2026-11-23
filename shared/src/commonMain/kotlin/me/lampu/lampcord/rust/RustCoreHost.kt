package me.lampu.lampcord.rust

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import org.koin.compose.koinInject
import me.lampu.lampcord.shared.settings.Settings
import me.lampu.lampcord.shared.state.EntityStore
import me.lampu.lampcord.shared.state.GuildStore
import me.lampu.lampcord.shared.state.MemberListStore
import me.lampu.lampcord.shared.state.MessageStore
import me.lampu.lampcord.shared.state.NavigationStore
import me.lampu.lampcord.shared.state.PresenceStore
import me.lampu.lampcord.shared.state.ReadStateStore
import me.lampu.lampcord.shared.state.RustCoreMode
import me.lampu.lampcord.shared.state.SessionManager
import me.lampu.lampcord.shared.state.UserStore
import me.lampu.lampcord.shared.utils.Logging

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
    val memberListStore: MemberListStore = koinInject()

    DisposableEffect(sessionManager) {
        val engine = try {
            RustCoreEngine(RustClientConfig())
        } catch (error: Throwable) {
            Logging.e("RustCore", "RustCoreEngine init failed", error)
            null
        }
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
                memberListStore,
            )
            integration.start()
            if (token != null) engine.connectToken(token) else engine.connectStoredToken()
            onDispose {
                integration.close()
                engine.close()
                Logging.i("RustCore", "RustCoreEngine stopped")
            }
        }
    }
}
