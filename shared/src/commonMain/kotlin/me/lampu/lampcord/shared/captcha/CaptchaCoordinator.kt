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

internal const val CAPTCHA_SOLVE_TIMEOUT_MS = 5 * 60_000L

sealed interface CaptchaOutcome {
    data class Solved(val token: String) : CaptchaOutcome
    data object Cancelled : CaptchaOutcome
    data object TimedOut : CaptchaOutcome
}

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

    suspend fun solve(challenge: CaptchaChallenge): CaptchaOutcome = mutex.withLock {
        val request = CaptchaRequest(++nextId, challenge, CompletableDeferred())
        _pending.value = request
        try {
            val token = withTimeoutOrNull(CAPTCHA_SOLVE_TIMEOUT_MS) { request.result.await() }
            when {
                token != null -> {
                    Logging.i("Captcha", "Captcha ${request.id} solved")
                    CaptchaOutcome.Solved(token)
                }
                request.result.isCompleted -> {
                    Logging.i("Captcha", "Captcha ${request.id} was cancelled")
                    CaptchaOutcome.Cancelled
                }
                else -> {
                    Logging.w("Captcha", "Captcha ${request.id} timed out after ${CAPTCHA_SOLVE_TIMEOUT_MS / 1000}s")
                    CaptchaOutcome.TimedOut
                }
            }
        } finally {
            _pending.value = null
        }
    }

    fun complete(token: String) {
        _pending.value?.result?.complete(token)
    }

    fun complete(request: CaptchaRequest, token: String) {
        if (_pending.value === request) request.result.complete(token)
    }

    fun cancel() {
        _pending.value?.result?.complete(null)
    }

    fun cancel(request: CaptchaRequest) {
        if (_pending.value === request) request.result.complete(null)
    }
}
