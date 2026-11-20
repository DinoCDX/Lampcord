package me.lampu.lampcord.shared.ui.components.captcha

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nucleusframework.webview.jsbridge.IJsMessageHandler
import dev.nucleusframework.webview.jsbridge.JsMessage
import dev.nucleusframework.webview.jsbridge.rememberWebViewJsBridge
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.WebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewStateWithHTMLData
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import me.lampu.lampcord.shared.api.CaptchaChallenge
import me.lampu.lampcord.shared.api.CaptchaService
import me.lampu.lampcord.shared.api.RECAPTCHA_ENTERPRISE_ACTION
import me.lampu.lampcord.shared.captcha.CaptchaCoordinator
import me.lampu.lampcord.shared.generated.resources.Res
import me.lampu.lampcord.shared.generated.resources.cancel
import me.lampu.lampcord.shared.generated.resources.lampcord_captcha_description
import me.lampu.lampcord.shared.generated.resources.lampcord_captcha_failed
import me.lampu.lampcord.shared.generated.resources.lampcord_captcha_retry
import me.lampu.lampcord.shared.generated.resources.lampcord_captcha_title
import me.lampu.lampcord.shared.generated.resources.lampcord_captcha_webview_missing
import me.lampu.lampcord.shared.utils.Logging
import me.lampu.lampcord.shared.utils.configureCaptchaWebView
import me.lampu.lampcord.shared.utils.getPlatformName
import me.lampu.lampcord.shared.utils.isEmbeddedWebViewAvailable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private const val CAPTCHA_PAGE_URL = "https://discord.com/login"

internal const val CAPTCHA_BRIDGE_METHOD = "lampcord.captcha"

private const val CAPTCHA_CHROME_VERSION = "131.0.0.0"
private const val HIDDEN_CARD_ALPHA = 0.02f
private val TERMINAL_KINDS = setOf(
    "error",
    "expired",
    "challenge-expired",
    "render-exception",
    "script-load-error",
    "execute-failed",
    "execute-error",
)
private val REVEAL_KINDS = TERMINAL_KINDS + "open"

internal data class CaptchaBridgeEvent(val kind: String, val value: String?)

internal fun parseCaptchaBridgeEvent(params: String): CaptchaBridgeEvent? = runCatching {
    val obj = Json.parseToJsonElement(params).jsonObject
    val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
    CaptchaBridgeEvent(kind, obj["value"]?.jsonPrimitive?.contentOrNull)
}.getOrNull()

internal class CaptchaBridgeHandler(
    private val onEvent: (CaptchaBridgeEvent) -> Unit,
) : IJsMessageHandler {
    override fun methodName(): String = CAPTCHA_BRIDGE_METHOD

    override fun handle(message: JsMessage, navigator: WebViewNavigator?, callback: (String) -> Unit) {
        callback("ok")
        val event = parseCaptchaBridgeEvent(message.params)
        if (event == null) {
            Logging.w("Captcha", "Ignoring malformed captcha bridge message")
            return
        }
        onEvent(event)
    }
}

