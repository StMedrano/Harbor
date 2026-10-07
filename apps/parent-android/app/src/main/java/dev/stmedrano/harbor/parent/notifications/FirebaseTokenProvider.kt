package dev.stmedrano.harbor.parent.notifications

import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface FirebaseTokenProvider {
    suspend fun token(): String
    suspend fun deleteToken()
}

class AndroidFirebaseTokenProvider(private val fetch: () -> Task<String>, private val delete: () -> Task<Void>,
    private val autoInit: (Boolean) -> Unit) : FirebaseTokenProvider {
    private val operations = Mutex()
    private var pendingDeletion: Task<Void>? = null
    override suspend fun token(): String = operations.withLock {
        pendingDeletion?.let { pending ->
            try { pending.awaitResult() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A settled failure permits a fresh request, not a cleanup claim. */ }
            if (pendingDeletion === pending) pendingDeletion = null
        }
        autoInit(true)
        fetch().awaitResult()
    }
    override suspend fun deleteToken() = operations.withLock {
        autoInit(false)
        val deletion = delete().also { pendingDeletion = it }
        deletion.awaitResult()
        if (pendingDeletion === deletion) pendingDeletion = null
        Unit
    }
    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener(Executor { it.run() }) { completed ->
            if (!continuation.isActive) return@addOnCompleteListener
            when {
                completed.isCanceled -> continuation.cancel(CancellationException("Firebase task cancelled"))
                completed.isSuccessful -> continuation.resume(completed.result)
                else -> continuation.resumeWithException(completed.exception ?: IllegalStateException("Firebase task failed"))
            }
        }
    }
    companion object {
        fun create(): AndroidFirebaseTokenProvider {
            val messaging = FirebaseMessaging.getInstance()
            return AndroidFirebaseTokenProvider({ messaging.token }, { messaging.deleteToken() }, { messaging.isAutoInitEnabled = it })
        }
    }
}
