package dev.stmedrano.harbor.parent.notifications

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.profile.ProfileLease
import kotlinx.coroutines.*

// Android retains execution until dequeueWork is empty or onStopJob cancels it.
// Only scoped references are queued. A token job obtains the current provider
// token after encrypted Auth restore; neither Firebase nor Auth tokens are queued.
class ParentNotificationJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val execution = scope.launch(start = CoroutineStart.LAZY) {
            var drained = false
            try {
                while (isActive) {
                    val work = params.dequeueWork() ?: run { drained = true; break }
                    try {
                        val owner = ParentIdentity(checkNotNull(work.intent.getStringExtra("userId")), checkNotNull(work.intent.getStringExtra("sessionId")))
                        val data = if (work.intent.action == TOKEN) null else mapOf(
                            "route" to checkNotNull(work.intent.getStringExtra("route")),
                            "parentRegistrationId" to checkNotNull(work.intent.getStringExtra("parentRegistrationId")))
                        withTimeout(60000) { (application as ParentApplication).processBackground(owner, data) }
                    } catch (_: TimeoutCancellationException) { /* Bounded operation stays unconfirmed. */ }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Foreground refresh and explicit registration retry remain available. */ }
                    params.completeWork(work)
                }
            } finally {
                if (running[params.jobId] == currentCoroutineContext()[Job]) {
                    running.remove(params.jobId)
                    if (!drained) jobFinished(params, false)
                }
            }
            // dequeueWork()==null already tells Android the enqueued job is done.
        }
        running[params.jobId] = execution
        execution.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        const val JOB_ID = 4102
        private const val TOKEN = "harbor.parent.TOKEN_SYNC"
        internal fun info(context: Context) = JobInfo.Builder(JOB_ID, ComponentName(context, ParentNotificationJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        internal fun work(owner: ParentIdentity, data: Map<String, String>?): JobWorkItem {
            require(ParentMessageParser.uuid(owner.userId) && ParentMessageParser.uuid(owner.sessionId))
            require(data == null || ParentMessageParser.parse(data) != null)
            val intent = Intent(if (data == null) TOKEN else "harbor.parent.MESSAGE")
                .putExtra("userId", owner.userId).putExtra("sessionId", owner.sessionId)
            data?.forEach { (key, value) -> intent.putExtra(key, value) }
            return JobWorkItem(intent)
        }
        internal fun work(lease: ProfileLease, owner: ParentIdentity?, data: Map<String, String>?): JobWorkItem =
            error("Family work shape awaiting native behavior proof")
        fun enqueue(context: Context, owner: ParentIdentity, data: Map<String, String>?): Boolean =
            context.getSystemService(JobScheduler::class.java).enqueue(info(context), work(owner, data)) == JobScheduler.RESULT_SUCCESS
    }
}
