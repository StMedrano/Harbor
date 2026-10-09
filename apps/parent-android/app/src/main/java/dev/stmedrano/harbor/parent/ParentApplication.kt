package dev.stmedrano.harbor.parent

import android.app.Application
import io.github.jan.supabase.exceptions.RestException
import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import dev.stmedrano.harbor.parent.auth.SupabaseAuthGateway
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.data.ParentDatabase
import dev.stmedrano.harbor.parent.family.*
import dev.stmedrano.harbor.parent.notifications.*
import dev.stmedrano.harbor.parent.security.*
import dev.stmedrano.harbor.parent.profile.*
import dev.stmedrano.harbor.parent.child.*
import dev.stmedrano.harbor.parent.usage.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import android.app.job.JobScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ParentTapState(val device: DevicePublicV1? = null, val message: String? = null)

class ParentApplication : Application() {
    val accountScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var parentConstructionAllowed = false
    private var parentCollectors: Job? = null
    private var runtimeLease: ProfileLease? = null
    private var profileScope: CoroutineScope? = null
    private val approvalLock = Mutex()
    private val mutableApproval = MutableStateFlow<TemporaryParentSession?>(null)
    val approvalSession = mutableApproval.asStateFlow()
    private val mutableApprovalClosing = MutableStateFlow(false)
    val approvalClosing = mutableApprovalClosing.asStateFlow()
    suspend fun beginChildApproval(lease: ProfileLease): Boolean = approvalLock.withLock {
        if (BuildConfig.CI_FIXTURE || !profiles.isCurrent(lease) || lease.role != ProfileRole.CHILD || mutableApproval.value != null) return@withLock false
        val binding = childRepository.binding.value ?: return@withLock false
        mutableApproval.value = TemporaryParentSession.open(this, lease, binding, profiles::isCurrent)
        true
    }
    suspend fun cancelChildApproval() = withContext(NonCancellable) {
        approvalLock.withLock {
            val session = mutableApproval.value ?: return@withLock
            mutableApprovalClosing.value = true
            try { session.clear() }
            catch (_: Exception) { /* Only confirmed local erasure allows leaving approval. */ }
            finally {
                if (session.locallyCleared) mutableApproval.value = null
                mutableApprovalClosing.value = false
            }
        }
    }
    suspend fun removeChildEnrollment(session: TemporaryParentSession): Boolean = approvalLock.withLock {
        if (mutableApproval.value !== session || !profiles.isCurrent(session.lease)) return@withLock false
        val transition = RoleTransition(profiles::currentLease, { childRepository.binding.value }, profiles::transitionToSetup,
            { false }, { expected ->
                childRepository.confirmRevocation(expected)
                childNotificationStore.setOpted(expected, false)
                usageStore.eraseBinding(expected.deviceId)
                childRepository.clearAfterConfirmedRevocation()
                childRepository.restore()
            })
        val confirmed = transition.childToSetup(session.approval)
        if (session.locallyCleared) mutableApproval.value = null
        confirmed
    }
    suspend fun parentToSetup(expected: ProfileLease): Boolean {
        if (BuildConfig.CI_FIXTURE || !profiles.isCurrent(expected) || expected.role != ProfileRole.PARENT) return false
        val active = runtime ?: return false
        val auth = authRepository ?: return false
        return RoleTransition({ profiles.currentLease()?.takeIf { it == expected } }, { null }, profiles::transitionToSetup, {
            active.signOutCurrent()
            active.state.value.cleanupConfirmed && auth.identity.value == null &&
                getSharedPreferences("harbor-secure-auth", MODE_PRIVATE).all.isEmpty()
        }, {}).parentToSetup()
    }
    private val evidence by lazy { ProfileEvidence(
        { getSharedPreferences("harbor-secure-auth", MODE_PRIVATE).all.isNotEmpty() },
        { EncryptedChildStore.hasRecords(this) }) }
    private val childKey by lazy { ChildDeviceKey() }
    private val childRepository by lazy {
        ChildRepository(ChildApi.create(childKey), EncryptedChildStore(this), childKey, { System.currentTimeMillis() / 1000 })
    }
    val profiles: ProfileCoordinator by lazy {
        val store = AndroidProfileStore(this)
        ProfileCoordinator(store, {
            evidence.assertSingleProfile()
            val childRecords = EncryptedChildStore.hasRecords(this)
            if (BuildConfig.CI_FIXTURE || store.read() == ProfileRole.CHILD || childRecords) null
            else {
                try { parentAuth?.restore(); parentAuth?.identity?.value?.userId }
                catch (failure: RestException) {
                    if (failure.statusCode >= 500 || failure.statusCode == 429) throw ProfileValidationFailure(ProfileBlock.NETWORK_UNAVAILABLE)
                    throw failure
                }
            }
        }, {
            evidence.assertSingleProfile()
            if (!EncryptedChildStore.hasRecords(this)) null
            else {
                childRepository.restore()
                (childRepository.state.value as? ChildSyncState.Blocked)?.let {
                    throw ProfileValidationFailure(if (it.reason == ChildFailure.NETWORK_UNAVAILABLE) ProfileBlock.NETWORK_UNAVAILABLE else ProfileBlock.INVALID_CREDENTIALS)
                }
                childRepository.binding.value?.deviceId
            }
        }, ::stopFamilyRuntime, { lease ->
            runtimeLease = lease
            profileScope = CoroutineScope(SupervisorJob(accountScope.coroutineContext[Job]) + Dispatchers.IO)
            if (lease.role == ProfileRole.PARENT) startParentRuntime()
            else {
                check(childRepository.binding.value?.deviceId == lease.ownerId)
                if (!BuildConfig.CI_FIXTURE) startChildUsage(lease)
            }
            accountScope.launch {
                profiles.state.first { it != ProfileState.Transitioning }
                if (profiles.isCurrent(lease)) foreground()
            }
        })
    }
    private val usageStore by lazy { EncryptedUsageStore.open(this) }
    private val usagePermission by lazy { UsagePermission(this) }
    private val usageTransport by lazy { UsageHttpTransport.create() }
    private val usageApi by lazy { ChildApi(BuildConfig.SUPABASE_URL, BuildConfig.PUBLISHABLE_KEY, usageTransport::execute,
        { System.currentTimeMillis()/1000 }, { java.util.UUID.randomUUID().toString() }, childKey) }
    private var childUsage: UsageRuntime? = null
    val childUsageRuntime: UsageRuntime?
        get() = profiles.currentLease()?.takeIf(::currentUsageLease)?.let { childUsage }
    private fun currentUsageLease(lease: ProfileLease): Boolean = !BuildConfig.CI_FIXTURE && lease.role==ProfileRole.CHILD &&
        runtimeLease==lease && profiles.isCurrent(lease) && childRepository.binding.value?.deviceId==lease.ownerId &&
        childRepository.state.value !is ChildSyncState.Blocked
    private suspend fun startChildUsage(lease: ProfileLease) {
        val scope=checkNotNull(profileScope)
        val collector=UsageCollector(AndroidUsageSource(this),AndroidAppInventory(this),::currentUsageLease,System::currentTimeMillis,
            { java.time.ZoneId.systemDefault().id },{ resolvedHomePackages(this) })
        val reporter=UsageReporter(::currentUsageLease) { captured,operation,body ->
            check(currentUsageLease(captured))
            val binding=checkNotNull(childRepository.binding.value)
            childRepository.withCurrentSession(binding) { session ->
                currentCoroutineContext().ensureActive();check(currentUsageLease(captured))
                if(operation=="report-device-usage" && Json.parseToJsonElement(body).jsonObject["usagePermission"]?.jsonPrimitive?.content=="granted" && !usagePermission.isGranted())
                    throw UsagePermissionChanged()
                val response=usageApi.signedUsage(operation,body,binding,session)
                currentCoroutineContext().ensureActive();check(currentUsageLease(captured))
                response
            }
        }
        childUsage=UsageRuntime(scope,usageStore,reporter,::currentUsageLease,usagePermission::isGranted,
            { captured,saved -> withContext(Dispatchers.IO){collector.collect(captured,saved)} },
            { unavailableUsageReport(System.currentTimeMillis(),java.time.ZoneId.systemDefault().id,UsagePermissionState.DENIED) })
        childUsage?.start(lease)
        scope.launch {
            childRepository.state.collectLatest { value ->
                if(value is ChildSyncState.Blocked){UsageJobService.cancel(this@ParentApplication);childUsage?.stop()}
            }
        }
    }
    private fun scheduleUsageIfOpted(lease: ProfileLease) {
        // Stay scheduled until an opt-out deletion is confirmed, so a clear made offline is retried in the background.
        val needed=try {currentUsageLease(lease)&&usageStore.read(lease.ownerId)?.needsBackgroundWork==true}catch(_:UsageCheckpointLost){false}
        if(needed)UsageJobService.schedule(this,lease)else UsageJobService.cancel(this)
    }
    @Volatile private var inventoryCache: Pair<Long, InventoryResult>? = null
    private fun launchableInventory(): InventoryResult {
        val now=System.currentTimeMillis()
        inventoryCache?.takeIf {now-it.first<60_000}?.let {return it.second}
        return AndroidAppInventory(this).read().also {inventoryCache=now to it}
    }
    suspend fun localChildUsage(lease: ProfileLease): LocalChildUsage? = withContext(Dispatchers.IO) {
        if(!currentUsageLease(lease))return@withContext null
        // A lost checkpoint is shown as "restoring"; recovery runs in the foreground refresh. It must never crash the UI.
        val read=usageStore.readForView(lease.ownerId)
        val snapshot=LocalChildUsage(read,if(read.state?.consent==true)launchableInventory() else null)
        snapshot.takeIf {currentUsageLease(lease)}
    }
    fun isUsageAccessGranted(): Boolean = usagePermission.isGranted()
    fun usageSettingsIntent(): android.content.Intent = usagePermission.settingsIntent()
    fun childUsageView(local: LocalChildUsage?, runtime: UsageRuntimeStatus): UsageViewState =
        UsageViewState.fromLocal(local?.read?.state, runtime, usagePermission.isGranted(), System.currentTimeMillis(),
            recovering = local?.read?.recovering == true, inventory = local?.inventory) { name ->
            // Labels are resolved on this phone only and are never uploaded from here.
            runCatching { packageManager.getApplicationInfo(name, 0).loadLabel(packageManager).toString() }.getOrNull()
        }
    fun setChildUsageSharing(lease: ProfileLease,enabled: Boolean) { accountScope.launch {
        if(!currentUsageLease(lease))return@launch
        profileWork(lease) {childUsage?.setSharing(lease,enabled);scheduleUsageIfOpted(lease);true}
    } }
    fun refreshChildUsage(lease: ProfileLease) { accountScope.launch {
        if(!currentUsageLease(lease))return@launch
        profileWork(lease) {childUsage?.refresh(lease);scheduleUsageIfOpted(lease);true}
    } }
    suspend fun processUsageBackground(reference: ProfileLease): Boolean {
        if(BuildConfig.CI_FIXTURE||reference.role!=ProfileRole.CHILD)return false
        profiles.state.first {it!=ProfileState.Transitioning}
        if(profiles.state.value is ProfileState.Blocked)profiles.retryValidation()
        // Periodic references trigger current encrypted Auth validation; they are never role or session authority.
        val captured=profiles.currentLease()?.takeIf {it.role==ProfileRole.CHILD&&it.ownerId==reference.ownerId}?:return false
        return profileWork(captured) {
            val saved=try {usageStore.read(captured.ownerId)}catch(_:UsageCheckpointLost){null}
            if(!currentUsageLease(captured)||saved?.needsBackgroundWork!=true)return@profileWork false
            childUsage?.refresh(captured)
            if(currentUsageLease(captured)) {
                // Nothing left to send once an opt-out deletion is confirmed: stop waking the phone.
                val after=try {usageStore.read(captured.ownerId)}catch(_:UsageCheckpointLost){null}
                if(after?.needsBackgroundWork!=true)UsageJobService.cancel(this)
            }
            currentUsageLease(captured)
        }
    }
    private val mutableTap = MutableStateFlow(ParentTapState())
    val tapState = mutableTap.asStateFlow()
    private val secureStore by lazy { SecureAuthStore.open(this) }
    private val client by lazy {
        if (BuildConfig.CI_FIXTURE) null else SupabaseAuthGateway.createClient(BuildConfig.SUPABASE_URL, BuildConfig.PUBLISHABLE_KEY, secureStore)
    }
    // One SDK/repository per process survives Activity recreation. The CI APK
    // never constructs a live client, in addition to having no INTERNET permission.
    private val parentAuth: ParentAuthRepository? by lazy {
        client?.let { ParentAuthRepository(SupabaseAuthGateway(it, secureStore), secureStore) }
    }
    val authRepository: ParentAuthRepository?
        get() = if (parentConstructionAllowed) parentAuth else null
    fun openParentSetup(): Boolean {
        if (profiles.state.value != ProfileState.Setup || EncryptedChildStore.hasRecords(this)) return false
        parentConstructionAllowed = true
        return true
    }
    fun retrySetupValidation() { accountScope.launch { profiles.retryValidation() } }
    fun hasChildSetup() = EncryptedChildStore.hasRecords(this)
    fun childForSetup(): ChildRepository? = if (!parentConstructionAllowed &&
        getSharedPreferences("harbor-secure-auth", MODE_PRIVATE).all.isEmpty() &&
        (profiles.state.value == ProfileState.Setup || profiles.state.value is ProfileState.Child)) childRepository else null
    private val parentApi by lazy { authRepository?.let { SdkParentApi(checkNotNull(client), it) } }
    private val parentUsageModel by lazy { parentAuth?.let { ParentUsageModel(SdkParentUsageApi(checkNotNull(client), it), ::currentIdentity) } }
    val parentUsage: ParentUsageModel? get() = if (parentConstructionAllowed) parentUsageModel else null
    val familyViewModel: FamilyViewModel? by lazy {
        authRepository?.let { auth ->
            val api = checkNotNull(parentApi)
            val cache = ParentDatabase.open(this).familyCache()
            val current = { currentIdentity() }
            FamilyViewModel(api, FamilyRepository(api, cache, current), PendingChildCreation(api, cache, current),
                PairingModel(api, current), secureStore, current)
        }
    }
    val securityViewModel: SecurityViewModel? by lazy {
        authRepository?.let { auth -> SecurityViewModel(SdkMfaGateway(checkNotNull(client), auth),
            { currentIdentity() }, { checkNotNull(familyViewModel).repository.state.value },
            { family, device -> checkNotNull(parentApi).revokeDevice(family, device) },
            { currentIdentity()?.let { checkNotNull(familyViewModel).refresh(it) } }) }
    }
    private val registrationStore by lazy { ParentRegistrationStore.open(this) }
    private val tokenProvider by lazy { AndroidFirebaseTokenProvider.create() }
    private val renderer by lazy { ParentNotificationRenderer(this) }
    private val childRenderer by lazy { ChildNotificationRenderer(this) }
    private val childNotificationStore by lazy { ChildNotificationStore.open(this) }
    private val childNotifications by lazy { ChildNotifications(::notificationLease, { childRepository.binding.value },
        childNotificationStore::optedBinding, { childRepository.sync(it) }, childRenderer::show, childRenderer::clear,
        System::currentTimeMillis) }
    val profileNotifications: ProfileNotificationRouter by lazy { ProfileNotificationRouter(::notificationLease,
        { if (profiles.currentLease()?.role == ProfileRole.CHILD) childRepository.binding.value else null },
        { lease -> if (lease.role == ProfileRole.PARENT) registrationStore.optedOwner() == currentIdentity() && currentIdentity() != null
            else childNotificationStore.optedBinding() == childRepository.binding.value && childRepository.binding.value != null },
        { when (profiles.currentLease()?.role) { ProfileRole.PARENT -> renderer.allowed(); ProfileRole.CHILD -> childRenderer.allowed(); else -> false } },
        { lease, data -> ParentNotificationJob.enqueue(this, lease, if (lease.role == ProfileRole.PARENT) currentIdentity() else null, data) },
        { lease ->
            val expected = currentIdentity()
            var confirmed = false
            if (expected?.userId == lease.ownerId) accountWork {
                if (profiles.isCurrent(lease) && currentIdentity() == expected) {
                    notifications?.enable(expected)
                    confirmed = currentIdentity() == expected && notifications?.state?.value?.confirmed == true
                }
            }
            confirmed
        }, { tokenProvider.token() },
        { lease, token -> val expected = childRepository.binding.value
            expected != null && profiles.isCurrent(lease) && expected.deviceId == lease.ownerId && childRepository.registerFcm(expected, token) },
        { lease -> if (lease.role == ProfileRole.PARENT) { notifications?.invalidate(); renderer.clear() }
            else childNotifications.invalidate() }) }
    private fun notificationLease(): ProfileLease? = profiles.currentLease()?.takeIf { lease ->
        if (lease.role == ProfileRole.PARENT) currentIdentity()?.userId == lease.ownerId
        else childRepository.state.value !is ChildSyncState.Blocked && childRepository.binding.value?.deviceId == lease.ownerId
    }
    private val fcmApi by lazy { SdkParentFcmApi(checkNotNull(client), checkNotNull(authRepository)) }
    val notifications: ParentNotifications? by lazy {
        authRepository?.let { auth -> ParentNotifications(fcmApi, object : FirebaseTokenProvider {
            override suspend fun token() = tokenProvider.token()
            override suspend fun deleteToken() = tokenProvider.deleteToken()
        }, registrationStore, { currentIdentity() }, { currentFamily() }, renderer::allowed,
            ::refreshHint, renderer::show, { auth.withAccessToken { it } }) }
    }
    private val realtime: FamilyRealtime? by lazy {
        authRepository?.let { auth -> FamilyRealtime(SdkFamilyChannelFactory(checkNotNull(client), auth, accountScope),
            accountScope, { currentIdentity() }, { currentFamily() }, {
                currentIdentity()?.let { familyViewModel?.refresh(it) }
            }) }
    }
    val runtime: ParentRuntime? by lazy {
        authRepository?.let { auth ->
            // Capture the existing API while construction is permitted, before the role is fenced.
            val registrationApi = fcmApi
            ParentRuntime({ auth.identity.value },
            { owner -> auth.withAccessToken { check(auth.identity.value == owner); it } },
            { notifications?.disableLocally(); renderer.clear() }, { realtime?.disconnect() },
            { parentUsageModel?.clear(); familyViewModel?.hideVisible(); securityViewModel?.clear(); mutableTap.value = ParentTapState() },
            { _, token -> registrationApi.remove(registrationStore.installationId(), token) },
            { tokenProvider.deleteToken() }, auth::signOutCurrent,
            { owner ->
                if (auth.identity.value == null || auth.identity.value == owner) {
                    try { parentUsageModel?.clear(); familyViewModel?.clear() }
                    finally {
                        try { auth.clearLocal() }
                        finally { registrationStore.clearMarker(); registrationStore.clearOptIn(); contextBinding.reset() }
                    }
                }
            }) }
    }
    private fun currentIdentity(): ParentIdentity? = authRepository?.identity?.value?.takeUnless { runtime?.state?.value?.signingOut == true }
    private fun currentFamily(): String? {
        val identity = currentIdentity() ?: return null
        return familyViewModel?.repository?.state?.value?.snapshot?.takeIf { it.membership.userId == identity.userId }?.family?.id
    }
    override fun onCreate() {
        super.onCreate()
        accountScope.launch {
            profiles.restore()
        }
    }
    private suspend fun stopFamilyRuntime() {
        val old = runtimeLease
        val scope = profileScope
        val usage = childUsage
        runtimeLease = null; profileScope = null; childUsage = null
        // Usage first, then the existing notification/profile/parent cleanup. Every step runs.
        runShutdownSteps(
            { UsageJobService.cancel(this) },
            { usage?.stop() },
            { getSystemService(JobScheduler::class.java).cancel(ParentNotificationJob.JOB_ID) },
            { if (old != null) profileNotifications.stop(old) },
            { scope?.coroutineContext?.get(Job)?.cancelAndJoin() },
            { stopParentRuntime() },
        )
    }
    private suspend fun profileWork(lease: ProfileLease, action: suspend () -> Boolean): Boolean {
        val scope = profileScope?.takeIf { runtimeLease == lease } ?: return false
        val work = scope.async { if (profiles.isCurrent(lease)) action() else false }
        return try { work.await() } finally { if (work.isActive) work.cancel() }
    }
    private suspend fun stopParentRuntime() {
        parentCollectors?.cancelAndJoin()
        parentCollectors = null
        if (parentConstructionAllowed) {
            realtime?.disconnect()
            parentUsageModel?.clear()
            familyViewModel?.hideVisible()
            securityViewModel?.clear()
            mutableTap.value = ParentTapState()
        }
        parentConstructionAllowed = false
    }
    private fun startParentRuntime() {
        parentConstructionAllowed = true
        if (BuildConfig.CI_FIXTURE || parentCollectors != null) return
        val auth = checkNotNull(authRepository)
        parentCollectors = accountScope.launch { coroutineScope {
        launch {
            auth.identity.collectLatest { identity ->
                if (identity != null) accountWork { ensureContext() }
                else { realtime?.disconnect(); familyViewModel?.hideVisible(); securityViewModel?.clear(); mutableTap.value = ParentTapState() }
            }
        }
        launch {
            combine(auth.identity, checkNotNull(familyViewModel).repository.state) { identity, family ->
                identity to family.snapshot?.takeIf { family.failure != FamilyFailure.ACCESS_DENIED && it.membership.userId == identity?.userId }?.family?.id
            }.distinctUntilChanged().collectLatest { (identity, family) ->
                securityViewModel?.clear()
                realtime?.disconnect()
                if (identity != null && family != null && currentIdentity() == identity) {
                    try { realtime?.connect(identity, family) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Foreground and SDK reconnect retry current authorized reads. */ }
                }
            }
        }
        } }
    }
    private val contextBinding by lazy { ParentContextBinding(::currentIdentity, checkNotNull(familyViewModel), registrationStore,
        { realtime?.disconnect(); notifications?.invalidate(); renderer.clear(); securityViewModel?.clear(); mutableTap.value = ParentTapState() },
        { notifications?.enable() }) }
    private suspend fun ensureContext() = contextBinding.ensure()
    private suspend fun refreshHint(hint: ParentHint): Boolean {
        val identity = currentIdentity() ?: return false
        val model = familyViewModel ?: return false
        model.refresh(identity)
        val state = model.repository.state.value
        return currentIdentity() == identity && hint.matchesFamily(identity, state)
    }
    suspend fun accountWork(action: suspend () -> Unit) {
        // An inactive profile must not evaluate and cache a null parent runtime.
        if (profiles.state.value !is ProfileState.Parent) return
        try { runtime?.authAction(action) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Controllers retain honest failed/unconfirmed states. */ }
    }
    fun foreground() { accountScope.launch {
        profiles.state.first { it != ProfileState.Transitioning }
        val lease = profiles.currentLease() ?: return@launch
        profileWork(lease) {
            if (lease.role == ProfileRole.PARENT) accountWork {
                if (currentIdentity() != null) {
                    authRepository?.withAccessToken { }; ensureContext()
                    currentIdentity()?.let { familyViewModel?.refresh(it) }
                }
            } else {
                childRepository.binding.value?.let { childRepository.sync(it) }
                if(currentUsageLease(lease)){childUsage?.refresh(lease);scheduleUsageIfOpted(lease)}
            }
            if (!BuildConfig.CI_FIXTURE) profileNotifications.syncToken(lease)
            true
        }
    } }
    fun queueTokenRefresh() { accountScope.launch {
        profiles.state.first { it != ProfileState.Transitioning }
        val lease = notificationLease() ?: return@launch
        val owner = if (lease.role == ProfileRole.PARENT) registrationStore.optedOwner()?.takeIf { it == currentIdentity() } ?: return@launch else null
        if (lease.role == ProfileRole.PARENT || childNotificationStore.optedBinding() == childRepository.binding.value)
            ParentNotificationJob.enqueue(this@ParentApplication, lease, owner, null)
    } }
    fun queueMessage(data: Map<String, String>) { accountScope.launch {
        profiles.state.first { it != ProfileState.Transitioning }
        notificationLease()?.let { profileNotifications.accept(data, it) }
    } }
    suspend fun processFamilyBackground(lease: ProfileLease, owner: ParentIdentity?, data: Map<String, String>?): Boolean {
        if (BuildConfig.CI_FIXTURE) return false
        profiles.state.first { it != ProfileState.Transitioning }
        return profileWork(lease) {
            if (notificationLease() != lease) return@profileWork false
            if (lease.role == ProfileRole.PARENT) {
                if (owner == null || owner != currentIdentity() || owner != registrationStore.optedOwner()) return@profileWork false
                if (data == null) profileNotifications.syncToken(lease) else processBackground(owner, data)
                true
            } else {
                if (owner != null) return@profileWork false
                if (data == null) { profileNotifications.syncToken(lease); true }
                else childNotifications.onMessage(data, lease)
            }
        }
    }
    fun enableChildNotifications(lease: ProfileLease) { accountScope.launch {
        if (BuildConfig.CI_FIXTURE || notificationLease() != lease || lease.role != ProfileRole.CHILD) return@launch
        profileWork(lease) {
            val expected = childRepository.binding.value ?: return@profileWork false
            childNotificationStore.setOpted(expected, true)
            profileNotifications.syncToken(lease); true
        }
    } }
    fun syncChild(lease: ProfileLease, expected: ChildBinding) { accountScope.launch {
        profileWork(lease) { if (lease.role != ProfileRole.CHILD) false else { childRepository.sync(expected); true } }
    } }
    fun openChildNotification(route: ParentRoute) { accountScope.launch {
        profiles.state.first { it != ProfileState.Transitioning }
        val lease = notificationLease()?.takeIf { it.role == ProfileRole.CHILD } ?: return@launch
        profileWork(lease) { childNotifications.onTap(route, lease) }
    } }
    suspend fun processBackground(owner: ParentIdentity, data: Map<String, String>?) {
        if (BuildConfig.CI_FIXTURE) return
        profiles.state.first { it != ProfileState.Transitioning }
        if (profiles.state.value !is ProfileState.Parent) return
        runtime?.authAction {
            ParentBackgroundProcessor(::currentIdentity, registrationStore::optedOwner,
                { authRepository?.restore() }, ::ensureContext,
                { expected -> notifications?.enable(expected) }, { payload -> notifications?.onMessage(payload) == true }).process(owner, data)
        }
    }
    fun openNotification(hint: ParentHint) { accountScope.launch {
        profiles.state.first { it != ProfileState.Transitioning }
        val lease = notificationLease()?.takeIf { it.role == ProfileRole.PARENT } ?: return@launch
        profileWork(lease) { accountWork {
        if (currentIdentity() == null) authRepository?.restore()
        ensureContext()
        val accepted = notifications?.onTap(hint) == true
        val device = if (accepted) familyViewModel?.repository?.state?.value?.snapshot?.devices?.firstOrNull { it.id == hint.route.deviceId } else null
        if (profiles.isCurrent(lease)) mutableTap.value = ParentTapState(device, if (accepted) null else "This update is unavailable. Refresh your family or sign in again.")
        }; true }
    } }
    fun closeNotification() { mutableTap.value = ParentTapState() }
}



