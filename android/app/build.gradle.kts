plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.appdistribution)
}

android {
    namespace = "com.rjnr.pocketnode"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rjnr.pocketnode"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            // Firebase App Distribution
            firebaseAppDistribution {
                artifactType = "APK"
                testers = "neon@nervos.community"
                releaseNotes = "v1.0.1: Fixed launch crash on Samsung devices. Includes multi-ABI support and debug signing."
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    configurations.all {
        resolutionStrategy {
            // Force use of the listenablefuture capability from guava
            capabilitiesResolution {
                withCapability("com.google.guava:listenablefuture") {
                    select("com.google.guava:guava:0")
                }
            }
        }
    }

}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Security & Crypto
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.secp256k1.kmp.jni.android)
    implementation(libs.kotlin.bip39)

    // CKB SDK
    implementation(libs.ckb.sdk.core)
    implementation(libs.ckb.sdk.utils)
    implementation(libs.bouncycastle)

    // Room (for caching)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // CameraX & ML Kit for QR scanning
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.accompanist.permissions)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

tasks.register<Exec>("cargoBuild") {
    workingDir = file("${project.rootDir}/../external/ckb-light-client")
    commandLine("./build-android-jni.sh")
    // 1. Try Android Gradle Plugin's detected NDK
    var ndkDir = try { android.ndkDirectory } catch (e: Exception) { null }

    // 2. Fallback: Check standard macOS NDK location
    if (ndkDir == null || !ndkDir.exists()) {
        val defaultNdkRoot = file("/Users/raheemjnr/Library/Android/sdk/ndk")
        if (defaultNdkRoot.exists()) {
            // Pick the latest valid version (must have toolchains)
            ndkDir = defaultNdkRoot.listFiles()
                ?.filter { it.isDirectory && File(it, "toolchains").exists() }
                ?.sortedByDescending { it.name }
                ?.firstOrNull()
        }
    }

    if (ndkDir != null && ndkDir.exists()) {
        println("Using NDK at: $ndkDir")
        environment("ANDROID_NDK_HOME", ndkDir)
    } else {
        println("WARNING: Could not find Android NDK. Build may fail.")
    }
}

tasks.named("preBuild") {
    dependsOn("cargoBuild")
}
