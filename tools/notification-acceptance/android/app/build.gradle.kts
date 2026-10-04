import java.util.Properties
plugins { id("com.android.application") }

val ciFixture = providers.gradleProperty("acceptanceCiFixture").orNull == "true"
val configFile = rootProject.file(if (ciFixture) "config/ci-fixture.properties" else "acceptance.properties")
require(configFile.isFile) { "Supply development acceptance.properties (see example); no default live configuration." }
val config = Properties().apply { configFile.inputStream().use { load(it) } }
require(config.getProperty("supabaseUrl") == "https://bfvybxkjxilntjgndsrm.supabase.co") { "Wrong backend environment" }
require(config.getProperty("publishableKey", "").matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) { "Use a publishable client key" }
require(config.getProperty("firebaseProjectId", "").isNotBlank()) { "Supply matching development Firebase project ID" }
require(config.getProperty("applicationId") == "dev.stmedrano.harbor.acceptance") { "Wrong acceptance application ID" }

android {
    namespace = "dev.stmedrano.harbor.acceptance"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.stmedrano.harbor.acceptance"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-test"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents.beforeVariants { variant -> variant.enable = variant.buildType == "debug" }
dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android supplies JSONObject at runtime; JVM tests need its real parser.
    testImplementation("org.json:json:20240303")
}
