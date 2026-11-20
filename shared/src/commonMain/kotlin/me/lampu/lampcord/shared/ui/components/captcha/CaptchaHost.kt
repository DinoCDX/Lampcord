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
import dev.nucleusframework.webview.web.LoadingState
import dev.nucleusframework.webview.web.rememberWebViewState
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
private const val PAGE_SETTLE_MS = 600L

private val REVEAL_KINDS = setOf(
    "error",
    "expired",
    "challenge-expired",
    "execute-fallback-visible",
    "render-exception",
    "script-load-error",
    "stalled",
)

private val TERMINAL_KINDS = setOf(
    "error",
    "expired",
    "challenge-expired",
    "render-exception",
    "script-load-error",
    "stalled",
    "execute-failed",
    "execute-error",
)

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
    val webViewState = rememberWebViewState(CAPTCHA_PAGE_URL) {
        customUserAgentString = captchaUserAgent()
        androidWebSettings.apply {
            useWideViewPort = true
            domStorageEnabled = true
        }
    }

    val isDark = background.luminance() < 0.5f
    val script = remember(challenge, background) {
        captchaInjectionScript(
            challenge = challenge,
            background = background.toCssHex(),
            theme = if (isDark) "dark" else "light",
        )
    }

    val pageLoaded = webViewState.loadingState is LoadingState.Finished

    var widgetReady by remember(script) { mutableStateOf(false) }
    val events = remember(script) { Channel<CaptchaBridgeEvent>(Channel.UNLIMITED) }
    val handler = remember(events) { CaptchaBridgeHandler { events.trySend(it) } }

    DisposableEffect(bridge, handler) {
        bridge.register(handler)
        onDispose { bridge.unregister(handler) }
    }

    LaunchedEffect(webViewState) {
        webViewState.webSettings.isJavaScriptEnabled = true
    }

    LaunchedEffect(pageLoaded, script) {
        if (pageLoaded) {
            delay(PAGE_SETTLE_MS)
            navigator.evaluateJavaScript(script)
        }
    }

    LaunchedEffect(events) {
        for (event in events) {
            widgetReady = true
            when (event.kind) {
                "token" -> {
                    val token = event.value.orEmpty()
                    if (token.isBlank()) {
                        Logging.w("Captcha", "Captcha page returned an empty token")
                        onFailure()
                    } else {
                        Logging.i("Captcha", "Received solved token, length=${token.length}")
                        onToken(token)
                    }
                }
                "open" -> {
                    onReveal()
                    onExpandedChange(true)
                }
                "close" -> onExpandedChange(false)
                else -> {
                    if (event.kind != "ready") {
                        Logging.i("Captcha", "event: ${event.kind}${event.value?.let { ": $it" }.orEmpty()}")
                    }
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

internal fun captchaInjectionScript(
    challenge: CaptchaChallenge,
    background: String,
    theme: String,
): String {
    val sitekey = jsString(challenge.sitekey)
    val rqdata = challenge.rqdata?.let { jsString(it) } ?: "null"
    val bg = jsString(background)
    val themeLiteral = jsString(theme)
    val invisibleLiteral = if (challenge.isInvisible) "true" else "false"
    val actionLiteral = jsString(RECAPTCHA_ENTERPRISE_ACTION)
    val bridgeMethod = jsString(CAPTCHA_BRIDGE_METHOD)
    val scriptSrc = jsString(
        when (challenge.service) {
            CaptchaService.HCAPTCHA -> "https://js.hcaptcha.com/1/api.js?render=explicit&onload=lampcordReady"
            CaptchaService.RECAPTCHA -> "https://www.google.com/recaptcha/api.js?render=explicit&onload=lampcordReady"
            CaptchaService.RECAPTCHA_ENTERPRISE ->
                "https://www.google.com/recaptcha/enterprise.js?render=${challenge.sitekey.encodeURLParameter()}"
        },
    )
    val serviceBlock = when (challenge.service) {
        CaptchaService.HCAPTCHA -> HCAPTCHA_SERVICE_JS
        CaptchaService.RECAPTCHA -> RECAPTCHA_SERVICE_JS
        CaptchaService.RECAPTCHA_ENTERPRISE -> RECAPTCHA_ENTERPRISE_SERVICE_JS
    }
    val head = """
(function () {
  if (window.__lampcordTimer) { clearInterval(window.__lampcordTimer); }
  var stale = document.getElementById('lampcord-captcha');
  if (stale && stale.parentNode) { stale.parentNode.removeChild(stale); }
  var staleStyle = document.getElementById('lampcord-captcha-style');
  if (staleStyle && staleStyle.parentNode) { staleStyle.parentNode.removeChild(staleStyle); }

  var doc = document;

  var lock = doc.createElement('style');
  lock.id = 'lampcord-captcha-style';
  lock.textContent = 'html,body{overflow:hidden !important;width:100% !important;height:100% !important;' +
    'max-width:100% !important;margin:0 !important;overscroll-behavior:none !important;}' +
    '#lampcord-captcha{overflow:hidden;overscroll-behavior:none;}';
  (doc.head || doc.documentElement).appendChild(lock);

  var viewport = doc.querySelector('meta[name="viewport"]');
  if (!viewport) {
    viewport = doc.createElement('meta');
    viewport.name = 'viewport';
    (doc.head || doc.documentElement).appendChild(viewport);
  }
  viewport.content = 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no';

  var SITEKEY = $sitekey;
  var RQDATA = $rqdata;
  var INVISIBLE = $invisibleLiteral;
  var THEME = $themeLiteral;
  var ACTION = $actionLiteral;
  var METHOD = $bridgeMethod;
  var STALL_MS = 15000;

  var rendered = false;
  var widgetId = null;
  var executed = false;
  var executeTries = 0;
  var retryAfter = 0;
  var solved = false;
  var opened = false;
  var stallReported = false;
  var startedAt = Date.now();
  var eventSeq = 0;
  var outbox = [];
  window.addEventListener('error', function (ev) {
    send('js-error', String(ev.message || (ev.error && ev.error.message) || 'error').slice(0, 200));
  });
  window.addEventListener('unhandledrejection', function (ev) {
    send('js-rejection', errorText(ev.reason));
  });
  var origConsoleError = console.error;
  console.error = function () {
    try {
      send('console-error', Array.prototype.slice.call(arguments).map(function (a) { return String(a); }).join(' ').slice(0, 200));
    } catch (_) {}
    if (origConsoleError) { origConsoleError.apply(console, arguments); }
  };

  var overlay = doc.createElement('div');
  overlay.id = 'lampcord-captcha';
  overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:100000;' +
    'display:flex;align-items:center;justify-content:center;background:' + $bg + ';';
  var box = doc.createElement('div');
  box.id = 'lampcord-captcha-box';
  box.style.cssText = 'max-width:100%;';
  overlay.appendChild(box);
  doc.documentElement.appendChild(overlay);

  function flush() {
    var bridge = window.kmpJsBridge;
    if (!bridge || typeof bridge.callNative !== 'function') { return; }
    while (outbox.length > 0) {
      try { bridge.callNative(METHOD, outbox[0], function () {}); }
      catch (e) { return; }
      outbox.shift();
    }
  }
  function send(kind, value) {
    if (solved && kind !== 'token') { return; }
    eventSeq++;
    outbox.push(JSON.stringify({ seq: eventSeq, kind: kind, value: value === undefined ? null : value }));
    flush();
  }
  function errorText(e) {
    if (e === null || e === undefined) { return 'null'; }
    if (typeof e === 'string') { return e.slice(0, 200); }
    var parts = [];
    try {
      if (e.name) { parts.push(String(e.name)); }
      if (e.message) { parts.push(String(e.message)); }
      if (e.code !== undefined) { parts.push('code=' + String(e.code)); }
    } catch (_) {}
    if (parts.length) { return parts.join(': ').slice(0, 200); }
    try { return JSON.stringify(e).slice(0, 200); } catch (_) {}
    try { return Object.keys(e).join(',').slice(0, 200) || 'unknown'; } catch (_) {}
    return 'unknown';
  }

  window.lampcordDone = function (token) {
    if (solved) { return; }
    solved = true;
    send('token', token ? String(token) : '');
  };
  window.lampcordReady = function () { serviceRender(); };

  function fit() {
    var natural = box.scrollWidth;
    var available = doc.documentElement.clientWidth - 24;
    if (natural > 0 && natural > available) {
      box.style.transformOrigin = 'center center';
      box.style.transform = 'scale(' + (available / natural) + ')';
    } else {
      box.style.transform = '';
    }
  }

  function checkStall() {
    if (stallReported || solved || opened || !INVISIBLE) { return; }
    if (Date.now() - startedAt < STALL_MS) { return; }
    stallReported = true;
    send('stalled');
  }
"""
    val tail = """
  window.__lampcordTimer = setInterval(function () {
    flush();
    if (solved) {
      if (outbox.length === 0) { clearInterval(window.__lampcordTimer); }
      return;
    }
    if (!overlay.isConnected) { doc.documentElement.appendChild(overlay); }
    if (window.scrollX !== 0 || window.scrollY !== 0) { window.scrollTo(0, 0); }
    serviceRender();
    serviceExecute();
    checkStall();
    fit();
  }, 500);

  var s = doc.createElement('script');
  s.src = $scriptSrc;
  s.onerror = function () { send('script-load-error'); };
  doc.documentElement.appendChild(s);
})();
"""
    return head + serviceBlock + tail
}

private const val HCAPTCHA_SERVICE_JS = """
  var renderTries = 0;
  var renderRetryAt = 0;
  function serviceRender() {
    if (rendered || !window.hcaptcha || Date.now() < renderRetryAt) { return; }
    rendered = true;
    renderTries++;
    var container = renderTries % 2 === 1 ? box : 'lampcord-captcha-box';
    send('rendering', 'sitekey_len=' + String(SITEKEY).length + ' invisible=' + INVISIBLE + ' box_connected=' + box.isConnected + ' container=' + (container === box ? 'element' : 'id') + ' attempt=' + renderTries + ' id_lookup_ok=' + (doc.getElementById('lampcord-captcha-box') === box) + ' same_doc=' + (box.ownerDocument === doc) + ' in_root=' + doc.documentElement.contains(box));
    var opts = {
      sitekey: SITEKEY,
      theme: THEME,
      callback: window.lampcordDone,
      'open-callback': function () { opened = true; send('open'); },
      'close-callback': function () { send('close'); },
      'error-callback': function (code) { send('error', errorText(code)); },
      'expired-callback': function () { send('expired'); },
      'chalexpired-callback': function () { send('challenge-expired'); }
    };
    if (INVISIBLE) { opts.size = 'invisible'; }
    try {
      widgetId = hcaptcha.render(container, opts);
    } catch (e) {
      send('render-exception', errorText(e));
      return;
    }
    if (widgetId === undefined || widgetId === null) {
      var got = widgetId === undefined ? 'undefined' : 'null';
      widgetId = null;
      if (renderTries < 3) {
        try { box.innerHTML = ''; } catch (_) {}
        rendered = false;
        renderRetryAt = Date.now() + 1000;
        send('retry-render', 'attempt ' + renderTries + ' returned ' + got);
        return;
      }
      send('render-exception', 'render returned ' + got + ' after ' + renderTries + ' attempts');
      return;
    }
    send('rendered', String(widgetId));
    if (RQDATA) {
      try { hcaptcha.setData(widgetId, { rqdata: RQDATA }); }
      catch (e) { send('render-exception', 'setData: ' + errorText(e)); return; }
    }
    send('ready');
  }

  function fallbackToVisible(e) {
    if (!INVISIBLE) { return; }
    INVISIBLE = false;
    executed = true;
    rendered = false;
    widgetId = null;
    try { box.innerHTML = ''; } catch (_) {}
    serviceRender();
    if (widgetId !== null) { send('execute-fallback-visible', errorText(e)); }
  }

  function serviceExecute() {
    if (!INVISIBLE || widgetId === null || executed || executeTries >= 5) { return; }
    if (Date.now() < retryAfter) { return; }
    executeTries++;
    try {
      var pending = hcaptcha.execute(widgetId);
      executed = true;
      if (pending && typeof pending.catch === 'function') {
        pending.catch(function (e) { fallbackToVisible(e); });
      }
    } catch (e) {
      if (executeTries < 3) {
        retryAfter = Date.now() + 1000;
        send('retry-execute', executeTries + ': ' + errorText(e));
      } else {
        fallbackToVisible(e);
      }
    }
  }
"""

private const val RECAPTCHA_SERVICE_JS = """
  function serviceRender() {
    if (rendered || !window.grecaptcha || typeof grecaptcha.render !== 'function') { return; }
    rendered = true;
    var opts = {
      sitekey: SITEKEY,
      theme: THEME,
      callback: window.lampcordDone,
      'expired-callback': function () { send('expired'); },
      'error-callback': function () { send('error', 'recaptcha'); }
    };
    if (INVISIBLE) { opts.size = 'invisible'; }
    try {
      widgetId = grecaptcha.render(box, opts);
      send('ready');
    } catch (e) {
      send('render-exception', errorText(e));
    }
  }

  function serviceExecute() {
    if (!INVISIBLE || widgetId === null || executed || executeTries >= 5) { return; }
    executeTries++;
    try {
      executed = true;
      opened = true;
      grecaptcha.execute(widgetId);
    } catch (e) {
      send('execute-failed', executeTries + ': ' + errorText(e));
    }
  }
"""

private const val RECAPTCHA_ENTERPRISE_SERVICE_JS = """
  var enterpriseReady = false;

  function serviceRender() {
    if (rendered || !window.grecaptcha || !grecaptcha.enterprise) { return; }
    rendered = true;
    grecaptcha.enterprise.ready(function () {
      enterpriseReady = true;
      send('ready');
    });
  }

  function serviceExecute() {
    if (!enterpriseReady || executed || executeTries >= 5) { return; }
    executeTries++;
    executed = true;
    try {
      grecaptcha.enterprise.execute(SITEKEY, { action: ACTION })
        .then(function (token) { window.lampcordDone(token); })
        .catch(function (e) { send('execute-error', errorText(e)); });
    } catch (e) {
      send('execute-failed', executeTries + ': ' + errorText(e));
    }
  }
"""

private fun jsString(value: String): String = JsonPrimitive(value).toString()

private fun Color.toCssHex(): String =
    "#" + listOf(red, green, blue).joinToString("") {
        (it * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    }
