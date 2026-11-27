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

        // Select which CMake targets are built. `targets` must live in
        // `defaultConfig.externalNativeBuild.cmake`, not `android.externalNativeBuild.cmake`.
        externalNativeBuild {
            cmake {
                targets += listOf("vulkan_probe", "mo2_input_bridge", "mo2_process_group_launcher")
            }
        }
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
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Stage the standalone process-group launcher as an extracted native-library payload.
// The executable is not loaded with System.loadLibrary; ProcessBuilder executes it directly.
val processGroupLauncherJniDir = layout.buildDirectory.dir("generated/processGroupLauncher/jniLibs")
android.sourceSets.getByName("debug").jniLibs.srcDir(processGroupLauncherJniDir)

val stageProcessGroupLauncherDebug = tasks.register("stageProcessGroupLauncherDebug") {
    // Do not resolve the executable with a provider: Gradle queries providers while
    // building the task graph, i.e. before `externalNativeBuildDebug` has produced the
    // artifact, which makes `assembleDebug` fail even on a clean x86_64 CI runner.
    // Discover and copy the output only at execution time, after the native build.
    dependsOn("externalNativeBuildDebug")
    val destinationDir = processGroupLauncherJniDir.get().dir("arm64-v8a").asFile
    doLast {
        // CMake target `mo2_process_group_launcher` emits `libmo2_process_group.so`
        // (PREFIX lib + SUFFIX .so, OUTPUT_NAME mo2_process_group).
        val includePattern = "**/libmo2_process_group.so"
        val primary = fileTree(projectDir.resolve(".cxx/Debug")).matching { include(includePattern) }.files
        val candidates = if (primary.isNotEmpty()) primary else {
            fileTree(layout.buildDirectory.dir("intermediates/cxx/Debug").get().asFile).matching {
                include(includePattern)
            }.files
        }
        if (candidates.size != 1) {
            throw GradleException("Expected one ARM64 process-group launcher executable, found ${candidates.size}: ${candidates.joinToString()}")
        }
        destinationDir.mkdirs()
        candidates.single().copyTo(File(destinationDir, "libmo2_process_group.so"), overwrite = true)
    }
}

tasks.matching { it.name == "mergeDebugNativeLibs" }.configureEach {
    dependsOn(stageProcessGroupLauncherDebug)
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
