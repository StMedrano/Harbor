plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.harbor.family.web"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.harbor.family.web"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // The HTTPS site this app opens. Change it here (or pass -PharborUrl=https://...) and rebuild.
        val url = (project.findProperty("harborUrl") as String?) ?: "https://harbor-lyart-nu.vercel.app/"
        buildConfigField("String", "SITE_URL", "\"$url\"")
        }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
