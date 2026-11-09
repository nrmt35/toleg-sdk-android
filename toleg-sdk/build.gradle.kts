plugins {
    id("com.android.library")
    `maven-publish`
}

group = "com.github.nrmt35"
version = "1.0.0"

android {
    namespace = "com.belgilabs.toleg"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "toleg-sdk-android"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
}
