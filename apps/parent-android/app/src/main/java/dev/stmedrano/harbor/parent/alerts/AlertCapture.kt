package dev.stmedrano.harbor.parent.alerts

/**
 * Remembers notification keys already handled so a repost or a group-summary duplicate does not raise a second
 * alert. Bounded (oldest keys fall out); holds keys only, never text. Not thread-safe on its own; the listener
 * calls it from a single handler.
 */
class NotificationDedup(private val capacity: Int = 256) {
    private val seen = object : LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > capacity
    }

    /** True the first time this key is seen; false on any later sighting. */
    fun firstSighting(key: String): Boolean {
        if (seen.containsKey(key)) { seen[key]; return false }
        seen[key] = true
        return true
    }

    fun forget(key: String) { seen.remove(key) }
}

/**
 * The pure heart of message-alert capture, kept out of Android classes so it can be fully unit tested. It is the
 * only place message text is handled, and it never returns, logs or stores that text: it classifies in memory and
 * emits alerts that carry a category, the app label, the rule-set version, a time and a saved-contact flag only.
 *
 * A package that is not on [MonitoredApps] is rejected before its text is examined. Group-summary notifications and
 * blank text produce nothing. Each matched category yields one alert with a fresh id.
 */
class AlertCaptureCore(
    private val classifier: MessageClassifier = MessageClassifier(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /**
     * @param packageName the source app's package; must be on the allowlist or the call returns empty untouched.
     * @param text the notification/message text, read into memory only for the duration of this call.
     * @param isGroupSummary true for the collapsed group header Android also posts; such posts are ignored.
     * @param fromSavedContact whether the sender is a saved contact (boolean only).
     * @return zero or more alerts, one per matched category, newest time. Never contains any input text.
     */
    fun capture(packageName: String, text: String?, isGroupSummary: Boolean, fromSavedContact: Boolean): List<MessageAlert> {
        val label = MonitoredApps.label(packageName) ?: return emptyList()
        if (isGroupSummary) return emptyList()
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return emptyList()
        val result = classifier.classify(body, fromSavedContact)
        if (result.categories.isEmpty()) return emptyList()
        val at = alertInstantString(now())
        return result.categories.map { category ->
            MessageAlert(
                id = newId(),
                appLabel = label.take(MAX_ALERT_LABEL),
                category = category,
                ruleSetVersion = result.ruleSetVersion,
                at = at,
                savedContact = fromSavedContact,
            )
        }
    }

    /** SMS goes through the same classifier but is always labelled as the phone's Messages app. */
    fun captureSms(text: String?, fromSavedContact: Boolean): List<MessageAlert> {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return emptyList()
        val result = classifier.classify(body, fromSavedContact)
        if (result.categories.isEmpty()) return emptyList()
        val at = alertInstantString(now())
        return result.categories.map { category ->
            MessageAlert(newId(), MonitoredApps.SMS_LABEL, category, result.ruleSetVersion, at, fromSavedContact)
        }
    }
}
