package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.auth.AuthSessionRejected
import dev.stmedrano.harbor.parent.auth.AuthStorageLost
import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AuthScreen(repository: ParentAuthRepository?, callback: String?, onCallbackConsumed: () -> Unit) {
    // Credentials never enter saved-instance state; rotation clears typed passwords.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var recoveryReady by remember { mutableStateOf(false) }
    val identity = repository?.identity?.collectAsState()?.value
    var message by remember { mutableStateOf(if (repository == null) "Development preview · offline fixture. Live sign-in is disabled." else "Sign in to your parent account.") }
    val scope = rememberCoroutineScope()

    suspend fun runRequest(success: String, action: suspend (ParentAuthRepository) -> Unit) {
        val repo = repository ?: return
        busy = true
        message = "Working…"
        try {
            val state = withContext(Dispatchers.IO) {
                action(repo)
                repo.hasVerifiedRecovery()
            }
            recoveryReady = state
            message = success
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: AuthSessionRejected) { message = "Session rejected. Sign in again."; recoveryReady = false }
        catch (_: AuthStorageLost) { message = "Secure storage reset. Sign in again."; recoveryReady = false }
        catch (_: Exception) { message = "Request failed. Check your connection, or cancel the email flow and request a fresh link." }
        finally { password = ""; newPassword = ""; confirmPassword = ""; busy = false }
    }

    fun submit(success: String, action: suspend (ParentAuthRepository) -> Unit) {
        if (!busy) scope.launch { runRequest(success, action) }
    }

    LaunchedEffect(repository, callback) {
        if (!restored) {
            runRequest("Session checked. Sign in if needed.") { it.restore() }
            restored = true
        }
        if (callback != null) {
            runRequest("Email verified. Recovery password changes require a verified recovery request.") { it.consumeCallback(callback) }
            onCallbackConsumed()
        }
    }

    val enabled = repository != null && !busy
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (identity != null) "Parent session verified" else "Parent account", style = MaterialTheme.typography.headlineSmall)
        Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
            enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            enabled = enabled, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(onClick = { val secret = password; submit("Parent session verified.") { it.signIn(email, secret) } }, enabled = enabled && email.isNotBlank() && password.isNotBlank()) { Text("Sign in") }
        OutlinedButton(onClick = { val secret = password; submit("Check your email. Open the confirmation link on this device. After confirmation, cancel the email flow to use a fresh sign-in.") { it.beginSignup(email, secret) } }, enabled = enabled && email.isNotBlank() && password.isNotBlank()) { Text("Create account") }
        OutlinedButton(onClick = { submit("Recovery email requested. Open its link on this device; keep this app installed.") { it.beginRecovery(email) } }, enabled = enabled && email.isNotBlank()) { Text("Request recovery email") }
        TextButton(onClick = { submit("Email flow cancelled. You can sign in or request a new link.") { it.cancelEmailFlow() } }, enabled = enabled) { Text("Cancel email flow") }
        Text("Password recovery", style = MaterialTheme.typography.titleMedium)
        Text(if (recoveryReady) "Recovery verified. Choose a new password." else "First verify the link from a recovery request started in this app.")
        OutlinedTextField(newPassword, { newPassword = it }, label = { Text("New password") }, singleLine = true,
            enabled = enabled && recoveryReady, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(confirmPassword, { confirmPassword = it }, label = { Text("Confirm new password") }, singleLine = true,
            enabled = enabled && recoveryReady, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(onClick = { val secret = newPassword; submit("Password updated. Recovery authorization consumed.") { it.changePassword(secret) } },
            enabled = enabled && recoveryReady && newPassword.isNotBlank() && newPassword == confirmPassword) { Text("Update password") }
        if (busy) CircularProgressIndicator()
    }
}
