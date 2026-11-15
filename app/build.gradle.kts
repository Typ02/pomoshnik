plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.typ02.pomoshnik"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.typ02.pomoshnik"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("rel") {
            storeFile = file("pomoshnik.jks")
            storePassword = "pomoshnik"
            keyAlias = "pomoshnik"
            keyPassword = "pomoshnik"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rel")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
}
