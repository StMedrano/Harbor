package dev.stmedrano.harbor.parent.alerts

/**
 * The bounded, visible allowlist of messaging and social apps Harbor may inspect. Everything else is ignored
 * on-device before any notification text is read. Matching is by exact package name; the label is what the
 * child and parent see for that app. The list is fixed in the app and shown to the child in the "What Harbor
 * watches" panel; it changes only through an app release.
 */
object MonitoredApps {
    /** Package name -> app label. Several apps ship under more than one package (store variants); each maps to its label. */
    val DEFAULT: Map<String, String> = linkedMapOf(
        // The phone's Messages app (SMS/RCS). SMS itself is also captured directly by the receiver.
        "com.google.android.apps.messaging" to "Messages",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp",
        "com.facebook.katana" to "Facebook",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.snapchat.android" to "Snapchat",
        "com.discord" to "Discord",
        "org.telegram.messenger" to "Telegram",
        "com.twitter.android" to "X",
    )

    /** The label applied to alerts raised from the phone's own SMS, independent of which Messages app is installed. */
    const val SMS_LABEL = "Messages"

    fun isMonitored(packageName: String): Boolean = DEFAULT.containsKey(packageName)

    /** The label for a monitored package, or null when the package is not on the allowlist (and must be ignored). */
    fun label(packageName: String): String? = DEFAULT[packageName]

    /** Distinct app labels, in listing order, for the child-facing panel. */
    fun labels(): List<String> = DEFAULT.values.distinct()
}
