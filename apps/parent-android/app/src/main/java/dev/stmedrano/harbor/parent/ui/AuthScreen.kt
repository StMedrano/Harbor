package dev.stmedrano.harbor.parent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.auth.AuthSessionRejected
import dev.stmedrano.harbor.parent.auth.AuthStorageLost
import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import dev.stmedrano.harbor.parent.ParentRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AccountPage { SIGN_IN, SIGN_UP, RECOVERY, RESET }

@Composable
fun AuthScreen(repository: ParentAuthRepository?, callback: String?, runtime: ParentRuntime? = null, onCallbackConsumed: () -> Unit) {
    ProtectSensitiveScreen()
    // Credentials never enter saved-instance state; rotation and navigation clear typed passwords.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(AccountPage.SIGN_IN) }
    var busy by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var recoveryReady by remember { mutableStateOf(false) }
    val identity = repository?.identity?.collectAsState()?.value
    val closing = runtime?.state?.collectAsState()?.value?.signingOut == true
    var message by remember { mutableStateOf(if (repository == null) "Development preview · offline fixture. Live sign-in is disabled." else "Sign in to your parent account.") }
    val scope = rememberCoroutineScope()

    fun navigate(destination: AccountPage) {
        password = ""; newPassword = ""; confirmPassword = ""
        page = destination
    }

    suspend fun runRequest(success: String, action: suspend (ParentAuthRepository) -> Unit) {
        val repo = repository ?: return
        busy = true
        message = "Working…"
        try {
            val state = withContext(Dispatchers.IO) {
                if (runtime == null) action(repo) else runtime.authAction { action(repo) }
                repo.hasVerifiedRecovery()
            }
            recoveryReady = state
            if (state) navigate(AccountPage.RESET)
            message = success
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: AuthSessionRejected) { message = "Session rejected. Sign in again."; recoveryReady = false }
        catch (_: AuthStorageLost) { message = "Secure storage reset. Sign in again."; recoveryReady = false }
        catch (_: Exception) {
            recoveryReady = repo.hasVerifiedRecovery()
            message = "Request failed. Check your connection, or cancel the email flow and request a fresh link."
        }
        finally { password = ""; newPassword = ""; confirmPassword = ""; busy = false }
    }

    fun submit(success: String, action: suspend (ParentAuthRepository) -> Unit) {
        if (!busy) scope.launch { runRequest(success, action) }
    }

    fun backToSignIn() {
        if (busy || closing) return
        navigate(page) // Clear typed secrets immediately, before cancellation can await storage.
        if (repository == null) navigate(AccountPage.SIGN_IN)
        else submit("Email flow cancelled. You can sign in or request a new link.") {
            it.cancelEmailFlow()
            recoveryReady = false
            navigate(AccountPage.SIGN_IN)
        }
    }

    BackHandler(enabled = page != AccountPage.SIGN_IN) { backToSignIn() }
    LaunchedEffect(repository, callback) {
        if (!restored) {
            runRequest("Session checked. Sign in if needed.") { it.restore() }
            restored = true
        }
        if (callback != null) {
            runRequest("Email verified. You can continue with your account.") { it.consumeCallback(callback) }
            onCallbackConsumed()
        }
    }

    val enabled = repository != null && !busy && !closing
    val navigationEnabled = !busy && !closing
    val accountForm = page == AccountPage.SIGN_IN || page == AccountPage.SIGN_UP
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.background,
            shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Harbor", style = MaterialTheme.typography.headlineLarge)
                Text("A calmer connection for your family.", style = MaterialTheme.typography.bodyLarge)
                if (accountForm) {
                    Row(Modifier.fillMaxWidth()) {
                        Tab(selected = page == AccountPage.SIGN_IN,
                            enabled = navigationEnabled, onClick = { if (page != AccountPage.SIGN_IN) backToSignIn() },
                            selectedContentColor = MaterialTheme.colorScheme.background,
                            unselectedContentColor = MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f), text = { Text("Sign in") })
                        Tab(selected = page == AccountPage.SIGN_UP,
                            enabled = navigationEnabled, onClick = { navigate(AccountPage.SIGN_UP) },
                            selectedContentColor = MaterialTheme.colorScheme.background,
                            unselectedContentColor = MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f), text = { Text("Create account") })
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(when (page) {
                    AccountPage.SIGN_IN -> "Welcome back"
                    AccountPage.SIGN_UP -> "Create your account"
                    AccountPage.RECOVERY -> "Forgot password"
                    AccountPage.RESET -> "Choose a new password"
                }, style = MaterialTheme.typography.headlineSmall)
                if (identity != null) Text("Parent session verified", style = MaterialTheme.typography.labelLarge)
                Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (page != AccountPage.RESET) {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                        enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                }
                if (accountForm) {
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                        enabled = enabled, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    if (page == AccountPage.SIGN_IN) {
                        TextButton(onClick = { navigate(AccountPage.RECOVERY) }, enabled = navigationEnabled) { Text("Forgot password?") }
                    }
                    Button(onClick = {
                        val secret = password
                        if (page == AccountPage.SIGN_IN) submit("Parent session verified.") { it.signIn(email, secret) }
                        else submit("Check your email. Open the confirmation link on this device. Return to sign in after confirmation.") { it.beginSignup(email, secret) }
                    }, enabled = enabled && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("auth-submit"),
                        colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.background)) {
                        Text(if (page == AccountPage.SIGN_IN) "Sign in" else "Create account")
                    }
                } else if (page == AccountPage.RECOVERY) {
                    Text("We'll email you a recovery link. Open it on this device to choose a new password.")
                    Button(onClick = { submit("Recovery email requested. Open its link on this device; keep this app installed.") { it.beginRecovery(email) } },
                        enabled = enabled && email.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.background)) { Text("Request recovery email") }
                } else {
                    Text(if (recoveryReady) "Recovery verified. Choose a new password." else "Request and verify a fresh recovery link first.")
                    OutlinedTextField(newPassword, { newPassword = it }, label = { Text("New password") }, singleLine = true,
                        enabled = enabled && recoveryReady, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(confirmPassword, { confirmPassword = it }, label = { Text("Confirm new password") }, singleLine = true,
                        enabled = enabled && recoveryReady, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { val secret = newPassword; submit("Password updated. You can now sign in with your new password.") { it.changePassword(secret); navigate(AccountPage.SIGN_IN) } },
                        enabled = enabled && recoveryReady && newPassword.isNotBlank() && newPassword == confirmPassword,
                        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.background)) { Text("Update password") }
                }
                if (page != AccountPage.SIGN_IN) {
                    TextButton(onClick = { backToSignIn() }, enabled = navigationEnabled) { Text("Back to sign in") }
                }
                if (busy) CircularProgressIndicator()
            }
        }
    }
}
