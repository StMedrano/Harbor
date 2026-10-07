package dev.stmedrano.harbor.parent

import android.app.Application
import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import dev.stmedrano.harbor.parent.auth.SupabaseAuthGateway

class ParentApplication : Application() {
    // One SDK/repository per process survives Activity recreation. The CI APK
    // never constructs a live client, in addition to having no INTERNET permission.
    val authRepository: ParentAuthRepository? by lazy {
        if (BuildConfig.CI_FIXTURE) null else {
            val store = SecureAuthStore.open(this)
            val client = SupabaseAuthGateway.createClient(BuildConfig.SUPABASE_URL, BuildConfig.PUBLISHABLE_KEY, store)
            ParentAuthRepository(SupabaseAuthGateway(client, store), store)
        }
    }
}
