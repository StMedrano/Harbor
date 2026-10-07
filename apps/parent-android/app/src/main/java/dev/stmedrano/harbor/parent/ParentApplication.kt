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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ParentTapState(val device: DevicePublicV1? = null, val message: String? = null)

class ParentApplication : Application() {
    val accountScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contextChanges = Mutex()
    private var boundIdentity: ParentIdentity? = null
    private val mutableTap = MutableStateFlow(ParentTapState())
    val tapState = mutableTap.asStateFlow()
    private val secureStore by lazy { SecureAuthStore.open(this) }
    private val client by lazy {
        if (BuildConfig.CI_FIXTURE) null else SupabaseAuthGateway.createClient(BuildConfig.SUPABASE_URL, BuildConfig.PUBLISHABLE_KEY, secureStore)
    }
    // One SDK/repository per process survives Activity recreation. The CI APK
    // never constructs a live client, in addition to having no INTERNET permission.
    val authRepository: ParentAuthRepository? by lazy {
        client?.let { ParentAuthRepository(SupabaseAuthGateway(it, secureStore), secureStore) }
    }
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
            { notifications?.invalidate(); renderer.clear() }, { realtime?.disconnect() },
            { familyViewModel?.hideVisible(); securityViewModel?.clear(); mutableTap.value = ParentTapState() },
            { _, token -> fcmApi.remove(registrationStore.installationId(), token) },
            { tokenProvider.deleteToken() }, auth::signOutCurrent,
            { owner ->
                if (auth.identity.value == null || auth.identity.value == owner) {
                    try { familyViewModel?.clear() }
                    finally {
                        try { auth.clearLocal() }
                        finally { registrationStore.clearMarker(); boundIdentity = null }
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
        if (BuildConfig.CI_FIXTURE) return
        val auth = checkNotNull(authRepository)
        accountScope.launch {
            auth.identity.collectLatest { identity ->
                if (identity != null) accountWork { ensureContext() }
                else { realtime?.disconnect(); familyViewModel?.hideVisible(); securityViewModel?.clear(); mutableTap.value = ParentTapState() }
            }
        }
        accountScope.launch {
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
    }
    private suspend fun ensureContext() = contextChanges.withLock {
        val identity = currentIdentity() ?: return@withLock
        if (boundIdentity == identity) return@withLock
        val optedIn = registrationStore.marker(identity) != null
        realtime?.disconnect(); notifications?.invalidate(); renderer.clear()
        securityViewModel?.clear()
        mutableTap.value = ParentTapState()
        familyViewModel?.clear()
        check(currentIdentity() == identity)
        familyViewModel?.load(identity)
        check(currentIdentity() == identity)
        if (optedIn) notifications?.enable()
        check(currentIdentity() == identity)
        boundIdentity = identity
    }
    private suspend fun refreshHint(hint: ParentHint): Boolean {
        val identity = currentIdentity() ?: return false
        val model = familyViewModel ?: return false
        model.refresh(identity)
        val state = model.repository.state.value
        val snapshot = state.snapshot ?: return false
        return currentIdentity() == identity && !state.cached && state.failure == null &&
            snapshot.family.id == hint.route.familyId && snapshot.membership.userId == identity.userId &&
            (hint.route.childId == null || snapshot.children.any { it.id == hint.route.childId }) &&
            (hint.route.deviceId == null || snapshot.devices.any { it.id == hint.route.deviceId && it.status == "active" })
    }
    suspend fun accountWork(action: suspend () -> Unit) {
        try { runtime?.authAction(action) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Controllers retain honest failed/unconfirmed states. */ }
    }
    fun foreground() { accountScope.launch { accountWork {
        if (currentIdentity() != null) {
            authRepository?.withAccessToken { }
            ensureContext()
            currentIdentity()?.let { familyViewModel?.refresh(it) }
        }
    } } }
    fun receiveToken(token: String) { accountScope.launch { accountWork { notifications?.onTokenChanged(token) } } }
    fun receiveMessage(data: Map<String, String>) { accountScope.launch { accountWork {
        if (currentIdentity() == null) authRepository?.restore()
        ensureContext()
        notifications?.onMessage(data)
    } } }
    fun openNotification(hint: ParentHint) { accountScope.launch { accountWork {
        if (currentIdentity() == null) authRepository?.restore()
        ensureContext()
        val accepted = notifications?.onTap(hint) == true
        val device = if (accepted) familyViewModel?.repository?.state?.value?.snapshot?.devices?.firstOrNull { it.id == hint.route.deviceId } else null
        mutableTap.value = ParentTapState(device, if (accepted) null else "This update is unavailable. Refresh your family or sign in again.")
    } } }
    fun closeNotification() { mutableTap.value = ParentTapState() }
}
