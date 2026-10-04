package dev.stmedrano.harbor.acceptance

import android.content.Context
import org.json.JSONArray
import java.util.UUID

class AcceptanceRuntime private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("harbor.acceptance.messaging", Context.MODE_PRIVATE)
    private val childStore = AndroidChildStore(context)
    private val deviceKey = AndroidDeviceKey()
    private lateinit var backend: HarborApi
    val identity = DeviceIdentity({System.currentTimeMillis() / 1000L}, {backend.refreshSession(it)}, childStore::saveSession)
    val registration: Registration
    val receipts = ReceiptStore({value ->
        val rows = JSONArray(preferences.getString("receipts", "[]")).put(value)
        check(preferences.edit().putString("receipts", rows.toString()).commit()) { "Receipt persistence failed" }
    }, {
        val rows = JSONArray(preferences.getString("receipts", "[]"))
        (0 until rows.length()).map { rows.getString(it) }
    })
    init {
        backend = HarborApi(BuildConfig.SUPABASE_URL, BuildConfig.PUBLISHABLE_KEY, identity, ::androidTransport,
            {System.currentTimeMillis() / 1000L}, {UUID.randomUUID().toString()}, deviceKey::sign)
        childStore.loadSession()?.let(identity::acceptSession)
        childStore.loadBinding()?.let(identity::acceptBinding)
        registration = Registration({identity.binding}, backend::registerFcm)
        preferences.getString("pendingToken", null)?.let(registration::onToken)
    }
    @Synchronized fun onToken(token: String) {
        check(preferences.edit().putString("pendingToken", token).commit()) { "Token persistence failed" }
        registration.onToken(token)
    }
    fun claim(code: String) {
        if (childStore.loadSession() == null) backend.anonymousSignup()
        val binding = backend.claim(code, deviceKey.publicKeySpki())
        childStore.saveBinding(binding)
    }
    fun sync(): String = backend.sync(checkNotNull(identity.binding) { "Pair first" })
    companion object {
        @Volatile private var instance: AcceptanceRuntime? = null
        @Synchronized fun get(context: Context): AcceptanceRuntime = instance ?: AcceptanceRuntime(context.applicationContext).also { instance = it }
    }
}
