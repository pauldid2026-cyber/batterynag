plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.batterynag.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.batterynag.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String",
            "SIGNING_SECRET",
            "\"" + (System.getenv("BATTERY_NAG_SIGNING_SECRET") ?: "").replace("\"", "\\\"") + "\""
        )
    }
    buildFeatures { buildConfig = true }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
    kotlin { jvmToolchain(17) }
}
dependencies { implementation("androidx.core:core-ktx:1.15.0") }
