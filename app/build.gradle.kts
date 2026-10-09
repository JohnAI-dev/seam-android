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

    // Release builds are signed with a private key that exists only as GitHub secrets
    // (decoded to a file by the release workflow). Debug builds, used by tests and the
    // agent, use Android's standard debug key.
    val releaseKeystore = System.getenv("SEAM_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SEAM_KEYSTORE_PASSWORD")
                keyAlias = "seam"
                keyPassword = System.getenv("SEAM_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
