plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "sk.devtools"
    compileSdk = 37

    defaultConfig {
        applicationId = "sk.devtools.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.compose.material3:material3:1.5.0-alpha29")
    implementation("androidx.compose.material:material-icons-core:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.8.0-alpha06")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