@Composable
fun CaptchaHost(coordinator: CaptchaCoordinator = koinInject()) {
    val request by coordinator.pending.collectAsState()
    val current = request ?: return

    key(current) {
        Dialog(
            onDismissRequest = { coordinator.cancel(current) },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
        ) {
            CaptchaDialogContent(
                challenge = current.challenge,
                onToken = { coordinator.complete(current, it) },
                onCancel = { coordinator.cancel(current) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CaptchaDialogContent(
    challenge: CaptchaChallenge,
    onToken: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val webViewAvailable = remember { isEmbeddedWebViewAvailable() }
    var expanded by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(!webViewAvailable || !challenge.isInvisible) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val cardAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else HIDDEN_CARD_ALPHA,
        label = "captchaCardAlpha",
    )
    val outerPadding by animateDpAsState(
        targetValue = if (expanded) 8.dp else 24.dp,
        label = "captchaOuterPadding",
    )
    val innerPadding by animateDpAsState(
        targetValue = if (expanded) 12.dp else 24.dp,
        label = "captchaInnerPadding",
    )

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(outerPadding)
                .graphicsLayer { alpha = cardAlpha },
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainerHigh,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(horizontal = innerPadding, vertical = 24.dp)) {
                Text(
                    text = stringResource(Res.string.lampcord_captcha_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                )
                if (!expanded) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.lampcord_captcha_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(if (expanded) 12.dp else 20.dp))
                if (webViewAvailable) {
                    key(attempt) {
                        CaptchaFrame(
                            challenge = challenge,
                            background = colors.surfaceContainer,
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            onReveal = { revealed = true },
                            onFailure = {
                                failed = true
                                revealed = true
                            },
                            onToken = {
                                Logging.i(
                                    "Captcha",
                                    if (revealed) "Solved after the dialog was shown" else "Solved silently, dialog never shown",
                                )
                                onToken(it)
                            },
                        )
                    }
                    if (failed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(Res.string.lampcord_captcha_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.error,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(Res.string.lampcord_captcha_webview_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (failed) {
                        TextButton(
                            onClick = {
                                Logging.i("Captcha", "Retrying captcha after a failure")
                                failed = false
                                revealed = true
                                attempt++
                            },
                        ) {
                            Text(stringResource(Res.string.lampcord_captcha_retry), color = colors.primary)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onCancel) {
                        Text(stringResource(Res.string.cancel), color = colors.primary)
                    }
                }
            }
        }

        if (!revealed) {
            Column(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { } },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ContainedLoadingIndicator(indicatorColor = colors.primary)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onCancel) {
                    Text(stringResource(Res.string.cancel), color = colors.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CaptchaFrame(
    challenge: CaptchaChallenge,
    background: Color,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onReveal: () -> Unit,
    onFailure: () -> Unit,
    onToken: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val navigator = rememberWebViewNavigator()
    val bridge = rememberWebViewJsBridge(navigator)
    val pageHtml = remember(challenge, background) {
        captchaPageHtml(
            challenge = challenge,
            background = background.toCssHex(),
            theme = if (background.luminance() < 0.5f) "dark" else "light",
        )
    }
    val webViewState = rememberWebViewStateWithHTMLData(data = pageHtml, baseUrl = CAPTCHA_PAGE_URL)
    var widgetReady by remember(pageHtml) { mutableStateOf(false) }
    val events = remember(pageHtml) { Channel<CaptchaBridgeEvent>(Channel.UNLIMITED) }
    val handler = remember(events) { CaptchaBridgeHandler { events.trySend(it) } }

    DisposableEffect(bridge, handler) {
        bridge.register(handler)
        onDispose { bridge.unregister(handler) }
    }

    LaunchedEffect(webViewState) {
        webViewState.webSettings.isJavaScriptEnabled = true
        webViewState.webSettings.customUserAgentString = captchaUserAgent()
        webViewState.webSettings.androidWebSettings.apply {
            useWideViewPort = true
            domStorageEnabled = true
        }
    }

    LaunchedEffect(events) {
        for (event in events) {
            widgetReady = true
            Logging.i("Captcha", "event: ${event.kind}${event.value?.let { ": $it" }.orEmpty()}")
            when (event.kind) {
                "token" -> {
                    val token = event.value.orEmpty()
                    if (token.isBlank()) onFailure() else onToken(token)
                }
                "open" -> {
                    onReveal()
                    onExpandedChange(true)
                }
                "close" -> onExpandedChange(false)
                else -> {
                    if (event.kind in REVEAL_KINDS) onReveal()
                    if (event.kind in TERMINAL_KINDS) onFailure()
                }
            }
        }
    }

    val frameHeight by animateDpAsState(
        targetValue = if (expanded) 480.dp else 96.dp,
        label = "captchaFrameHeight",
    )

    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(frameHeight)
            .clip(shape)
            .background(background)
            .border(1.dp, colors.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        WebView(
            state = webViewState,
            navigator = navigator,
            webViewJsBridge = bridge,
            modifier = Modifier.fillMaxSize(),
            onCreated = { native -> configureCaptchaWebView(native) },
        )
        if (!widgetReady) {
            ContainedLoadingIndicator(indicatorColor = colors.primary)
        }
    }
}

private fun captchaUserAgent(): String {
    val chrome = "Chrome/$CAPTCHA_CHROME_VERSION"
    return when (getPlatformName()) {
        "android" -> "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Mobile Safari/537.36"
        "ios" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        "macos" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        "windows" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        else -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }
}

internal fun captchaPageHtml(
    challenge: CaptchaChallenge,
    background: String,
    theme: String,
): String {
    val script = captchaInjectionScript(challenge, background, theme)
    return """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
html, body { margin: 0; padding: 0; background: $background; }
#captcha { display: flex; justify-content: center; align-items: center; min-height: 78px; }
</style>
</head>
<body>
<div id="captcha"></div>
<script>
$script
</script>
</body>
</html>
"""
}

internal fun captchaInjectionScript(
    challenge: CaptchaChallenge,
    background: String,
    theme: String,
): String {
    val loadSrc = when (challenge.service) {
        CaptchaService.HCAPTCHA -> "https://js.hcaptcha.com/1/api.js?render=explicit"
        CaptchaService.RECAPTCHA -> "https://www.google.com/recaptcha/api.js?render=explicit"
        CaptchaService.RECAPTCHA_ENTERPRISE ->
            "https://www.google.com/recaptcha/enterprise.js?render=${challenge.sitekey.encodeURLParameter()}"
    }
    return """
var SITEKEY = ${jsString(challenge.sitekey)};
var RQDATA = ${challenge.rqdata?.let { jsString(it) } ?: "null"};
var INVISIBLE = ${if (challenge.isInvisible) "true" else "false"};
var THEME = ${jsString(theme)};
var ACTION = ${jsString(RECAPTCHA_ENTERPRISE_ACTION)};
var METHOD = ${jsString(CAPTCHA_BRIDGE_METHOD)};
var SERVICE = ${jsString(challenge.service.wireName)};
var LOAD_SRC = ${jsString(loadSrc)};
var BACKGROUND = ${jsString(background)};

var seq = 0;
var outbox = [];
var widgetId = null;
var rendered = false;
var executed = false;

function flush() {
  var bridge = window.kmpJsBridge;
  if (!bridge || typeof bridge.callNative !== 'function') { return; }
  while (outbox.length > 0) {
    try { bridge.callNative(METHOD, outbox[0], function () {}); } catch (e) { return; }
    outbox.shift();
  }
}

function send(kind, value) {
  seq++;
  outbox.push(JSON.stringify({ seq: seq, kind: kind, value: value === undefined ? null : String(value) }));
  flush();
}

function errorText(e) {
  if (e === null || e === undefined) { return 'null'; }
  if (typeof e === 'string') { return e.slice(0, 200); }
  var parts = [];
  if (e.name) { parts.push(String(e.name)); }
  if (e.message) { parts.push(String(e.message)); }
  if (parts.length) { return parts.join(': ').slice(0, 200); }
  try { return JSON.stringify(e).slice(0, 200); } catch (_) { return 'unknown'; }
}

window.addEventListener('error', function (ev) { send('js-error', ev.message); });
window.addEventListener('unhandledrejection', function (ev) { send('js-rejection', errorText(ev.reason)); });
var nativeConsoleError = console.error;
console.error = function () {
  send('console-error', Array.prototype.map.call(arguments, String).join(' ').slice(0, 200));
  if (nativeConsoleError) { nativeConsoleError.apply(console, arguments); }
};
setInterval(flush, 250);

window.lampcordToken = function (token) { send('token', token || ''); };

function serviceExecute() {
  if (!INVISIBLE || widgetId === null || executed) { return; }
  executed = true;
  try { hcaptcha.execute(widgetId); } catch (e) { send('execute-failed', errorText(e)); }
}

function renderHcaptcha() {
  rendered = true;
  var opts = {
    sitekey: SITEKEY,
    theme: THEME,
    callback: window.lampcordToken,
    'open-callback': function () { send('open'); },
    'close-callback': function () { send('close'); },
    'error-callback': function (code) { send('error', code); },
    'expired-callback': function () { send('expired'); },
    'chalexpired-callback': function () { send('challenge-expired'); }
  };
  if (INVISIBLE) { opts.size = 'invisible'; }
  try {
    widgetId = hcaptcha.render('captcha', opts);
  } catch (e) {
    send('render-exception', errorText(e));
    return;
  }
  if (widgetId === undefined || widgetId === null) {
    send('render-exception', 'render returned no widget id');
    return;
  }
  if (RQDATA) {
    try { hcaptcha.setData(widgetId, { rqdata: RQDATA }); } catch (e) {
      send('render-exception', 'setData: ' + errorText(e));
      return;
    }
  }
  send('ready', widgetId);
  serviceExecute();
}

function renderRecaptcha() {
  rendered = true;
  var opts = {
    sitekey: SITEKEY,
    theme: THEME,
    callback: window.lampcordToken,
    'expired-callback': function () { send('expired'); },
    'error-callback': function () { send('error', 'recaptcha'); }
  };
  if (INVISIBLE) { opts.size = 'invisible'; }
  try {
    widgetId = grecaptcha.render('captcha', opts);
  } catch (e) {
    send('render-exception', errorText(e));
    return;
  }
  send('ready', widgetId);
  if (INVISIBLE && !executed) {
    executed = true;
    try { grecaptcha.execute(widgetId); } catch (e) { send('execute-failed', errorText(e)); }
  }
}

function renderEnterprise() {
  rendered = true;
  grecaptcha.enterprise.ready(function () {
    send('ready');
    if (executed) { return; }
    executed = true;
    try {
      grecaptcha.enterprise.execute(SITEKEY, { action: ACTION })
        .then(function (token) { window.lampcordToken(token); })
        .catch(function (e) { send('execute-error', errorText(e)); });
    } catch (e) {
      send('execute-failed', errorText(e));
    }
  });
}

window.lampcordReady = function () {
  if (rendered) { return; }
  if (SERVICE === 'hcaptcha') { renderHcaptcha(); }
  else if (SERVICE === 'recaptcha') { renderRecaptcha(); }
  else { renderEnterprise(); }
};

var loader = document.createElement('script');
loader.src = LOAD_SRC;
loader.async = true;
loader.onload = function () { window.lampcordReady(); };
loader.onerror = function () { send('script-load-error'); };
document.head.appendChild(loader);
""".trimIndent()
}

private fun jsString(value: String): String = JsonPrimitive(value).toString()

private fun Color.toCssHex(): String =
    "#" + listOf(red, green, blue).joinToString("") {
        (it * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    }
