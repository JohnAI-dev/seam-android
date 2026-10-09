plugins {
    id("com.android.application")
}

// CI sets these so every build gets a higher version than the last.
val buildNumber = (System.getenv("SEAM_BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "dev.johnai.seam"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.johnai.seam"
        minSdk = 29
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Development signing key, kept in the repo so every build can update the last one.
    // Replace with a private key (stored as a GitHub secret) before publishing to Play.
    signingConfigs {
        create("seamDev") {
            storeFile = file("seam-dev.keystore")
            storePassword = "seamdev"
            keyAlias = "seam"
            keyPassword = "seamdev"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("seamDev")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("seamDev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
