import java.util.Properties
import groovy.json.JsonSlurper
plugins { id("com.android.application"); id("com.google.gms.google-services") }

val ciFixture = providers.gradleProperty("acceptanceCiFixture").orNull == "true"
val configFile = rootProject.file(if (ciFixture) "config/ci-fixture.properties" else "acceptance.properties")
require(configFile.isFile) { "Supply development acceptance.properties (see example); no default live configuration." }
val config = Properties().apply { configFile.inputStream().use { load(it) } }
require(config.getProperty("supabaseUrl") == "https://bfvybxkjxilntjgndsrm.supabase.co") { "Wrong backend environment" }
require(config.getProperty("publishableKey", "").matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) { "Use a publishable client key" }
require(config.getProperty("firebaseProjectId", "").matches(Regex("[a-z][a-z0-9-]{4,62}"))) { "Supply matching development Firebase project ID" }
require(config.getProperty("applicationId") == "dev.stmedrano.harbor.acceptance") { "Wrong acceptance application ID" }
val firebaseFile = rootProject.file(if (ciFixture) "config/ci-fixture-google-services.json" else "app/google-services.json")
require(firebaseFile.isFile) { "Supply the matching development Firebase Android app google-services.json" }
val firebase = JsonSlurper().parse(firebaseFile) as Map<*, *>
val projectInfo = firebase["project_info"] as? Map<*, *>
require(projectInfo?.get("project_id") == config.getProperty("firebaseProjectId")) { "Firebase project does not match acceptance configuration" }
val clients = firebase["client"] as? List<*> ?: emptyList<Any>()
require(clients.any { client ->
    val clientInfo = (client as? Map<*, *>)?.get("client_info") as? Map<*, *>
    val androidInfo = clientInfo?.get("android_client_info") as? Map<*, *>
    androidInfo?.get("package_name") == "dev.stmedrano.harbor.acceptance"
}) { "Firebase Android application ID mismatch" }

android {
    namespace = "dev.stmedrano.harbor.acceptance"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.stmedrano.harbor.acceptance"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2-test"
        buildConfigField("boolean", "CI_FIXTURE", ciFixture.toString())
        buildConfigField("String", "SUPABASE_URL", "\"${config.getProperty("supabaseUrl")}\"")
        buildConfigField("String", "PUBLISHABLE_KEY", "\"${config.getProperty("publishableKey")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${config.getProperty("firebaseProjectId")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents.beforeVariants { variant -> variant.enable = variant.buildType == "debug" }
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    // Android supplies JSONObject at runtime; JVM tests need its real parser.
    testImplementation("org.json:json:20240303")
}
