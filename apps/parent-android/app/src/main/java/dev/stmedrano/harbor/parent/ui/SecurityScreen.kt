package dev.stmedrano.harbor.parent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.ParentRuntime
import dev.stmedrano.harbor.parent.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SecurityScreen(model: SecurityViewModel, onBack: () -> Unit, runtime: ParentRuntime? = null) {
    ProtectSensitiveScreen()
    val state by model.state.collectAsState()
    val closing = runtime?.state?.collectAsState()?.value?.signingOut == true
    val enabled = !state.busy && !closing
    var code by remember { mutableStateOf("") }
    var selectedFactor by remember { mutableStateOf<String?>(null) }
    val factor = state.enrollment?.factorId ?: selectedFactor ?: state.factors.firstOrNull()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    suspend fun request(action: suspend () -> Unit) = withContext(Dispatchers.IO) {
        if (runtime == null) action() else runtime.authAction(action)
    }
    LaunchedEffect(model) { request { model.loadFactors() } }
    DisposableEffect(model) { onDispose { model.clear() } }
    BackHandler { onBack() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Account security", style = MaterialTheme.typography.headlineSmall)
        TextButton(onBack, enabled = !closing) { Text("Back to family") }
        if (state.target != null) {
            Text("Revoke ${model.targetName() ?: "selected device"}?", style = MaterialTheme.typography.titleMedium)
            Text("This ends the child device's access. The backend must accept the request before Harbor confirms revocation.")
        }
        state.message?.let { Text(it) }
        if (state.phase == SecurityPhase.CONFIRMATION) Button({ scope.launch { request { model.confirmRetry() } } }, enabled = enabled) { Text("Confirm revocation") }
        if (state.phase == SecurityPhase.READY_TO_RETRY) Button({ scope.launch { request { model.confirmRetry() } } }, enabled = enabled) { Text("Retry revocation") }
        Text("Authenticator", style = MaterialTheme.typography.titleMedium)
        Text("Verification does not revoke a device. You must confirm the revocation separately.")
        OutlinedButton({ scope.launch { request { model.enrollTotp() } } }, enabled = enabled) { Text("Set up authenticator") }
        state.enrollment?.let { enrollment ->
            Text("Add an account in your authenticator using this setup key. Keep it private.")
            Text("Setup key: ${enrollment.secret}")
        }
        state.factors.forEachIndexed { index, id ->
            TextButton({ selectedFactor = id }, enabled = enabled && state.enrollment == null) { Text("Use authenticator ${index + 1}") }
        }
        OutlinedTextField(code, { code = it.filter { char -> char in '0'..'9' }.take(6) },
            label = { Text("Authenticator code") }, singleLine = true, enabled = enabled && factor != null,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
        Button({ val entered = code; val selected = factor; code = ""; if (selected != null) scope.launch { request { model.challenge(selected, entered) } } },
            enabled = enabled && factor != null && code.length == 6) { Text("Verify authenticator") }
        if (state.busy) CircularProgressIndicator()
    }
}
