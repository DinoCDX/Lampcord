package me.lampu.lampcord.shared.api

import io.ktor.client.call.save
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import me.lampu.lampcord.shared.captcha.CaptchaCoordinator
import me.lampu.lampcord.shared.captcha.CaptchaOutcome
import me.lampu.lampcord.shared.utils.Logging

open class CaptchaCancelledException(message: String = "Captcha was cancelled") : Exception(message)

class CaptchaTimeoutException : CaptchaCancelledException("Captcha timed out")

private const val MAX_CAPTCHA_ATTEMPTS = 3
private const val CAPTCHA_KEY_HEADER = "X-Captcha-Key"
private const val CAPTCHA_RQTOKEN_HEADER = "X-Captcha-Rqtoken"
private const val CAPTCHA_SESSION_HEADER = "X-Captcha-Session-Id"

class CaptchaPluginConfig {
    lateinit var coordinator: CaptchaCoordinator
}

val LampcordCaptchaPlugin = createClientPlugin("LampcordCaptcha", ::CaptchaPluginConfig) {
    val coordinator = pluginConfig.coordinator

    client.plugin(HttpSend).intercept { builder ->
        var call = execute(builder)
        var attempts = 0
        while (call.response.status == HttpStatusCode.BadRequest) {
            call = call.save()
            val challenge = parseCaptchaChallenge(call.response.bodyAsText())
            if (challenge == null) {
                Logging.i("Captcha", "400 response is not a captcha challenge; returning it unchanged")
                break
            }
            if (attempts >= MAX_CAPTCHA_ATTEMPTS) {
                Logging.w("Captcha", "Still rejected after $MAX_CAPTCHA_ATTEMPTS captcha attempts; giving up")
                break
            }
            if (builder.body is OutgoingContent.ReadChannelContent) {
                Logging.w("Captcha", "Request body is a stream and cannot be resent; not retrying after captcha")
                break
            }
            attempts++
            Logging.i(
                "Captcha",
                "Solving ${challenge.service.wireName} challenge, attempt $attempts of $MAX_CAPTCHA_ATTEMPTS",
            )
            when (val outcome = coordinator.solve(challenge)) {
                is CaptchaOutcome.Solved -> applyCaptchaHeaders(builder, outcome.token, challenge)
                CaptchaOutcome.Cancelled -> throw CaptchaCancelledException()
                CaptchaOutcome.TimedOut -> throw CaptchaTimeoutException()
            }
            call = execute(builder)
        }
        call
    }
}

private fun applyCaptchaHeaders(builder: HttpRequestBuilder, token: String, challenge: CaptchaChallenge) {
    builder.headers.remove(CAPTCHA_KEY_HEADER)
    builder.headers.remove(CAPTCHA_RQTOKEN_HEADER)
    builder.headers.remove(CAPTCHA_SESSION_HEADER)
    builder.headers.append(CAPTCHA_KEY_HEADER, token)
    challenge.rqtoken?.let { builder.headers.append(CAPTCHA_RQTOKEN_HEADER, it) }
    challenge.sessionId?.let { builder.headers.append(CAPTCHA_SESSION_HEADER, it) }
}
