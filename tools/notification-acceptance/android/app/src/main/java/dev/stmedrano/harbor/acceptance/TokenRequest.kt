package dev.stmedrano.harbor.acceptance

internal fun requestFcmToken(
    fetch: (success: (String) -> Unit, failure: () -> Unit) -> Unit,
    save: (String) -> Unit,
    show: (String) -> Unit,
) {
    val failure = { show("FCM token unavailable. Check the matching Firebase setup and retry.") }
    show("Getting FCM token…")
    try {
        fetch({ token ->
            try {
                require(token.isNotBlank())
                save(token)
                show("FCM token obtained. Pair this test device, then register FCM.")
            } catch (_: Exception) { failure() }
        }, failure)
    } catch (_: Exception) { failure() }
}
