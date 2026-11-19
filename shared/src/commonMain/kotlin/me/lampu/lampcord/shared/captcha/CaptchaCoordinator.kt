package me.lampu.lampcord.shared.captcha

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.lampu.lampcord.shared.api.CaptchaChallenge
import me.lampu.lampcord.shared.utils.Logging

/** How long a single captcha may stay open before the pending login or join gives up. */
internal const val CAPTCHA_SOLVE_TIMEOUT_MS = 5 * 60_000L

class CaptchaRequest(
    val id: Long,
    val challenge: CaptchaChallenge,
    val result: CompletableDeferred<String?>,
)

class CaptchaCoordinator {
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<CaptchaRequest?>(null)
    val pending: StateFlow<CaptchaRequest?> = _pending.asStateFlow()
    private var nextId = 0L

    suspend fun solve(challenge: CaptchaChallenge): String? = mutex.withLock {
        val request = CaptchaRequest(++nextId, challenge, CompletableDeferred())
        _pending.value = request
        try {
            val token = withTimeoutOrNull(CAPTCHA_SOLVE_TIMEOUT_MS) { request.result.await() }
            if (token == null) {
                if (request.result.isCompleted) {
                    Logging.i("Captcha", "Captcha ${request.id} was cancelled")
                } else {
                    Logging.w("Captcha", "Captcha ${request.id} timed out after ${CAPTCHA_SOLVE_TIMEOUT_MS / 1000}s")
                }
            }
            token
        } finally {
            _pending.value = null
        }
    }

    /** Completes whichever captcha is currently pending. */
    fun complete(token: String) {
        _pending.value?.result?.complete(token)
    }

    /** Completes only [request], so a stale UI callback cannot answer a newer challenge. */
    fun complete(request: CaptchaRequest, token: String) {
        if (_pending.value === request) request.result.complete(token)
    }

    /** Cancels whichever captcha is currently pending. */
    fun cancel() {
        _pending.value?.result?.complete(null)
    }

    /** Cancels only [request], so a stale dismiss cannot cancel a newer challenge. */
    fun cancel(request: CaptchaRequest) {
        if (_pending.value === request) request.result.complete(null)
    }
}
