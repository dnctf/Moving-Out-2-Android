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

// Stage the standalone process-group launcher as an extracted native-library payload.
// The executable is not loaded with System.loadLibrary; ProcessBuilder executes it directly.
val processGroupLauncherJniDir = layout.buildDirectory.dir("generated/processGroupLauncher/jniLibs")
android.sourceSets.getByName("debug").jniLibs.srcDir(processGroupLauncherJniDir)

val stageProcessGroupLauncherDebug = tasks.register<Copy>("stageProcessGroupLauncherDebug") {
    dependsOn("externalNativeBuildDebug")
    val helperFiles = fileTree(projectDir).matching {
        include(".cxx/Debug/**/mo2_process_group_launcher")
        include("build/intermediates/cxx/Debug/**/mo2_process_group_launcher")
    }.files
    if (helperFiles.size != 1) {
        throw GradleException("Expected one ARM64 process-group launcher executable, found ${helperFiles.size}: ${helperFiles.joinToString()}")
    }
    from(helperFiles.single())
    into(processGroupLauncherJniDir.get().dir("arm64-v8a").asFile)
    rename { "libmo2_process_group.so" }
}

tasks.matching { it.name == "mergeDebugNativeLibs" }.configureEach {
    dependsOn(stageProcessGroupLauncherDebug)
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
