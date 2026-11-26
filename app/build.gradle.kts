plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dnctf.movingout2"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.dnctf.movingout2"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.0-alpha.4"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Keep executable ELF payloads as extracted native files under nativeLibraryDir.
            keepDebugSymbols += "**/libbox64.so"
        }
    }

    // Preserve opaque Windows/runtime payload bytes if a developer stages optional
    // user-supplied assets here. The game itself remains SAF-imported (not embedded
    // in the APK) to avoid a 1.4 GiB APK and redistribution of proprietary game data.
    androidResources {
        noCompress += listOf("exe", "dll", "bundle", "bank", "tar", "xz", "zst")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Alpha distribution must be installable. Until a persistent private
            // release keystore is configured, use Android's generated debug key.
            // This signs the APK but does not provide a stable upgrade identity
            // across fresh CI runners; configure a private release keystore before
            // public/stable distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
