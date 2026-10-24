import java.io.File
import java.util.Base64
import java.util.Properties
import groovy.json.JsonSlurper
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}
val ciFixture = providers.gradleProperty("parentCiFixture").orNull == "true"
val configFile = rootProject.file(if (ciFixture) "config/ci-fixture.properties" else "parent.properties")
require(configFile.isFile) { "Supply development parent.properties; no default live configuration." }
val config = Properties().apply { configFile.inputStream().use { load(it) } }
require(config.getProperty("environment") == if (ciFixture) "ci" else "development") { "Unassigned environment" }
require(config.getProperty("supabaseUrl") == if (ciFixture) "https://parent-ci.invalid" else "https://bfvybxkjxilntjgndsrm.supabase.co") { "Wrong backend environment" }
require(config.getProperty("publishableKey", "").matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) { "Use a publishable client key" }
require(config.getProperty("applicationId") == "dev.stmedrano.harbor.parent") { "Wrong parent package" }
val project = config.getProperty("firebaseProjectId", "")
require(project.matches(Regex("[a-z][a-z0-9-]{4,62}")) && (if (ciFixture) project == "harbor-parent-ci" else project != "harbor-parent-ci")) { "Invalid Firebase project" }
val firebaseFile = rootProject.file(if (ciFixture) "config/ci-fixture-google-services.json" else "app/google-services.json")
require(firebaseFile.isFile) { "Supply matching parent Firebase Android configuration" }
val firebase = JsonSlurper().parse(firebaseFile) as Map<*, *>
require(!firebase.containsKey("private_key") && firebase["type"] != "service_account") { "Firebase client configuration required" }
require((firebase["project_info"] as? Map<*, *>)?.get("project_id") == project) { "Firebase project mismatch" }
val clients = firebase["client"] as? List<*> ?: emptyList<Any>()
require(clients.any { client ->
    val info = (client as? Map<*, *>)?.get("client_info") as? Map<*, *>
    (info?.get("android_client_info") as? Map<*, *>)?.get("package_name") == "dev.stmedrano.harbor.parent"
}) { "Firebase parent package mismatch" }
/**
 * Signing. A phone only accepts an update that is signed with the same key as the build it already has, so every
 * machine that builds Harbor has to use one key, not its own debug key. The key is never in this repository: a build
 * names it with HARBOR_KEYSTORE (a path) or, where no file can be placed, HARBOR_KEYSTORE_BASE64 (its bytes), with
 * HARBOR_KEYSTORE_PASSWORD and optionally HARBOR_KEY_ALIAS and HARBOR_KEY_PASSWORD. Either may be a Gradle property
 * instead of an environment variable. With none of them the build signs with this machine's own debug key, as before,
 * which is what CI and a plain local build do.
 */
fun harborSigning(name: String): String? = (providers.environmentVariable(name).orNull ?: providers.gradleProperty(name).orNull)
    ?.trim()?.takeIf { it.isNotEmpty() }
val harborKeystore: File? = harborSigning("HARBOR_KEYSTORE")?.let { path ->
    File(path).also { require(it.isFile) { "HARBOR_KEYSTORE names no file: $path" } }
} ?: harborSigning("HARBOR_KEYSTORE_BASE64")?.let { encoded ->
    layout.buildDirectory.file("harbor-signing.jks").get().asFile.also { file ->
        file.parentFile.mkdirs()
        file.writeBytes(Base64.getMimeDecoder().decode(encoded))
    }
}
val harborStorePassword = harborSigning("HARBOR_KEYSTORE_PASSWORD")
require(harborKeystore == null || harborStorePassword != null) { "Supply HARBOR_KEYSTORE_PASSWORD with the signing key" }

android {
    namespace = "dev.stmedrano.harbor.parent"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.stmedrano.harbor.parent"
        minSdk = 29
        targetSdk = 36
        buildConfigField("boolean", "CI_FIXTURE", ciFixture.toString())
        buildConfigField("String", "SUPABASE_URL", "\"${config.getProperty("supabaseUrl")}\"")
        buildConfigField("String", "PUBLISHABLE_KEY", "\"${config.getProperty("publishableKey")}\"")
        versionCode = 35
        versionName = "0.35-development"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (harborKeystore != null) create("harbor") {
            storeFile = harborKeystore
            storePassword = harborStorePassword
            keyAlias = harborSigning("HARBOR_KEY_ALIAS") ?: "harbor"
            keyPassword = harborSigning("HARBOR_KEY_PASSWORD") ?: harborStorePassword
        }
    }
    buildTypes {
        getByName("debug") { if (harborKeystore != null) signingConfig = signingConfigs.getByName("harbor") }
    }
    if (ciFixture) sourceSets.getByName("main").manifest.srcFile("../config/ci-manifest.xml")
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents.beforeVariants { variant -> variant.enable = variant.buildType == "debug" }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.functions)
    implementation(libs.ktor.okhttp)
    implementation(libs.coroutines)
    implementation(libs.serialization)
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation(libs.activity.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(platform(libs.firebase.bom))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.mock)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

