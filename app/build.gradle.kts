plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "de.ledgerline.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.ledgerline.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "de")
        // 64-bit only; also drops the stale 4 KB-aligned prebuilt ABIs from lazysodium.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            useLegacyPackaging = false // uncompressed + page-aligned .so in the APK
            // Prefer our own 16 KB-aligned libsodium.so over lazysodium's 4 KB one.
            pickFirsts += "**/libsodium.so"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The unit-test suite grew large; give the forked test JVM enough heap so the
        // MockWebServer/OkHttp-based repo tests don't OutOfMemoryError.
        unitTests.all { it.maxHeapSize = "1536m" }
    }
    lint {
        // The bundled lifecycle lint detector `NonNullableMutableLiveDataDetector`
        // (check id `NullSafeMutableLiveData`) crashes against the Kotlin analysis
        // API pulled in by the Compose BOM 2026.01.01 upgrade
        // ("Found class KaCallableMemberCall, but interface was expected"). This is a
        // lint/AGP bug, not a code issue — disable the single offending check so the
        // release lint-vital pass runs. Revisit when AGP/lint ships a compatible build.
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging)

    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)

    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    // AndroidX Media3 (ExoPlayer) — open-source, no Google Play Services.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.zxing.core)
    implementation(libs.biometric)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.lazysodium.android) { exclude(group = "net.java.dev.jna", module = "jna") }
    implementation(libs.jna) { artifact { type = "aar" } }

    // Pure-Java/Kotlin PDF rendering (Apache-2, no native .so).
    implementation(libs.pdfbox.android)

    // Pure-Java OSM map tiles (Apache-2, no native .so).
    implementation(libs.osmdroid)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
