package dev.stmedrano.harbor.parent.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stmedrano.harbor.parent.child.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun ChildPairingScreen(repository: ChildRepository, onConfirmed: () -> Unit) {
    ProtectSensitiveScreen()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val state = repository.state.collectAsState().value
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    BackHandler {
        code = ""
        operation?.cancel()
        activity?.finish()
    }
    if (state is ChildSyncState.Blocked) {
        HarborNote("This phone needs a parent to check its setup.", HarborTone.WARN, emphasis = true)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Ask your parent for a fresh six-digit pairing code.")
        OutlinedTextField(code, onValueChange = { value ->
            if (!busy && value.length <= 6 && value.all { it in '0'..'9' }) code = value
        }, label = { Text("Pairing code") }, singleLine = true, enabled = !busy,
            textStyle = HarborCodeStyle.copy(fontSize = 22.sp, lineHeight = 28.sp), shape = MaterialTheme.shapes.medium, colors = harborFieldColors(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        HarborButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && code.matches(Regex("[0-9]{6}")), onClick = {
            val submitted = code
            busy = true
            message = null
            operation = scope.launch {
                try {
                    when (withContext(Dispatchers.IO) { repository.pair(submitted) }) {
                        is PairResult.Confirmed -> { code = ""; onConfirmed() }
                        PairResult.Rejected -> message = "Pairing was not confirmed. Ask your parent to check the code."
                        PairResult.UnknownOutcome -> message = "This phone needs a parent to check its setup."
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                finally { code = ""; busy = false }
            }
        }) { Text(if (busy) "Connecting…" else "Connect phone") }
        message?.let { HarborNote(it, HarborTone.DANGER, emphasis = true) }
    }
}
