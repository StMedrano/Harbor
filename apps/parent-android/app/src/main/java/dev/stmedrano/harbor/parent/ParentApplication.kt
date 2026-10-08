package dev.stmedrano.harbor.parent

import android.app.Application
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ParentTapState(val device: DevicePublicV1? = null, val message: String? = null)

class ParentApplication : Application() {
    val accountScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var parentConstructionAllowed = false
    private var parentCollectors: Job? = null
    private val evidence by lazy { ProfileEvidence(
        { getSharedPreferences("harbor-secure-auth", MODE_PRIVATE).all.isNotEmpty() },
        { EncryptedChildStore.hasRecords(this) }) }
    private val childKey by lazy { ChildDeviceKey() }
    private val childRepository by lazy {
        ChildRepository(ChildApi.create(childKey), EncryptedChildStore(this), childKey, { System.currentTimeMillis() / 1000 })
    }
    val profiles by lazy {
        val store = AndroidProfileStore(this)
        ProfileCoordinator(store, {
            evidence.assertSingleProfile()
            val childRecords = EncryptedChildStore.hasRecords(this)
            if (BuildConfig.CI_FIXTURE || store.read() == ProfileRole.CHILD || childRecords) null
            else { parentAuth?.restore(); parentAuth?.identity?.value?.userId }
        }, {
            evidence.assertSingleProfile()
            if (!EncryptedChildStore.hasRecords(this)) null
            else {
                childRepository.restore()
                if (childRepository.state.value is ChildSyncState.Blocked) throw ProfileValidationFailure(ProfileBlock.INVALID_CREDENTIALS)
                childRepository.binding.value?.deviceId
            }
        }, ::stopParentRuntime, { lease ->
            if (lease.role == ProfileRole.PARENT) startParentRuntime()
            else check(childRepository.binding.value?.deviceId == lease.ownerId)
        })
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
    fun hasChildSetup() = EncryptedChildStore.hasRecords(this)
    fun childForSetup(): ChildRepository? = if (!parentConstructionAllowed &&
        getSharedPreferences("harbor-secure-auth", MODE_PRIVATE).all.isEmpty() &&
        (profiles.state.value == ProfileState.Setup || profiles.state.value is ProfileState.Child)) childRepository else null
    private val parentApi by lazy { authRepository?.let { SdkParentApi(checkNotNull(client), it) } }
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
        authRepository?.let { auth -> ParentRuntime({ auth.identity.value },
            { owner -> auth.withAccessToken { check(auth.identity.value == owner); it } },
            { notifications?.disableLocally(); renderer.clear() }, { realtime?.disconnect() },
            { familyViewModel?.hideVisible(); securityViewModel?.clear(); mutableTap.value = ParentTapState() },
            { _, token -> fcmApi.remove(registrationStore.installationId(), token) },
            { tokenProvider.deleteToken() }, auth::signOutCurrent,
            { owner ->
                if (auth.identity.value == null || auth.identity.value == owner) {
                    try { familyViewModel?.clear() }
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
    private suspend fun stopParentRuntime() {
        parentCollectors?.cancelAndJoin()
        parentCollectors = null
        if (parentConstructionAllowed) {
            realtime?.disconnect()
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
    fun foreground() { accountScope.launch { accountWork {
        if (profiles.state.value !is ProfileState.Parent) return@accountWork
        if (currentIdentity() != null) {
            authRepository?.withAccessToken { }
            ensureContext()
            currentIdentity()?.let { familyViewModel?.refresh(it) }
        }
    } } }
    fun queueTokenRefresh() { accountScope.launch {
        profiles.state.first { it != ProfileState.Transitioning }
        if (profiles.state.value is ProfileState.Parent) registrationStore.optedOwner()?.let { ParentNotificationJob.enqueue(this@ParentApplication, it, null) }
    } }
    fun queueMessage(data: Map<String, String>) { accountScope.launch {
        profiles.state.first { it != ProfileState.Transitioning }
        if (profiles.state.value is ProfileState.Parent) registrationStore.optedOwner()?.let { ParentNotificationJob.enqueue(this@ParentApplication, it, data) }
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
    fun openNotification(hint: ParentHint) { accountScope.launch { accountWork {
        if (currentIdentity() == null) authRepository?.restore()
        ensureContext()
        val accepted = notifications?.onTap(hint) == true
        val device = if (accepted) familyViewModel?.repository?.state?.value?.snapshot?.devices?.firstOrNull { it.id == hint.route.deviceId } else null
        mutableTap.value = ParentTapState(device, if (accepted) null else "This update is unavailable. Refresh your family or sign in again.")
    } } }
    fun closeNotification() { mutableTap.value = ParentTapState() }
}
