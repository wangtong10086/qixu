plugins { id("com.android.application") }
android {
    namespace = "dev.local.fuxicontrol.probe"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.local.fuxicontrol.probe"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
