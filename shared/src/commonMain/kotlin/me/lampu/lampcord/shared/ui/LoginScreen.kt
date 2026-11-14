package me.lampu.lampcord.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.lampu.lampcord.shared.api.CdnUrls
import me.lampu.lampcord.shared.api.RemoteAuthClient
import me.lampu.lampcord.shared.api.RemoteAuthState
import me.lampu.lampcord.shared.generated.resources.Res
import me.lampu.lampcord.shared.generated.resources.back_to_login
import me.lampu.lampcord.shared.generated.resources.enter_phone_or_email
import me.lampu.lampcord.shared.generated.resources.error
import me.lampu.lampcord.shared.generated.resources.form_label_password
import me.lampu.lampcord.shared.generated.resources.lampcord_login_backup_code_label
import me.lampu.lampcord.shared.generated.resources.lampcord_login_backup_subtitle
import me.lampu.lampcord.shared.generated.resources.lampcord_login_backup_title
import me.lampu.lampcord.shared.generated.resources.lampcord_login_error_captcha_cancelled
import me.lampu.lampcord.shared.generated.resources.lampcord_login_error_enter_token
import me.lampu.lampcord.shared.generated.resources.lampcord_login_error_failed
import me.lampu.lampcord.shared.generated.resources.lampcord_login_error_invalid
import me.lampu.lampcord.shared.generated.resources.lampcord_login_error_invalid_mfa
import me.lampu.lampcord.shared.generated.resources.lampcord_login_error_unexpected
import me.lampu.lampcord.shared.generated.resources.lampcord_login_mfa_code_label
import me.lampu.lampcord.shared.generated.resources.lampcord_login_mfa_subtitle
import me.lampu.lampcord.shared.generated.resources.lampcord_login_mfa_title
import me.lampu.lampcord.shared.generated.resources.lampcord_login_password_hide
import me.lampu.lampcord.shared.generated.resources.lampcord_login_password_show
import me.lampu.lampcord.shared.generated.resources.lampcord_login_qr_approve
import me.lampu.lampcord.shared.generated.resources.lampcord_login_qr_prompt
import me.lampu.lampcord.shared.generated.resources.lampcord_login_restore_account
import me.lampu.lampcord.shared.generated.resources.lampcord_login_subtitle
import me.lampu.lampcord.shared.generated.resources.lampcord_login_title
import me.lampu.lampcord.shared.generated.resources.lampcord_login_token_hide
import me.lampu.lampcord.shared.generated.resources.lampcord_login_token_label
import me.lampu.lampcord.shared.generated.resources.lampcord_login_token_show
import me.lampu.lampcord.shared.generated.resources.lampcord_login_use_authenticator
import me.lampu.lampcord.shared.generated.resources.lampcord_login_use_backup
import me.lampu.lampcord.shared.generated.resources.lampcord_login_with_credentials
import me.lampu.lampcord.shared.generated.resources.lampcord_login_with_token
import me.lampu.lampcord.shared.generated.resources.login
import me.lampu.lampcord.shared.generated.resources.retry
import me.lampu.lampcord.shared.generated.resources.user_settings_avatar
import me.lampu.lampcord.shared.generated.resources.verify
import me.lampu.lampcord.shared.model.LoginFailure
import me.lampu.lampcord.shared.state.SessionManager
import me.lampu.lampcord.shared.ui.components.ContainedLoadingIndicator
import me.lampu.lampcord.shared.ui.icons.Icons
import me.lampu.lampcord.shared.ui.kit.handCursor
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import qrcode.QRCode
import qrcode.raw.ErrorCorrectionLevel
import kotlin.math.min

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    sessionManager: SessionManager = koinInject(),
    remoteAuthClient: RemoteAuthClient = koinInject(),
    onLoginSuccess: () -> Unit
) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var useTokenLogin by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var mfaCode by remember { mutableStateOf("") }
    var mfaTicket by remember { mutableStateOf<String?>(null) }
    var mfaInstanceId by remember { mutableStateOf<String?>(null) }
    var mfaType by remember { mutableStateOf("totp") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<LoginFailure?>(null) }

    val passwordFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val remoteAuthState by remoteAuthClient.state.collectAsState()

    val enterTokenError = stringResource(Res.string.lampcord_login_error_enter_token)
    val loginFailedError = stringResource(Res.string.lampcord_login_error_failed)
    val invalidLoginError = stringResource(Res.string.lampcord_login_error_invalid)
    val invalidMfaError = stringResource(Res.string.lampcord_login_error_invalid_mfa)
    val captchaCancelledError = stringResource(Res.string.lampcord_login_error_captcha_cancelled)
    val unexpectedError = stringResource(Res.string.lampcord_login_error_unexpected)

    val inCredentialsForm = mfaTicket == null && !useTokenLogin
    val passwordError = inCredentialsForm && failure == LoginFailure.INVALID_CREDENTIALS
    val canRestoreAccount = inCredentialsForm &&
        (failure == LoginFailure.ACCOUNT_DISABLED || failure == LoginFailure.ACCOUNT_SCHEDULED_FOR_DELETION)
    val bannerError = if (passwordError) null else errorMessage

    LaunchedEffect(remoteAuthState) {
        val state = remoteAuthState
        if (state is RemoteAuthState.Finished) {
            sessionManager.connect(state.token)
            onLoginSuccess()
        }
    }

    LaunchedEffect(useTokenLogin) {
        if (useTokenLogin) {
            remoteAuthClient.stop()
        } else {
            remoteAuthClient.start()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            remoteAuthClient.stop()
        }
    }

    fun resetMfa() {
        mfaTicket = null
        mfaInstanceId = null
        mfaCode = ""
        errorMessage = null
        failure = null
    }

    fun performLogin(undelete: Boolean = false) {
        scope.launch {
            isLoading = true
            errorMessage = null
            failure = null
            try {
                val currentTicket: String? = mfaTicket
                if (currentTicket == null) {
                    if (useTokenLogin) {
                        val authToken = token.trim()
                        if (authToken.isBlank()) {
                            errorMessage = enterTokenError
                        } else {
                            sessionManager.connect(authToken)
                            onLoginSuccess()
                        }
                    } else {
                        val response = sessionManager.login(login.trim(), password, undelete)
                        when {
                            response == null -> errorMessage = loginFailedError
                            response.token != null -> {
                                sessionManager.connect(response.token)
                                onLoginSuccess()
                            }
                            response.mfa == true && response.ticket != null -> {
                                mfaTicket = response.ticket
                                mfaInstanceId = response.login_instance_id
                                mfaCode = ""
                            }
                            response.captchaCancelled -> errorMessage = captchaCancelledError
                            else -> {
                                errorMessage = response.message ?: invalidLoginError
                                failure = response.failure
                            }
                        }
                    }
                } else {
                    val res = sessionManager.verifyMFA(
                        code = mfaCode.trim(),
                        ticket = currentTicket,
                        type = mfaType,
                        loginInstanceId = mfaInstanceId
                    )
                    if (res?.token != null) {
                        onLoginSuccess()
                    } else {
                        errorMessage = res?.message ?: invalidMfaError
                    }
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: unexpectedError
            } finally {
                isLoading = false
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "          へ   ♡     ╱|、\n" +
                           "     ૮  -   ՛ )      (`   -  7\n" +
                           "       /   ⁻  ៸|       |、⁻〵\n" +
                           " 乀 (ˍ, ل ل        じしˍ,)ノ",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(Res.string.lampcord_login_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (mfaTicket == null) {
                        stringResource(Res.string.lampcord_login_subtitle)
                    } else if (mfaType == "totp") {
                        stringResource(Res.string.lampcord_login_mfa_subtitle)
                    } else {
                        stringResource(Res.string.lampcord_login_backup_subtitle)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = AlertDialogDefaults.TonalElevation
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (mfaTicket == null) {
                            if (useTokenLogin) {
                                OutlinedTextField(
                                    value = token,
                                    onValueChange = { token = it; errorMessage = null },
                                    label = { Text(stringResource(Res.string.lampcord_login_token_label)) },
                                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    enabled = !isLoading,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.None,
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = { performLogin() }),
                                    trailingIcon = {
                                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                            Icon(
                                                imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (tokenVisible) stringResource(Res.string.lampcord_login_token_hide) else stringResource(Res.string.lampcord_login_token_show)
                                            )
                                        }
                                    }
                                )
                            } else {
                                OutlinedTextField(
                                    value = login,
                                    onValueChange = { login = it; errorMessage = null; failure = null },
                                    label = { Text(stringResource(Res.string.enter_phone_or_email)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    enabled = !isLoading,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.None,
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Next
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onNext = { passwordFocus.requestFocus() }
                                    )
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it; errorMessage = null; failure = null },
                                    label = { Text(stringResource(Res.string.form_label_password)) },
                                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                                    singleLine = true,
                                    enabled = !isLoading,
                                    isError = passwordError,
                                    supportingText = if (passwordError) ({ Text(errorMessage.orEmpty()) }) else null,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = { performLogin() }),
                                    trailingIcon = {
                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Icon(
                                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (passwordVisible) stringResource(Res.string.lampcord_login_password_hide) else stringResource(Res.string.lampcord_login_password_show)
                                            )
                                        }
                                    }
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = mfaCode,
                                onValueChange = { next ->
                                    errorMessage = null
                                    mfaCode = if (mfaType == "totp") next.filter { it.isDigit() }.take(6) else next.take(32)
                                },
                                label = {
                                    Text(
                                        if (mfaType == "totp") stringResource(Res.string.lampcord_login_mfa_code_label)
                                        else stringResource(Res.string.lampcord_login_backup_code_label)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isLoading,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (mfaType == "totp") KeyboardType.Number else KeyboardType.Text,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { performLogin() })
                            )
                        }

                        if (bannerError != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = bannerError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { performLogin() },
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth().height(56.dp).handCursor(),
                            shape = MaterialTheme.shapes.large
                        ) {
                            if (isLoading) {
                                ContainedLoadingIndicator(
                                    modifier = Modifier.size(24.dp),
                                    containerColor = MaterialTheme.colorScheme.onPrimary,
                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Text(
                                    text = if (mfaTicket == null) stringResource(Res.string.login) else stringResource(Res.string.verify),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }

                        if (canRestoreAccount) {
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = { performLogin(undelete = true) },
                                enabled = !isLoading,
                                modifier = Modifier.fillMaxWidth().height(48.dp).handCursor(),
                                shape = MaterialTheme.shapes.large
                            ) {
                                Text(stringResource(Res.string.lampcord_login_restore_account))
                            }
                        }

                        if (mfaTicket != null) {
                            TextButton(
                                onClick = {
                                    mfaType = if (mfaType == "totp") "backup" else "totp"
                                    mfaCode = ""
                                    errorMessage = null
                                },
                                modifier = Modifier.handCursor()
                            ) {
                                Text(
                                    if (mfaType == "totp") stringResource(Res.string.lampcord_login_use_backup)
                                    else stringResource(Res.string.lampcord_login_use_authenticator)
                                )
                            }
                            TextButton(
                                onClick = { resetMfa() },
                                modifier = Modifier.handCursor()
                            ) {
                                Text(stringResource(Res.string.back_to_login))
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    useTokenLogin = !useTokenLogin
                                    errorMessage = null
                                    failure = null
                                },
                                modifier = Modifier.handCursor()
                            ) {
                                Text(
                                    if (useTokenLogin) stringResource(Res.string.lampcord_login_with_credentials)
                                    else stringResource(Res.string.lampcord_login_with_token)
                                )
                            }
                        }
                    }
                }

                if (mfaTicket == null && !useTokenLogin) {
                    Spacer(modifier = Modifier.height(24.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = AlertDialogDefaults.TonalElevation
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(Res.string.lampcord_login_qr_prompt),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Surface(
                                modifier = Modifier.size(200.dp),
                                shape = MaterialTheme.shapes.large,
                                color = Color.White,
                                tonalElevation = 2.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    when (val state = remoteAuthState) {
                                        is RemoteAuthState.QRReady -> {
                                            val qrCode = remember(state.url) {
                                                QRCode.ofSquares()
                                                    .withErrorCorrectionLevel(ErrorCorrectionLevel.MEDIUM)
                                                    .build(state.url)
                                            }
                                            Canvas(
                                                modifier = Modifier.fillMaxSize().padding(8.dp)
                                            ) {
                                                drawLocalQrCode(qrCode)
                                            }
                                        }
                                        is RemoteAuthState.UserScanned -> {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                val avatarUrl = state.user.avatar?.let { CdnUrls.getUserAvatarUrl(state.user.id, it, 128) }
                                                if (avatarUrl != null) {
                                                    me.lampu.lampcord.shared.ui.components.AsyncImage(
                                                        model = avatarUrl,
                                                        contentDescription = stringResource(Res.string.user_settings_avatar),
                                                        modifier = Modifier.size(64.dp).clip(CircleShape)
                                                    )
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    state.user.global_name ?: state.user.username,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    stringResource(Res.string.lampcord_login_qr_approve),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        is RemoteAuthState.Connecting -> {
                                            ContainedLoadingIndicator(modifier = Modifier.size(32.dp))
                                        }
                                        is RemoteAuthState.Error -> {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier.padding(16.dp)
                                            ) {
                                                Text(
                                                    stringResource(Res.string.error),
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.labelLarge
                                                )
                                                TextButton(
                                                    onClick = { scope.launch { remoteAuthClient.start() } },
                                                    modifier = Modifier.handCursor()
                                                ) {
                                                    Text(stringResource(Res.string.retry))
                                                }
                                            }
                                        }
                                        else -> {
                                            ContainedLoadingIndicator(modifier = Modifier.size(32.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLocalQrCode(qrCode: QRCode) {
    drawRect(color = Color.White)
    val quietZone = 4
    val moduleCount = qrCode.rawData.size
    val totalModules = moduleCount + quietZone * 2
    val moduleSize = min(size.width, size.height) / totalModules
    val codeSize = totalModules * moduleSize
    val offsetX = (size.width - codeSize) / 2f
    val offsetY = (size.height - codeSize) / 2f
    qrCode.rawData.forEach { cells ->
        cells.forEach { cell ->
            if (cell.dark) {
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(
                        x = offsetX + (cell.col + quietZone) * moduleSize,
                        y = offsetY + (cell.row + quietZone) * moduleSize,
                    ),
                    size = Size(moduleSize, moduleSize),
                )
            }
        }
    }
}
