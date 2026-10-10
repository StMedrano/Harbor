package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.*

@Composable fun ParentApprovalScreen(onSignIn: suspend (String, String) -> Boolean,
    onVerify: suspend (String) -> Boolean, onRemove: suspend () -> Boolean, onCancel: () -> Unit) {
    ProtectSensitiveScreen()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun cancel() { email = ""; password = ""; code = ""; operation?.cancel(); onCancel() }
    BackHandler { cancel() }
    DisposableEffect(Unit) { onDispose { cancel() } }
    fun perform(next: Int, failure: String, action: suspend () -> Boolean) {
        if (busy) return
        busy = true; message = null
        operation = scope.launch {
            try {
                if (withContext(Dispatchers.IO) { action() }) step = next else message = failure
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = failure }
            finally { password = ""; code = ""; busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Parent approval", style = MaterialTheme.typography.headlineSmall)
        Text("A parent must confirm removal before this phone can change roles.")
        when (step) {
            0 -> {
                OutlinedTextField(email, { if (!busy) email = it }, label = { Text("Parent email") },
                    singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(password, { if (!busy) password = it }, label = { Text("Parent password") },
                    singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                HarborButton(enabled = !busy && email.isNotBlank() && password.isNotBlank(), onClick = {
                    val submittedEmail = email; val submittedPassword = password; password = ""
                    perform(1, "Use a confirmed parent account with an authenticator set up.") { onSignIn(submittedEmail, submittedPassword) }
                }) { Text(if (busy) "Checking…" else "Continue as parent") }
            }
            1 -> {
                OutlinedTextField(code, { if (!busy && it.length <= 6 && it.all { char -> char in '0'..'9' }) code = it },
                    label = { Text("Authenticator code") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                HarborButton(enabled = !busy && code.length == 6, onClick = {
                    val submitted = code; code = ""
                    perform(2, "Parent approval was not confirmed for this phone.") { onVerify(submitted) }
                }) { Text(if (busy) "Checking…" else "Verify parent approval") }
            }
            else -> HarborButton(enabled = !busy, onClick = {
                perform(2, "Removal was not confirmed. This phone remains enrolled.", onRemove)
            }) { Text(if (busy) "Removing…" else "Remove enrollment") }
        }
        message?.let { Text(it) }
        TextButton(onClick = { cancel() }) { Text("Cancel approval") }
    }
}
