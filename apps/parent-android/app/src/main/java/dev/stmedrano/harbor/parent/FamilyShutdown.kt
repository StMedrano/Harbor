package dev.stmedrano.harbor.parent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs every shutdown step in order, even if an earlier step throws or the caller is cancelled.
 * The first failure is rethrown after all steps ran; later failures are attached as suppressed.
 */
suspend fun runShutdownSteps(vararg steps: suspend () -> Unit) {
    val failures = mutableListOf<Throwable>()
    withContext(NonCancellable) {
        for (step in steps) {
            try { step() } catch (t: Throwable) { failures += t }
        }
    }
    val first = failures.firstOrNull() ?: return
    failures.drop(1).forEach { first.addSuppressed(it) }
    throw first
}
