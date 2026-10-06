package dev.stmedrano.harbor.acceptance

sealed interface RegistrationResult {
    data object Registered : RegistrationResult
    data object NeedsBinding : RegistrationResult
    data object RetryableFailure : RegistrationResult
}
class Registration(private val binding: () -> DeviceBinding?, private val submit: (DeviceBinding, String) -> Unit) {
    private var currentToken: String? = null
    var confirmedToken: String? = null
        private set
    @Synchronized fun onToken(token: String) {
        require(token.isNotBlank())
        if (token != currentToken) confirmedToken = null
        currentToken = token
    }
    @Synchronized fun registerCurrentToken(): RegistrationResult {
        val device = binding() ?: return RegistrationResult.NeedsBinding
        val token = currentToken ?: return RegistrationResult.RetryableFailure
        return try {
            submit(device, token)
            confirmedToken = token
            RegistrationResult.Registered
        } catch (_: Exception) {
            confirmedToken = null
            RegistrationResult.RetryableFailure
        }
    }
}
