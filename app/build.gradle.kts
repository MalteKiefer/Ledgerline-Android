import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ── Version derived from git (reproducible, monotonic) ──────────────────────
// Marketing semver base; bump on real releases. The build metadata (commit count,
// short SHA, branch, UTC build date) is derived from git so every build is uniquely
// identifiable and versionCode grows monotonically without manual edits.
val versionBase = "0.9.0"

fun git(vararg args: String): String? = runCatching {
    ProcessBuilder(listOf("git", *args))
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start().inputStream.bufferedReader().readText().trim().ifEmpty { null }
}.getOrNull()

val gitCommitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val gitSha = git("rev-parse", "--short", "HEAD") ?: "nogit"
val gitBranch = git("rev-parse", "--abbrev-ref", "HEAD") ?: "detached"
val gitDirty = git("status", "--porcelain")?.isNotEmpty() == true
val buildDateUtc: String = SimpleDateFormat("yyyy-MM-dd")
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date())

android {
    namespace = "de.ledgerline.app"
    // AGP 9.2 + the AndroidX bumps (core 1.19, lifecycle 2.11, hilt-nav 1.4) require
    // compiling against API 37. targetSdk/minSdk stay at 36 (runtime behavior unchanged).
    compileSdk = 37

    defaultConfig {
        applicationId = "de.ledgerline.app"
        minSdk = 36
        targetSdk = 36
        // Monotonic from git history (CI-safe, no manual bumps); marketing string stays semver.
        versionCode = gitCommitCount
        versionName = versionBase
        // Build provenance for the About screen (never PII; git SHA + branch + UTC date).
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDateUtc\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "de", "ru")
        // 64-bit only; also drops the stale 4 KB-aligned prebuilt ABIs from lazysodium.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release signing from environment (CI secrets) or a gitignored keystore.properties —
    // never hardcode credentials. Absent → release stays unsigned (local dev / CI without
    // secrets) rather than silently signing with the debug key.
    val keystoreProps = rootProject.file("keystore.properties")
    val signingEnabled = System.getenv("LL_KEYSTORE_FILE") != null || keystoreProps.exists()
    signingConfigs {
        if (signingEnabled) {
            create("release") {
                val props = Properties().apply {
                    if (keystoreProps.exists()) keystoreProps.inputStream().use { load(it) }
                }
                fun cfg(env: String, key: String): String? = System.getenv(env) ?: props.getProperty(key)
                storeFile = cfg("LL_KEYSTORE_FILE", "storeFile")?.let { file(it) }
                storePassword = cfg("LL_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = cfg("LL_KEY_ALIAS", "keyAlias")
                keyPassword = cfg("LL_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingEnabled) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
        // NOTE: isIncludeAndroidResources cannot be true when minSdk >= 36 because
        // Robolectric (max SDK 35) rejects the binary-XML manifest that AGP injects.
        // No existing unit test needs merged Android resources; Robolectric tests use
        // @Config(sdk=[35]) + the synthetic Robolectric application context.
        unitTests.isIncludeAndroidResources = false
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material3.adaptive.navigation)
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
    implementation(libs.androidx.documentfile)
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
    implementation(libs.credentials)
    implementation(libs.autofill)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.lazysodium.android) { exclude(group = "net.java.dev.jna", module = "jna") }
    implementation(libs.jna) { artifact { type = "aar" } }

    // Pure-Java/Kotlin PDF rendering (Apache-2, no native .so).
    implementation(libs.pdfbox.android)
    // pdfbox-android 2.0.27.0 transitively pulls BouncyCastle 1.72 (CVE-2023-33201/33202 +
    // 2024 CVEs). Force the patched line even though PDFBox uses only the render paths.
    constraints {
        implementation(libs.bouncycastle.bcprov) { because("CVE-2023-33201/33202 patched in >=1.74") }
        implementation(libs.bouncycastle.bcpkix) { because("CVE-2023-33201/33202 patched in >=1.74") }
        implementation(libs.bouncycastle.bcutil) { because("align BouncyCastle modules") }
    }

    // MapLibre GL Android — BSD-licensed, libre map renderer (no Google, no Mapbox,
    // no API key, no telemetry). Renders OpenStreetMap RASTER tiles via a custom
    // raster style JSON (same tile source as the old osmdroid setup). The annotation
    // plugin (SymbolManager) provides marker pins. UA + tile HTTP client wired in
    // LedgerlineApp. android-sdk 13.x defaults to the Vulkan backend (fine at minSdk 36).
    // mapsforge — the app's sole map engine: offline vector maps + render engine (OSS, no Google).
    implementation(libs.mapsforge.map.android)
    implementation(libs.mapsforge.map)
    implementation(libs.mapsforge.map.reader)
    implementation(libs.mapsforge.themes)
    implementation(libs.mapsforge.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
