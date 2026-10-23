package dev.stmedrano.harbor.parent.controls

import java.time.ZonedDateTime

/**
 * While Harbor's app controls run on a child's phone, removing an app needs a parent's OK, so Harbor cannot be removed
 * without one. Android asks "Do you want to uninstall this app?" on a screen of the phone's app installer. For the
 * installer only, the controls service reads which of its screens came to the front (the screen's class name, never
 * what it shows). It therefore cannot tell which app is being removed and asks a parent for any of them. Installing
 * apps is not affected. A parent's yes (Allow 15 min) lets the child remove apps for 15 minutes.
 */
object UninstallGuard {
    /** Installers known to show the confirmation; the phone's own handler is added at run time ([RemovalScreens]). */
    val INSTALLERS = setOf("com.google.android.packageinstaller", "com.android.packageinstaller")

    /** What the block screen, the parent's request and the timeline call it. */
    const val LABEL = "Remove an app"

    /** The words on the block screen. */
    val message = "Ask a parent to remove apps" to
        "While Harbor is on, removing an app needs a parent's OK. Your parent gets a notification and can say yes."

    /** Covered unless a parent said yes. Without a policy this phone is not a paired child, and nothing is covered. */
    fun covers(installer: String, now: ZonedDateTime, policy: ControlsV1?): Boolean =
        policy != null && PolicyEngine.grantRemainingMs(installer, now, policy) == null

    /** A request, grant or timeline entry that is about removing apps, as the parent sees it. */
    fun isRemoval(packageName: String?, appLabel: String?): Boolean = appLabel == LABEL || packageName in INSTALLERS
}

/** The phone's screens for removing an app: the installers that handle "remove this package", and those screens' classes. */
data class RemovalScreens(val installers: Set<String> = UninstallGuard.INSTALLERS, val screens: Set<String> = emptySet()) {
    /** [screen] gives the class name of the window that came to the front; it is only asked for an installer's window. */
    fun isRemoval(packageName: String, screen: () -> String?): Boolean {
        if (packageName !in installers) return false
        val name = screen() ?: return false
        return name in screens || name.contains("uninstall", ignoreCase = true)
    }
}
