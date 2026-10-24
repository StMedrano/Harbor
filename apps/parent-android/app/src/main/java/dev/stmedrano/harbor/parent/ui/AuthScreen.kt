package dev.stmedrano.harbor.parent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.VisualTransformation
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
fun AuthScreen(repository: ParentAuthRepository?, callback: String?, runtime: ParentRuntime? = null,
    onAuthenticated: () -> Unit = {}, onCallbackConsumed: () -> Unit) {
    ProtectSensitiveScreen()
    // Credentials never enter saved-instance state; rotation and navigation clear typed passwords.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var signupConfirmation by remember { mutableStateOf("") }
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
        password = ""; signupConfirmation = ""; newPassword = ""; confirmPassword = ""; displayName = ""
        page = destination
    }

    fun openParentIfVerified() {
        val repo = repository ?: return
        if (repo.identity.value != null && !repo.hasVerifiedRecovery() && runtime?.state?.value?.signingOut != true) onAuthenticated()
    }

    suspend fun runRequest(success: String, action: suspend (ParentAuthRepository) -> Unit): Boolean {
        val repo = repository ?: return false
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
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: AuthSessionRejected) { message = "Session rejected. Sign in again."; recoveryReady = false }
        catch (_: AuthStorageLost) { message = "Secure storage reset. Sign in again."; recoveryReady = false }
        catch (_: Exception) {
            recoveryReady = repo.hasVerifiedRecovery()
            message = "Request failed. Check your connection, or cancel the email flow and request a fresh link."
        }
        finally { password = ""; signupConfirmation = ""; newPassword = ""; confirmPassword = ""; busy = false }
        return false
    }

    fun submit(success: String, afterSuccess: () -> Unit = {}, action: suspend (ParentAuthRepository) -> Unit) {
        if (!busy) scope.launch { if (runRequest(success, action)) afterSuccess() }
    }

    fun backToSignIn() {
        if (busy || closing) return
        navigate(page) // Clear typed secrets immediately, before cancellation can await storage.
        if (repository == null) navigate(AccountPage.SIGN_IN)
        else submit("Email flow cancelled. You can sign in or request a new link.", ::openParentIfVerified) {
            it.cancelEmailFlow()
            recoveryReady = false
            navigate(AccountPage.SIGN_IN)
        }
    }

    BackHandler(enabled = page != AccountPage.SIGN_IN) { backToSignIn() }
    LaunchedEffect(repository, callback) {
        if (!restored) {
            val accepted = runRequest("Session checked. Sign in if needed.") { it.restore() }
            restored = true
            if (accepted && callback == null) openParentIfVerified()
        }
        if (callback != null) {
            val accepted = runRequest("Email verified. You can continue with your account.") { it.consumeCallback(callback) }
            onCallbackConsumed()
            if (accepted) openParentIfVerified()
        }
    }

    val enabled = repository != null && !busy && !closing
    val navigationEnabled = !busy && !closing
    val accountForm = page == AccountPage.SIGN_IN || page == AccountPage.SIGN_UP
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        HarborLogo(HarborLogoVariant.LOCKUP, 150.dp, Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HarborEyebrow(if (page == AccountPage.SIGN_IN || page == AccountPage.SIGN_UP) "Parent account" else "Account recovery")
            Text(when (page) {
                AccountPage.SIGN_IN -> "Welcome back"
                AccountPage.SIGN_UP -> "Create your account"
                AccountPage.RECOVERY -> "Reset your password"
                AccountPage.RESET -> "Choose a new password"
            }, style = MaterialTheme.typography.headlineSmall)
            Text(when (page) {
                AccountPage.SIGN_IN -> "Sign in to see how your family is doing."
                AccountPage.SIGN_UP -> "Set up Harbor for your family."
                AccountPage.RECOVERY -> "Enter your email and we'll send you a reset link."
                AccountPage.RESET -> "Use a new password for your Harbor account."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (identity != null) Text("Parent session verified", style = MaterialTheme.typography.labelLarge)
            Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (page != AccountPage.RESET) {
                HarborField(email, { email = it }, "Email", enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            }
            if (accountForm) {
                key(page) {
                    if (page == AccountPage.SIGN_UP) {
                        // The name the rest of the family sees. Without it they would read only as "A parent".
                        HarborField(displayName, { displayName = it }, "Your name", enabled = enabled,
                            hint = "The other parents and your children see this.")
                    }
                    HarborPasswordField(password, { password = it }, "Password", enabled)
                    if (page == AccountPage.SIGN_UP) {
                        HarborPasswordField(signupConfirmation, { signupConfirmation = it }, "Confirm password", enabled)
                    }
                }
                HarborButton(onClick = {
                    val secret = password
                    if (page == AccountPage.SIGN_IN) submit("Parent session verified.", ::openParentIfVerified) { it.signIn(email, secret) }
                    else { val name = displayName; submit("Check your email. Open the confirmation link on this device. Return to sign in after confirmation.") { it.beginSignup(email, secret, name) } }
                }, enabled = enabled && email.isNotBlank() && password.isNotBlank() &&
                    (page == AccountPage.SIGN_IN || (password == signupConfirmation && displayName.isNotBlank())),
                    modifier = Modifier.fillMaxWidth().testTag("auth-submit")) {
                    Text(if (page == AccountPage.SIGN_IN) "Sign in" else "Create account")
                }
                if (page == AccountPage.SIGN_IN) {
                    TextButton(onClick = { navigate(AccountPage.RECOVERY) }, enabled = navigationEnabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("Forgot password?") }
                    Text("New to Harbor?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { navigate(AccountPage.SIGN_UP) }, enabled = navigationEnabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("Create account") }
                }
            } else if (page == AccountPage.RECOVERY) {
                Text("Open the email link on this device. Keep Harbor installed until recovery is complete.", style = MaterialTheme.typography.bodySmall)
                HarborButton(onClick = { submit("If an account exists for that address, a recovery link is on its way. Open it on this device.") { it.beginRecovery(email) } },
                    enabled = enabled && email.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Request recovery email") }
            } else {
                Text(if (recoveryReady) "Recovery verified. Choose a new password." else "Request and verify a fresh recovery link first.")
                HarborPasswordField(newPassword, { newPassword = it }, "New password", enabled && recoveryReady)
                HarborPasswordField(confirmPassword, { confirmPassword = it }, "Confirm new password", enabled && recoveryReady)
                HarborButton(onClick = { val secret = newPassword; submit("Password updated. You can now sign in with your new password.", ::openParentIfVerified) { it.changePassword(secret); navigate(AccountPage.SIGN_IN) } },
                    enabled = enabled && recoveryReady && newPassword.isNotBlank() && newPassword == confirmPassword,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Update password") }
            }
            if (page != AccountPage.SIGN_IN) {
                TextButton(onClick = { backToSignIn() }, enabled = navigationEnabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("Back to sign in") }
            }
            if (busy) CircularProgressIndicator()
        }
    }
}

@Composable
private fun HarborPasswordField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    var visible by remember(label) { mutableStateOf(false) }
    // Design system `PasswordField`: a real Show / Hide button, the rule in the hint before anyone gets it wrong.
    HarborField(value, onChange, label, enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailing = {
            TextButton(onClick = { visible = !visible }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "${if (visible) "Hide" else "Show"} ${label.lowercase()}" }) {
                Text(if (visible) "Hide" else "Show", color = harbor.acc)
            }
        })
}
