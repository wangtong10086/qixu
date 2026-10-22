plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.local.fuxicontrol"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.local.fuxicontrol"
        minSdk = 36
        targetSdk = 36
        versionCode = 17
        versionName = "0.6.0-alpha7"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }
    packaging { jniLibs.useLegacyPackaging = true; resources { merges += "META-INF/xposed/*" } }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(project(":core"))
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    compileOnly(files("../vendor/api-102.jar"))
}
