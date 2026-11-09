plugins {
    id("com.android.application")
}

android {
    namespace = "com.belgilabs.toleg.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.belgilabs.toleg.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":toleg-sdk"))
}
