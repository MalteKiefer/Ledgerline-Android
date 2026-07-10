# Ledgerline Android — Phase 1 Implementation Plan (Pairing, Security, Vault Unlock)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a native Android client foundation that pairs to a self-hosted Ledgerline instance via QR/deep-link, stores the bearer token under hardware-backed authenticated encryption, gates the app behind biometric/device-credential lock, pins the server over TLS (TOFU), and unlocks the zero-knowledge vault (passphrase → Vault Key) entirely on-device.

**Architecture:** Single Gradle module, clean layered (ui / domain / data / core), MVVM with unidirectional Compose state. Crypto isolated behind a `Crypto` interface backed by libsodium (lazysodium-android). Token/pin sealed with an AndroidKeystore AES-256-GCM key gated by user auth. Networking via Retrofit/OkHttp with a stored-SPKI trust manager. Vault Key lives only in memory and is wiped on background/idle.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Coroutines/Flow, Hilt, Retrofit + OkHttp, CameraX + ZXing (no ML Kit), lazysodium-android + JNA, DataStore (Preferences), AndroidKeystore, BiometricPrompt. minSdk/targetSdk/compileSdk = 36. appId `de.ledgerline.app`.

**Reference:** Design spec `docs/superpowers/specs/2026-07-10-phase1-pairing-security-design.md`. Crypto ground truth `~/Entwicklung/ledgerline/resources/js/vault.js`. Transport contract `CLAUDE.md`.

**Conventions:** English only in all code/commits/docs. Conventional Commits. Git Flow: work happens on `feature/phase1-*` branches off `develop`; nothing committed contains secrets. Commit after every green test.

---

## File Structure

```
settings.gradle.kts
build.gradle.kts                      (root)
gradle/libs.versions.toml             (version catalog)
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/res/xml/network_security_config.xml
app/src/main/res/xml/locales_config.xml
app/src/main/res/values/strings.xml           (English source)
app/src/main/res/values-de/strings.xml        (German)
app/src/main/res/values/themes.xml

app/src/main/java/de/ledgerline/app/
  LedgerlineApp.kt                    (@HiltAndroidApp Application)
  MainActivity.kt                     (single activity, FLAG_SECURE, Compose host)

  core/
    Outcome.kt                        (Result sealed type)
    crypto/
      Crypto.kt                       (interface)
      SodiumCrypto.kt                 (lazysodium impl)
    security/
      KeystoreSealer.kt               (AES-256-GCM keystore wrapper)
      AppLock.kt                      (BiometricPrompt gate)
      VaultKeyHolder.kt               (in-memory VK, lifecycle wipe)

  data/
    remote/
      LedgerlineApi.kt                (Retrofit interface)
      dto/AuthDtos.kt                 (pairing / me DTOs)
      dto/VaultDtos.kt                (vault DTO)
      PinnedTrust.kt                  (SPKI TOFU trust manager + capture)
      NetworkFactory.kt               (OkHttp/Retrofit builder per baseUrl)
      interceptors/AuthInterceptor.kt
      interceptors/BackoffInterceptor.kt
    SessionStore.kt                   (sealed token/baseUrl/pin via DataStore)
    PairingRepository.kt
    VaultRepository.kt

  domain/
    model/PairingState.kt
    model/Session.kt
    usecase/ClaimAndPollPairing.kt
    usecase/UnlockVault.kt

  ui/
    pairing/PairingScreen.kt, PairingViewModel.kt
    lock/LockScreen.kt, LockViewModel.kt
    unlock/UnlockScreen.kt, UnlockViewModel.kt
    scan/QrScanner.kt                 (CameraX + ZXing analyzer)
    nav/AppNav.kt                     (root navigation / flow gating)
    theme/Theme.kt

  di/
    AppModule.kt, CryptoModule.kt, NetworkModule.kt

app/src/test/java/de/ledgerline/app/            (JVM unit tests)
app/src/androidTest/java/de/ledgerline/app/     (instrumented)
```

---

## Task 0: Resolve build prerequisites (no TDD — environment setup)

**Files:** none (environment).

- [ ] **Step 1: Check for a JDK**

Run: `/usr/libexec/java_home -V 2>&1 || echo NO_JDK`
Expected: either a JDK 17/21 path, or `NO_JDK`.

- [ ] **Step 2: If NO_JDK, install a libre JDK (Temurin 21)**

Run: `brew install --cask temurin@21`
Then verify: `/usr/libexec/java_home -v 21` prints a path.
Record that path; it becomes `org.gradle.java.home` if the emulator/CI needs it.

- [ ] **Step 3: Confirm cmdline-tools + list system images**

Run: `~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --list 2>/dev/null | grep 'system-images;android-36' || echo NO_36_IMAGE`
Expected: a `system-images;android-36;google_apis_playstore;arm64-v8a` line, or `NO_36_IMAGE`.

- [ ] **Step 4: If NO_36_IMAGE, install one + verify the AVD target**

Run: `~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "system-images;android-36;google_apis;arm64-v8a"`
Then check the existing AVD: `cat ~/.android/avd/Pixel_9a.ini | grep target`
If it does not target android-36, note that a fresh API-36 AVD will be created during first run (`avdmanager create avd -n Ledgerline_API36 -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_9a`).

- [ ] **Step 5: Record findings**

Write the resolved JDK path and available API-36 image name into the plan's execution notes (or a scratch file). No commit.

---

## Task 1: Project scaffold (Gradle, Hilt, manifest, single Activity)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `gradle.properties`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/de/ledgerline/app/LedgerlineApp.kt`, `MainActivity.kt`
- Create: `app/src/main/res/values/themes.xml`, `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.google\\.dagger.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google Maven only for AndroidX / AGP / Hilt — never GMS/Firebase artifacts.
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.google\\.dagger.*") } }
        mavenCentral()
    }
}
rootProject.name = "Ledgerline-Android"
include(":app")
```

- [ ] **Step 2: Create `gradle/libs.versions.toml`** (pin every dependency)

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
hilt = "2.52"
composeBom = "2024.12.01"
lifecycle = "2.8.7"
navigation = "2.8.5"
retrofit = "2.11.0"
okhttp = "4.12.0"
kotlinxSerialization = "1.7.3"
retrofitSerialization = "1.0.0"
datastore = "1.1.1"
camerax = "1.4.1"
zxing = "3.5.3"
biometric = "1.2.0-alpha05"
lazysodium = "5.1.0"
jna = "5.14.0"
coroutines = "1.9.0"
junit = "4.13.2"
mockk = "1.13.13"
androidxTestJunit = "1.2.1"

[libraries]
androidx-core = { module = "androidx.core:core-ktx", version = "1.15.0" }
androidx-lifecycle-runtime = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version = "1.9.3" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version = "1.2.0" }
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
retrofit-kotlinx-serialization = { module = "com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter", version.ref = "retrofitSerialization" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
camera-view = { module = "androidx.camera:camera-view", version.ref = "camerax" }
zxing-core = { module = "com.google.zxing:core", version.ref = "zxing" }
biometric = { module = "androidx.biometric:biometric-ktx", version.ref = "biometric" }
lazysodium-android = { module = "com.goterl:lazysodium-android", version.ref = "lazysodium" }
jna = { module = "net.java.dev.jna:jna", version.ref = "jna" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
# test
junit = { module = "junit:junit", version.ref = "junit" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
androidx-test-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestJunit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 3: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

- [ ] **Step 4: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 5: Create `app/build.gradle.kts`**

```kotlin
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
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
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
    implementation(libs.zxing.core)
    implementation(libs.biometric)

    implementation(libs.lazysodium.android)
    implementation(libs.jna) { artifact { type = "aar" } }

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.junit)
}
```

- [ ] **Step 6: Create `LedgerlineApp.kt`**

```kotlin
package de.ledgerline.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LedgerlineApp : Application()
```

- [ ] **Step 7: Create `MainActivity.kt`** (FLAG_SECURE app-wide)

```kotlin
package de.ledgerline.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import de.ledgerline.app.ui.nav.AppNav
import de.ledgerline.app.ui.theme.LedgerlineTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MASVS-STORAGE: block screenshots, screen recording, recents preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { LedgerlineTheme { AppNav() } }
    }
}
```

- [ ] **Step 8: Create minimal `themes.xml`, `strings.xml`, `Theme.kt`, and stub `AppNav.kt`**

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.Ledgerline" parent="android:Theme.Material.NoActionBar" />
</resources>
```
`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Ledgerline</string>
</resources>
```
`ui/theme/Theme.kt`:
```kotlin
package de.ledgerline.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

@Composable
fun LedgerlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
```
`ui/nav/AppNav.kt` (temporary placeholder, replaced in Task 12):
```kotlin
package de.ledgerline.app.ui.nav

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun AppNav() { Text("Ledgerline") }
```

- [ ] **Step 9: Create `AndroidManifest.xml`** (hardening flags, minimal perms)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.any" android:required="false" />

    <application
        android:name=".LedgerlineApp"
        android:allowBackup="false"
        android:fullBackupContent="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:label="@string/app_name"
        android:localeConfig="@xml/locales_config"
        android:networkSecurityConfig="@xml/network_security_config"
        android:theme="@style/Theme.Ledgerline">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- Pairing deep link; scheme/host validated in code before use. -->
            <intent-filter android:autoVerify="false">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="ledgerline" android:host="pair" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 10: Create the referenced XML resources**

`app/src/main/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```
`app/src/main/res/xml/locales_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
    <locale android:name="de" />
</locale-config>
```
`app/src/main/res/xml/data_extraction_rules.xml` (exclude everything from cloud/D2D):
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup><exclude domain="root" /></cloud-backup>
    <device-transfer><exclude domain="root" /></device-transfer>
</data-extraction-rules>
```

- [ ] **Step 11: Create `proguard-rules.pro`** (strip logs, keep JNA/lazysodium)

```proguard
# Strip Android logging in release.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}
# JNA / lazysodium need reflection + native mappings preserved.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.**
```

- [ ] **Step 12: Build to verify the scaffold compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (If Gradle wrapper is missing, generate it first: `gradle wrapper --gradle-version 8.11.1` using the JDK from Task 0, then re-run.)

- [ ] **Step 13: Commit**

```bash
git checkout -b develop
git checkout -b feature/phase1-scaffold
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ app/
git commit -m "build: scaffold Android app module with Hilt, Compose, hardening flags"
```

---

## Task 2: Crypto layer — `Crypto` interface + `SodiumCrypto` (byte-parity with vault.js)

**Files:**
- Create: `core/Outcome.kt`, `core/crypto/Crypto.kt`, `core/crypto/SodiumCrypto.kt`
- Test: `app/src/test/java/de/ledgerline/app/core/crypto/SodiumCryptoTest.kt`

Note: `SodiumCrypto` calls native libsodium, so its parity test runs as an **instrumented** test on the emulator (native `.so` unavailable on the JVM). Pure-JVM tests use a fake `Crypto` in later tasks. Base64 uses `android.util.Base64.NO_WRAP` = standard alphabet **with padding** = libsodium `base64_variants.ORIGINAL`.

- [ ] **Step 1: Create `core/Outcome.kt`**

```kotlin
package de.ledgerline.app.core

sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val kind: ErrorKind, val cause: Throwable? = null) : Outcome<Nothing>
}

enum class ErrorKind { NETWORK, HTTP, WRONG_PASSPHRASE, DECRYPT, PIN_MISMATCH, NOT_CONFIGURED, GONE, RATE_LIMITED, UNKNOWN }
```

- [ ] **Step 2: Create `core/crypto/Crypto.kt`** (the interface; matches vault.js primitives)

```kotlin
package de.ledgerline.app.core.crypto

/**
 * Zero-knowledge primitives, byte-compatible with resources/js/vault.js.
 * Base64 is libsodium ORIGINAL variant (standard, padded).
 * Phase 1 uses deriveKek + secretBoxOpen + genericHash32 only; the rest are
 * defined for later phases.
 */
interface Crypto {
    /** Argon2id (ALG_ARGON2ID13): passphrase + 16-byte salt -> 32-byte KEK. */
    fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long): ByteArray

    /** crypto_secretbox_open_easy. Returns null on auth failure (wrong key). */
    fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?

    /** crypto_generichash to 32 bytes, keyless (recovery key derivation). */
    fun genericHash32(input: ByteArray): ByteArray

    fun b64decode(s: String): ByteArray
    fun b64encode(b: ByteArray): String
    fun fromHex(s: String): ByteArray
}
```

- [ ] **Step 3: Write the failing instrumented parity test**

`app/src/androidTest/java/de/ledgerline/app/core/crypto/SodiumCryptoTest.kt`:
```kotlin
package de.ledgerline.app.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SodiumCryptoTest {
    private val crypto = SodiumCrypto()

    // Round-trip: seal a payload with a known KEK-equivalent key, confirm open recovers it.
    // Uses secretbox directly to build a fixture, then verifies secretBoxOpen inverts it.
    @Test fun secretBoxOpen_recovers_plaintext_and_rejects_wrong_key() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(24) { (it + 7).toByte() }
        val plaintext = "vault-key-material".toByteArray()
        val cipher = crypto.secretBoxSealForTest(plaintext, nonce, key)

        assertArrayEquals(plaintext, crypto.secretBoxOpen(cipher, nonce, key))
        val wrong = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(crypto.secretBoxOpen(cipher, nonce, wrong))
    }

    @Test fun deriveKek_is_deterministic_for_same_inputs() {
        val salt = ByteArray(16) { it.toByte() }
        val a = crypto.deriveKek("correct horse".toByteArray(), salt, 2, 67108864)
        val b = crypto.deriveKek("correct horse".toByteArray(), salt, 2, 67108864)
        assertArrayEquals(a, b)
        assert(a.size == 32)
    }

    @Test fun genericHash32_matches_length_and_determinism() {
        val h1 = crypto.genericHash32(byteArrayOf(1, 2, 3))
        val h2 = crypto.genericHash32(byteArrayOf(1, 2, 3))
        assertArrayEquals(h1, h2)
        assert(h1.size == 32)
    }

    @Test fun base64_is_original_variant_padded() {
        val bytes = byteArrayOf(0, 1, 2, 3, 4)
        assert(crypto.b64encode(bytes) == "AAECAwQ=")
        assertArrayEquals(bytes, crypto.b64decode("AAECAwQ="))
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest` (emulator running)
Expected: FAIL / won't compile — `SodiumCrypto` not defined.

- [ ] **Step 5: Implement `core/crypto/SodiumCrypto.kt`**

```kotlin
package de.ledgerline.app.core.crypto

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SodiumCrypto @Inject constructor() : Crypto {
    private val ls = LazySodiumAndroid(SodiumAndroid())

    override fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long): ByteArray {
        require(salt.size == PwHash.SALTBYTES) { "salt must be ${PwHash.SALTBYTES} bytes" }
        val out = ByteArray(SecretBox.KEYBYTES) // 32
        val ok = ls.cryptoPwHash(
            out, out.size,
            passphrase, passphrase.size,
            salt,
            opsLimit, memLimit.toNativeLong(),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        check(ok) { "argon2id derivation failed" }
        return out
    }

    override fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        val msgLen = cipher.size - SecretBox.MACBYTES
        if (msgLen < 0) return null
        val out = ByteArray(msgLen)
        val ok = ls.cryptoSecretBoxOpenEasy(out, cipher, cipher.size.toLong(), nonce, key)
        return if (ok) out else null
    }

    override fun genericHash32(input: ByteArray): ByteArray {
        val out = ByteArray(32)
        val ok = ls.cryptoGenericHash(out, out.size, input, input.size.toLong(), null, 0)
        check(ok) { "generichash failed" }
        return out
    }

    override fun b64decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    override fun b64encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    override fun fromHex(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() }
        return ByteArray(clean.length / 2) { ((clean[it * 2].digitToInt(16) shl 4) or clean[it * 2 + 1].digitToInt(16)).toByte() }
    }

    // Test-only helper to build a secretbox ciphertext fixture.
    internal fun secretBoxSealForTest(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(message.size + SecretBox.MACBYTES)
        check(ls.cryptoSecretBoxEasy(out, message, message.size.toLong(), nonce, key)) { "seal failed" }
        return out
    }

    private fun Long.toNativeLong() = com.sun.jna.NativeLong(this)
}
```
Note: verify the exact `cryptoPwHash` overload against lazysodium 5.1.0 during implementation; `memLimit` is a `NativeLong`. If the installed signature differs, adapt the call while keeping arguments (ops from server, mem from server, ALG_ARGON2ID13, out=32) identical.

- [ ] **Step 6: Run to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/core/ app/src/androidTest/
git commit -m "feat: add libsodium crypto layer with byte-parity tests"
```

---

## Task 3: Keystore token sealer (AES-256-GCM, user-auth gated)

**Files:**
- Create: `core/security/KeystoreSealer.kt`
- Test: `app/src/androidTest/java/de/ledgerline/app/core/security/KeystoreSealerTest.kt`

- [ ] **Step 1: Write the failing instrumented test**

```kotlin
package de.ledgerline.app.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSealerTest {
    // requireAuth=false variant so the test needs no BiometricPrompt.
    private val sealer = KeystoreSealer(alias = "ledgerline_test_key", requireAuth = false)

    @Test fun seal_then_open_roundtrips() {
        val secret = "bearer-token-xyz".toByteArray()
        val blob = sealer.seal(secret)
        assertFalse(blob.isEmpty())
        assertArrayEquals(secret, sealer.open(blob))
    }

    @Test fun ciphertext_is_not_plaintext() {
        val secret = "bearer-token-xyz".toByteArray()
        val blob = sealer.seal(secret)
        assertFalse(String(blob).contains("bearer-token-xyz"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*KeystoreSealerTest*"`
Expected: FAIL — `KeystoreSealer` not defined.

- [ ] **Step 3: Implement `core/security/KeystoreSealer.kt`**

```kotlin
package de.ledgerline.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets (bearer token, base URL, SPKI pin) with a hardware-backed
 * AES-256-GCM key in the AndroidKeystore. On API 36 StrongBox is available and
 * requested. When requireAuth=true the key can only be used after a successful
 * BiometricPrompt; the key is invalidated if the user enrolls a new biometric.
 *
 * Blob layout: [1-byte IV length][IV][GCM ciphertext+tag].
 */
class KeystoreSealer(
    private val alias: String = "ledgerline_token_key",
    private val requireAuth: Boolean = true,
) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(true)
            .apply {
                if (requireAuth) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    setInvalidatedByBiometricEnrollment(true)
                }
            }
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        return ByteArray(1 + iv.size + ct.size).apply {
            this[0] = iv.size.toByte()
            System.arraycopy(iv, 0, this, 1, iv.size)
            System.arraycopy(ct, 0, this, 1 + iv.size, ct.size)
        }
    }

    fun open(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    fun clear() { if (store.containsAlias(alias)) store.deleteEntry(alias) }
}
```
Note: with `requireAuth=true`, `cipher.init(DECRYPT_MODE, ...)` requires the key to have been unlocked via `BiometricPrompt.authenticate(CryptoObject(cipher))` — wired in Task 4. The test uses `requireAuth=false` to isolate the crypto round-trip.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*KeystoreSealerTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/core/security/KeystoreSealer.kt app/src/androidTest/
git commit -m "feat: add hardware-backed AES-GCM keystore sealer"
```

---

## Task 4: Biometric app-lock gate

**Files:**
- Create: `core/security/AppLock.kt`
- Test: manual/instrumented smoke (BiometricPrompt cannot be unit-tested headless); logic seams unit-tested where possible.

- [ ] **Step 1: Implement `core/security/AppLock.kt`**

```kotlin
package de.ledgerline.app.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface LockResult {
    data object Success : LockResult
    data object Unavailable : LockResult
    data class Failed(val code: Int, val message: String) : LockResult
}

class AppLock {
    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAuthenticate(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    /** Prompt biometric/device-credential. Returns when the user resolves it. */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String): LockResult =
        suspendCancellableCoroutine { cont ->
            if (!canAuthenticate(activity)) { cont.resume(LockResult.Unavailable); return@suspendCancellableCoroutine }
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(LockResult.Success)
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (cont.isActive) cont.resume(LockResult.Failed(code, msg.toString()))
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title).setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(info)
        }
}
```
Note: `MainActivity` must extend `FragmentActivity` (it does — `ComponentActivity` is a `FragmentActivity` subclass via androidx.activity). Confirm the activity is a `FragmentActivity` when wiring in Task 12; if not, switch base class.

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/core/security/AppLock.kt
git commit -m "feat: add biometric + device-credential app lock"
```

---

## Task 5: Networking — DTOs, TOFU SPKI trust, interceptors, factory

**Files:**
- Create: `data/remote/dto/AuthDtos.kt`, `dto/VaultDtos.kt`, `data/remote/LedgerlineApi.kt`, `data/remote/PinnedTrust.kt`, `data/remote/interceptors/AuthInterceptor.kt`, `interceptors/BackoffInterceptor.kt`, `data/remote/NetworkFactory.kt`
- Test: `app/src/test/java/de/ledgerline/app/data/remote/PinnedTrustTest.kt`, `NetworkFactoryPairingTest.kt`

- [ ] **Step 1: Create DTOs `data/remote/dto/AuthDtos.kt`**

```kotlin
package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class PairClaimRequest(val code: String, val device_name: String)
@Serializable data class PairClaimResponse(val status: String)

@Serializable data class PairPollResponse(
    val status: String,
    val token: String? = null,
    val user: PairedUser? = null,
)
@Serializable data class PairedUser(
    val id: Long? = null,
    val name: String? = null,
    val email: String? = null,
    val locale: String? = null,
)
```

- [ ] **Step 2: Create `data/remote/dto/VaultDtos.kt`**

```kotlin
package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class VaultResponse(
    val configured: Boolean = false,
    val salt: String? = null,
    val kdf_ops: Long? = null,
    val kdf_mem: Long? = null,
    val wrapped_vault_key: String? = null,
    val wrap_nonce: String? = null,
    val has_recovery: Boolean = false,
    val wrapped_vault_key_recovery: String? = null,
    val recovery_nonce: String? = null,
)
```

- [ ] **Step 3: Create `data/remote/LedgerlineApi.kt`**

```kotlin
package de.ledgerline.app.data.remote

import de.ledgerline.app.data.remote.dto.PairClaimRequest
import de.ledgerline.app.data.remote.dto.PairClaimResponse
import de.ledgerline.app.data.remote.dto.PairPollResponse
import de.ledgerline.app.data.remote.dto.VaultResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface LedgerlineApi {
    @POST("api/v1/auth/pair")
    suspend fun claimPair(@Body body: PairClaimRequest): Response<PairClaimResponse>

    @GET("api/v1/auth/pair")
    suspend fun pollPair(@Query("code") code: String): Response<PairPollResponse>

    @GET("api/v1/vault")
    suspend fun vault(): Response<VaultResponse>
}
```

- [ ] **Step 4: Write the failing test for SPKI extraction `PinnedTrustTest.kt`**

```kotlin
package de.ledgerline.app.data.remote

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PinnedTrustTest {
    @Test fun spki_pin_is_stable_for_same_cert() {
        val cert = HeldCertificate.Builder().commonName("home.kiefer-networks.de").build().certificate
        val a = PinnedTrust.spkiSha256Base64(cert)
        val b = PinnedTrust.spkiSha256Base64(cert)
        assertNotNull(a)
        assertEquals(a, b)
        assert(a.startsWith("sha256/"))
    }
}
```
(Requires `okhttp-tls` test dep; add `testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")` to the catalog/module.)

- [ ] **Step 5: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PinnedTrustTest*"`
Expected: FAIL — `PinnedTrust` not defined.

- [ ] **Step 6: Implement `data/remote/PinnedTrust.kt`**

```kotlin
package de.ledgerline.app.data.remote

import android.util.Base64
import okhttp3.CertificatePinner
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Trust-on-first-use SPKI pinning. At pairing we record the leaf certificate's
 * SubjectPublicKeyInfo SHA-256 ("sha256/...."). Later requests enforce it via an
 * OkHttp CertificatePinner, so a swapped server certificate (even one from a
 * valid CA) is rejected.
 */
object PinnedTrust {
    fun spkiSha256Base64(cert: X509Certificate): String {
        val spki = cert.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(spki)
        return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    fun pinnerFor(host: String, pin: String): CertificatePinner =
        CertificatePinner.Builder().add(host, pin).build()
}
```
Note: `Base64` here is `android.util.Base64`; the unit test runs on the emulator-less JVM, so for the test add a tiny JVM shim OR make this method take a pre-encoded digest. Simpler: keep `java.util.Base64` for portability:
```kotlin
// replace the android.util.Base64 line with:
return "sha256/" + java.util.Base64.getEncoder().encodeToString(digest)
```
Use `java.util.Base64` (available on Android API 26+, well within minSdk 36) so the same code runs in JVM unit tests.

- [ ] **Step 7: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PinnedTrustTest*"`
Expected: PASS.

- [ ] **Step 8: Implement interceptors**

`data/remote/interceptors/AuthInterceptor.kt`:
```kotlin
package de.ledgerline.app.data.remote.interceptors

import okhttp3.Interceptor
import okhttp3.Response

/** Adds Bearer auth to /api/v1 calls except the public /auth/pair claim+poll. */
class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val isPublic = req.url.encodedPath.endsWith("/api/v1/auth/pair")
        val token = tokenProvider()
        val out = if (!isPublic && token != null) {
            req.newBuilder().header("Authorization", "Bearer $token").build()
        } else req
        return chain.proceed(out)
    }
}
```
`data/remote/interceptors/BackoffInterceptor.kt`:
```kotlin
package de.ledgerline.app.data.remote.interceptors

import okhttp3.Interceptor
import okhttp3.Response

/** Retries on HTTP 429 honoring Retry-After, with capped exponential backoff. */
class BackoffInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var response = chain.proceed(chain.request())
        while (response.code == 429 && attempt < maxRetries) {
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            val delayMs = (retryAfter?.times(1000)) ?: (1000L shl attempt)
            response.close()
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            attempt++
            response = chain.proceed(chain.request())
        }
        return response
    }
}
```

- [ ] **Step 9: Implement `data/remote/NetworkFactory.kt`**

```kotlin
package de.ledgerline.app.data.remote

import de.ledgerline.app.data.remote.interceptors.AuthInterceptor
import de.ledgerline.app.data.remote.interceptors.BackoffInterceptor
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds a Retrofit client bound to a specific base URL. `pin` is null during the
 * initial pairing claim/poll (TOFU: pin not yet known); once known, all sessions
 * use a pinned client.
 */
object NetworkFactory {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(baseUrl: String, tokenProvider: () -> String?, pin: String?): LedgerlineApi {
        val host = baseUrl.toHttpHostOrThrow()
        val builder = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS)) // TLS 1.2+/1.3 strong ciphers
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(BackoffInterceptor())
        if (pin != null) builder.certificatePinner(PinnedTrust.pinnerFor(host, pin))
        val retrofit = Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(builder.build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(LedgerlineApi::class.java)
    }

    private fun String.toHttpHostOrThrow(): String =
        okhttp3.HttpUrl.get(this).host
}
```
Note: `ConnectionSpec.RESTRICTED_TLS` enforces TLS 1.2+ with strong cipher suites; combined with `network_security_config` (cleartext disabled) this satisfies the transport requirement. Verify `HttpUrl.get` (OkHttp 4 uses `baseUrl.toHttpUrl()` extension — adapt import to `okhttp3.HttpUrl.Companion.toHttpUrl`).

- [ ] **Step 10: Write + run a MockWebServer test for the pairing poll `NetworkFactoryPairingTest.kt`**

```kotlin
package de.ledgerline.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NetworkFactoryPairingTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun poll_returns_approved_token() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"approved","token":"tok123","user":{"id":1,"name":"Malte"}}""")
            .addHeader("Content-Type", "application/json"))
        // http base URL: pin=null path (pairing phase uses no pin, and MockWebServer is plain http).
        val api = NetworkFactory.create(server.url("/").toString(), tokenProvider = { null }, pin = null)
        val res = api.pollPair("abc")
        assertEquals(200, res.code())
        assertEquals("approved", res.body()!!.status)
        assertEquals("tok123", res.body()!!.token)
    }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*NetworkFactoryPairingTest*"`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/data/remote/ app/src/test/ gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: add networking layer with TOFU SPKI pinning and pairing DTOs"
```

---

## Task 6: SessionStore + PairingRepository + pairing state machine

**Files:**
- Create: `domain/model/PairingState.kt`, `domain/model/Session.kt`, `data/SessionStore.kt`, `data/PairingRepository.kt`, `domain/usecase/ClaimAndPollPairing.kt`
- Test: `app/src/test/java/de/ledgerline/app/domain/usecase/ClaimAndPollPairingTest.kt`

- [ ] **Step 1: Create `domain/model/Session.kt` and `PairingState.kt`**

```kotlin
// domain/model/Session.kt
package de.ledgerline.app.domain.model

data class Session(val baseUrl: String, val token: String, val spkiPin: String, val userName: String?)
```
```kotlin
// domain/model/PairingState.kt
package de.ledgerline.app.domain.model

sealed interface PairingState {
    data object Idle : PairingState
    data object Claiming : PairingState
    data object Polling : PairingState
    data class Approved(val session: Session) : PairingState
    data class Failed(val reason: PairingFailure) : PairingState
}
enum class PairingFailure { INVALID_LINK, NOT_HTTPS, CONSUMED_OR_EXPIRED, NETWORK, RATE_LIMITED, UNKNOWN }
```

- [ ] **Step 2: Create `data/SessionStore.kt`** (sealed persistence)

```kotlin
package de.ledgerline.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ledgerline_session")

@Serializable private data class SealedSession(val baseUrl: String, val token: String, val spkiPin: String, val userName: String?)

/** Persists the session as a single AES-GCM sealed blob (keystore-gated). */
class SessionStore(private val context: Context, private val sealer: KeystoreSealer) {
    private val key = byteArrayPreferencesKey("session_blob")
    private val json = Json

    suspend fun save(session: Session) {
        val plain = json.encodeToString(SealedSession(session.baseUrl, session.token, session.spkiPin, session.userName))
        val blob = sealer.seal(plain.toByteArray())
        context.dataStore.edit { it[key] = blob }
    }

    /** Requires the keystore key to be unlocked (BiometricPrompt) beforehand. */
    suspend fun load(): Session? {
        val blob = context.dataStore.data.first()[key] ?: return null
        val plain = String(sealer.open(blob))
        val s = json.decodeFromString<SealedSession>(plain)
        return Session(s.baseUrl, s.token, s.spkiPin, s.userName)
    }

    suspend fun clear() { context.dataStore.edit { it.remove(key) } }
    suspend fun exists(): Boolean = context.dataStore.data.first()[key] != null
}
```

- [ ] **Step 3: Write the failing use-case test** (drives the state machine against a fake API)

```kotlin
package de.ledgerline.app.domain.usecase

import de.ledgerline.app.domain.model.PairingFailure
import de.ledgerline.app.domain.model.PairingState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimAndPollPairingTest {
    @Test fun rejects_non_https_url() = runTest {
        val useCase = ClaimAndPollPairing(FakePairingGateway())
        val states = useCase.run(baseUrl = "http://insecure.example", code = "c", deviceName = "d").toList()
        assertTrue(states.last() is PairingState.Failed)
        assertEquals(PairingFailure.NOT_HTTPS, (states.last() as PairingState.Failed).reason)
    }

    @Test fun claims_then_polls_until_approved() = runTest {
        val gw = FakePairingGateway(pollSequence = listOf("pending", "pending", "approved"))
        val useCase = ClaimAndPollPairing(gw)
        val states = useCase.run("https://host.example", "code1", "Pixel").toList()
        assertTrue(states.any { it is PairingState.Claiming })
        assertTrue(states.any { it is PairingState.Polling })
        assertTrue(states.last() is PairingState.Approved)
        assertEquals("tok", (states.last() as PairingState.Approved).session.token)
    }

    @Test fun maps_410_to_consumed_or_expired() = runTest {
        val gw = FakePairingGateway(pollGone = true)
        val useCase = ClaimAndPollPairing(gw)
        val states = useCase.run("https://host.example", "code1", "Pixel").toList()
        assertEquals(PairingFailure.CONSUMED_OR_EXPIRED, (states.last() as PairingState.Failed).reason)
    }
}
```
The test needs a `PairingGateway` seam + a fake. Define both.

- [ ] **Step 4: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ClaimAndPollPairingTest*"`
Expected: FAIL — `ClaimAndPollPairing`, `PairingGateway`, `FakePairingGateway` undefined.

- [ ] **Step 5: Implement the gateway seam + use case `domain/usecase/ClaimAndPollPairing.kt`**

```kotlin
package de.ledgerline.app.domain.usecase

import de.ledgerline.app.domain.model.PairingFailure
import de.ledgerline.app.domain.model.PairingState
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Result of one poll, decoupled from Retrofit so the use case is pure/testable. */
sealed interface PollResult {
    data object Pending : PollResult
    data class Approved(val token: String, val spkiPin: String, val userName: String?) : PollResult
    data object Gone : PollResult
    data object RateLimited : PollResult
    data object NetworkError : PollResult
}

interface PairingGateway {
    suspend fun claim(baseUrl: String, code: String, deviceName: String): PollResult /* Pending on success */
    suspend fun poll(baseUrl: String, code: String): PollResult
}

class ClaimAndPollPairing(
    private val gateway: PairingGateway,
    private val pollIntervalMs: Long = 2000,
    private val maxPolls: Int = 60,
) {
    fun run(baseUrl: String, code: String, deviceName: String): Flow<PairingState> = flow {
        emit(PairingState.Idle)
        if (!baseUrl.startsWith("https://")) { emit(PairingState.Failed(PairingFailure.NOT_HTTPS)); return@flow }

        emit(PairingState.Claiming)
        when (gateway.claim(baseUrl, code, deviceName)) {
            is PollResult.NetworkError -> { emit(PairingState.Failed(PairingFailure.NETWORK)); return@flow }
            is PollResult.RateLimited -> { emit(PairingState.Failed(PairingFailure.RATE_LIMITED)); return@flow }
            is PollResult.Gone -> { emit(PairingState.Failed(PairingFailure.CONSUMED_OR_EXPIRED)); return@flow }
            else -> {} // Pending / Approved-not-expected: continue to poll
        }

        emit(PairingState.Polling)
        repeat(maxPolls) {
            when (val r = gateway.poll(baseUrl, code)) {
                is PollResult.Approved -> {
                    emit(PairingState.Approved(Session(baseUrl, r.token, r.spkiPin, r.userName))); return@flow
                }
                is PollResult.Gone -> { emit(PairingState.Failed(PairingFailure.CONSUMED_OR_EXPIRED)); return@flow }
                is PollResult.NetworkError -> { emit(PairingState.Failed(PairingFailure.NETWORK)); return@flow }
                is PollResult.RateLimited, is PollResult.Pending -> delay(pollIntervalMs)
            }
        }
        emit(PairingState.Failed(PairingFailure.CONSUMED_OR_EXPIRED))
    }
}
```

- [ ] **Step 6: Add the test fake** `app/src/test/java/de/ledgerline/app/domain/usecase/FakePairingGateway.kt`

```kotlin
package de.ledgerline.app.domain.usecase

class FakePairingGateway(
    private val pollSequence: List<String> = listOf("approved"),
    private val pollGone: Boolean = false,
) : PairingGateway {
    private var idx = 0
    override suspend fun claim(baseUrl: String, code: String, deviceName: String): PollResult = PollResult.Pending
    override suspend fun poll(baseUrl: String, code: String): PollResult {
        if (pollGone) return PollResult.Gone
        val step = pollSequence[idx.coerceAtMost(pollSequence.size - 1)]; idx++
        return when (step) {
            "approved" -> PollResult.Approved(token = "tok", spkiPin = "sha256/AAA", userName = "Malte")
            else -> PollResult.Pending
        }
    }
}
```

- [ ] **Step 7: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*ClaimAndPollPairingTest*"`
Expected: PASS (3 tests).

- [ ] **Step 8: Implement `data/PairingRepository.kt`** (real gateway backed by Retrofit; maps HTTP → PollResult, captures pin)

```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.PinnedTrust
import de.ledgerline.app.data.remote.dto.PairClaimRequest
import de.ledgerline.app.domain.usecase.PairingGateway
import de.ledgerline.app.domain.usecase.PollResult
import okhttp3.OkHttpClient
import java.net.HttpURLConnection

/**
 * Real pairing gateway. Because the pin is unknown until first contact, it opens
 * an unpinned (but still HTTPS + system-CA-validated) connection to claim/poll,
 * and captures the leaf SPKI from the TLS handshake to return as the pin.
 */
class PairingRepository : PairingGateway {

    private fun api(baseUrl: String) = NetworkFactory.create(baseUrl, tokenProvider = { null }, pin = null)

    override suspend fun claim(baseUrl: String, code: String, deviceName: String): PollResult {
        return try {
            val res = api(baseUrl).claimPair(PairClaimRequest(code, deviceName))
            when (res.code()) {
                HttpURLConnection.HTTP_OK, 202 -> PollResult.Pending
                HttpURLConnection.HTTP_GONE -> PollResult.Gone
                429 -> PollResult.RateLimited
                else -> PollResult.NetworkError
            }
        } catch (_: Exception) { PollResult.NetworkError }
    }

    override suspend fun poll(baseUrl: String, code: String): PollResult {
        return try {
            val res = api(baseUrl).pollPair(code)
            when {
                res.code() == HttpURLConnection.HTTP_GONE -> PollResult.Gone
                res.code() == 429 -> PollResult.RateLimited
                !res.isSuccessful -> PollResult.NetworkError
                res.body()?.status == "approved" && res.body()?.token != null ->
                    PollResult.Approved(res.body()!!.token!!, capturePin(baseUrl), res.body()!!.user?.name)
                else -> PollResult.Pending
            }
        } catch (_: Exception) { PollResult.NetworkError }
    }

    /** Opens one TLS connection and hashes the leaf cert's SPKI (TOFU). */
    private fun capturePin(baseUrl: String): String {
        val url = okhttp3.HttpUrl.get(baseUrl)
        val client = OkHttpClient()
        client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { resp ->
            val leaf = resp.handshake!!.peerCertificates.first() as java.security.cert.X509Certificate
            return PinnedTrust.spkiSha256Base64(leaf)
        }
    }
}
```
Note: `okhttp3.HttpUrl.get` → in OkHttp 4 use `baseUrl.toHttpUrl()`; adapt imports consistently with Task 5.

- [ ] **Step 9: Build + commit**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.
```bash
git add app/src/main/java/de/ledgerline/app/domain/ app/src/main/java/de/ledgerline/app/data/ app/src/test/
git commit -m "feat: add pairing state machine, session store, and repository"
```

---

## Task 7: Pairing UI (Compose + CameraX + ZXing scanner + deep link)

**Files:**
- Create: `ui/scan/QrScanner.kt`, `ui/pairing/PairingViewModel.kt`, `ui/pairing/PairingScreen.kt`
- Modify: `MainActivity.kt` (handle `ledgerline://pair` intent)

- [ ] **Step 1: Implement the ZXing analyzer `ui/scan/QrScanner.kt`** (no ML Kit)

```kotlin
package de.ledgerline.app.ui.scan

import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** CameraX ImageAnalysis analyzer decoding QR codes with ZXing (Apache-2, no GMS). */
class QrCodeAnalyzer(private val onResult: (String) -> Unit) : androidx.camera.core.ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    @Volatile private var done = false

    override fun analyze(image: ImageProxy) {
        if (done) { image.close(); return }
        val plane = image.planes[0]
        val data = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
        val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
        try {
            val text = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            if (text.startsWith("ledgerline://pair")) { done = true; onResult(text) }
        } catch (_: Exception) { /* no code this frame */ } finally { image.close() }
    }
}

/** Parses a ledgerline://pair deep link into (baseUrl, code) or null if invalid. */
fun parsePairLink(uri: String): Pair<String, String>? {
    val parsed = android.net.Uri.parse(uri)
    if (parsed.scheme != "ledgerline" || parsed.host != "pair") return null
    val url = parsed.getQueryParameter("url") ?: return null
    val code = parsed.getQueryParameter("code") ?: return null
    if (!url.startsWith("https://")) return null
    return url to code
}
```

- [ ] **Step 2: Write a unit test for `parsePairLink`** `app/src/test/java/de/ledgerline/app/ui/scan/ParsePairLinkTest.kt`

```kotlin
package de.ledgerline.app.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class ParsePairLinkTest {
    @Test fun parses_valid_link() {
        val r = parsePairLink("ledgerline://pair?url=https%3A%2F%2Fhome.example&code=abc123")
        assertEquals("https://home.example" to "abc123", r)
    }
    @Test fun rejects_http_url() {
        assertNull(parsePairLink("ledgerline://pair?url=http%3A%2F%2Fhome.example&code=abc"))
    }
    @Test fun rejects_wrong_scheme() {
        assertNull(parsePairLink("https://pair?url=https%3A%2F%2Fx&code=abc"))
    }
}
```
Add `testImplementation("org.robolectric:robolectric:4.14")` + `android { testOptions { unitTests.isIncludeAndroidResources = true } }` (android.net.Uri needs Robolectric).
Run: `./gradlew :app:testDebugUnitTest --tests "*ParsePairLinkTest*"` → after implementation, PASS. Run first to see it FAIL (unresolved reference).

- [ ] **Step 3: Implement `ui/pairing/PairingViewModel.kt`**

```kotlin
package de.ledgerline.app.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.data.PairingRepository
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.domain.model.PairingState
import de.ledgerline.app.domain.usecase.ClaimAndPollPairing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    fun startPairing(baseUrl: String, code: String, deviceName: String) {
        viewModelScope.launch {
            ClaimAndPollPairing(repository).run(baseUrl, code, deviceName).collect { s ->
                _state.value = s
                if (s is PairingState.Approved) sessionStore.save(s.session)
            }
        }
    }
}
```
Note: `SessionStore.save` uses the keystore sealer with `requireAuth=true`; the first save happens right after pairing while the user is present. If save throws (key needs auth), trigger `AppLock.authenticate` first in the screen before calling `startPairing`'s save — wire in Task 12's flow.

- [ ] **Step 4: Implement `ui/pairing/PairingScreen.kt`** (camera permission, preview, manual paste fallback)

```kotlin
package de.ledgerline.app.ui.pairing

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.PairingState
import de.ledgerline.app.ui.scan.QrCodeAnalyzer
import de.ledgerline.app.ui.scan.parsePairLink

@Composable
fun PairingScreen(vm: PairingViewModel = hiltViewModel(), onPaired: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    LaunchedEffect(Unit) { if (!hasCamera) permLauncher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(state) { if (state is PairingState.Approved) onPaired() }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        if (hasCamera) {
            CameraPreview { link ->
                parsePairLink(link)?.let { (url, code) -> vm.startPairing(url, code, Build.MODEL) }
            }
        } else {
            Text(stringResource(R.string.pairing_no_camera))
        }
        Spacer(Modifier.height(16.dp))
        when (val s = state) {
            is PairingState.Claiming, is PairingState.Polling -> { CircularProgressIndicator(); Text(stringResource(R.string.pairing_waiting)) }
            is PairingState.Failed -> Text(stringResource(R.string.pairing_failed, s.reason.name), color = MaterialTheme.colorScheme.error)
            else -> {}
        }
    }
}

@Composable
private fun CameraPreview(onQr: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx)
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = androidx.camera.core.ImageAnalysis.Builder()
                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx), QrCodeAnalyzer(onQr))
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        previewView
    }, modifier = Modifier.fillMaxWidth().height(320.dp))
}
```

- [ ] **Step 5: Add the pairing strings (both locales)** to `values/strings.xml` and `values-de/strings.xml`

`values/strings.xml` (append):
```xml
<string name="pairing_title">Connect device</string>
<string name="pairing_waiting">Waiting for approval in the web app…</string>
<string name="pairing_no_camera">Camera permission is required to scan the pairing code.</string>
<string name="pairing_failed">Pairing failed: %1$s</string>
```
`values-de/strings.xml` (create):
```xml
<resources>
    <string name="app_name">Ledgerline</string>
    <string name="pairing_title">Gerät verbinden</string>
    <string name="pairing_waiting">Warte auf Bestätigung in der Web-App…</string>
    <string name="pairing_no_camera">Kameraberechtigung wird zum Scannen des Kopplungscodes benötigt.</string>
    <string name="pairing_failed">Kopplung fehlgeschlagen: %1$s</string>
</resources>
```

- [ ] **Step 6: Handle the deep link in `MainActivity`** (add intent parsing)

Add to `MainActivity.onCreate` after `setContent` setup — pass the initial intent's data down. Minimal approach: read `intent?.data?.toString()` and expose it via a holder the nav reads. Concretely, add:
```kotlin
// in MainActivity, before setContent:
val pairLink = intent?.data?.takeIf { it.scheme == "ledgerline" && it.host == "pair" }?.toString()
setContent { LedgerlineTheme { AppNav(initialPairLink = pairLink) } }
```
(`AppNav` gains an `initialPairLink` param in Task 12.)

- [ ] **Step 7: Build + run the app on the emulator to smoke-test the screen**

Run: `./gradlew :app:installDebug` then launch. Simulate the deep link:
`adb shell am start -a android.intent.action.VIEW -d "ledgerline://pair?url=https%3A%2F%2Fhome.kiefer-networks.de&code=TESTCODE" de.ledgerline.app`
Expected: Pairing screen shows; with a real code + web approval it reaches `Approved`. (Against the live instance this performs a real claim/poll.)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/ui/ app/src/main/res/ app/src/main/java/de/ledgerline/app/MainActivity.kt app/build.gradle.kts gradle/libs.versions.toml
git commit -m "feat: add QR pairing UI with ZXing scanner and deep-link handling"
```

---

## Task 8: Vault unlock (Argon2id → VK), in-memory holder, lifecycle wipe

**Files:**
- Create: `core/security/VaultKeyHolder.kt`, `data/VaultRepository.kt`, `domain/usecase/UnlockVault.kt`
- Test: `app/src/test/java/de/ledgerline/app/domain/usecase/UnlockVaultTest.kt`

- [ ] **Step 1: Implement `core/security/VaultKeyHolder.kt`** (memory-only, zeroing)

```kotlin
package de.ledgerline.app.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Holds the Vault Key in memory only. Never persisted. Wiped on lock/idle/background. */
@Singleton
class VaultKeyHolder @Inject constructor() {
    @Volatile private var vk: ByteArray? = null
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    fun set(key: ByteArray) { vk = key; _unlocked.value = true }
    fun get(): ByteArray? = vk
    fun wipe() {
        vk?.fill(0)          // overwrite key bytes before releasing
        vk = null
        _unlocked.value = false
    }
}
```

- [ ] **Step 2: Write the failing use-case test** (fake Crypto + fake vault gateway)

```kotlin
package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.crypto.Crypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockVaultTest {
    // Fake crypto: KEK = passphrase bytes; secretBoxOpen returns the "vk" only when key == "rightKEK".
    private val fakeCrypto = object : Crypto {
        override fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long) = passphrase
        override fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? =
            if (String(key) == "rightKEK") ByteArray(32) { 9 } else null
        override fun genericHash32(input: ByteArray) = ByteArray(32) { 1 }
        override fun b64decode(s: String) = s.toByteArray()
        override fun b64encode(b: ByteArray) = String(b)
        override fun fromHex(s: String) = s.toByteArray()
    }

    private fun gateway(configured: Boolean = true) = object : VaultGateway {
        override suspend fun fetch() = if (configured)
            VaultParams(configured = true, salt = "s", kdfOps = 2, kdfMem = 1, wrappedVk = "w", wrapNonce = "n",
                hasRecovery = true, wrappedVkRecovery = "wr", recoveryNonce = "rn")
        else VaultParams(configured = false)
    }

    @Test fun wrong_passphrase_maps_to_error() = runTest {
        val vk = de.ledgerline.app.core.security.VaultKeyHolder()
        val res = UnlockVault(fakeCrypto, vk).withPassphrase(gateway(), "wrongKEK".toByteArray())
        assertTrue(res is Outcome.Err && res.kind == ErrorKind.WRONG_PASSPHRASE)
        assertEquals(false, vk.unlocked.value)
    }

    @Test fun correct_passphrase_sets_vk() = runTest {
        val vk = de.ledgerline.app.core.security.VaultKeyHolder()
        val res = UnlockVault(fakeCrypto, vk).withPassphrase(gateway(), "rightKEK".toByteArray())
        assertTrue(res is Outcome.Ok)
        assertEquals(true, vk.unlocked.value)
    }

    @Test fun not_configured_maps_to_error() = runTest {
        val vk = de.ledgerline.app.core.security.VaultKeyHolder()
        val res = UnlockVault(fakeCrypto, vk).withPassphrase(gateway(configured = false), "x".toByteArray())
        assertTrue(res is Outcome.Err && res.kind == ErrorKind.NOT_CONFIGURED)
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*UnlockVaultTest*"`
Expected: FAIL — `UnlockVault`, `VaultGateway`, `VaultParams` undefined.

- [ ] **Step 4: Implement the gateway seam + use case `domain/usecase/UnlockVault.kt`**

```kotlin
package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder

data class VaultParams(
    val configured: Boolean,
    val salt: String? = null,
    val kdfOps: Long? = null,
    val kdfMem: Long? = null,
    val wrappedVk: String? = null,
    val wrapNonce: String? = null,
    val hasRecovery: Boolean = false,
    val wrappedVkRecovery: String? = null,
    val recoveryNonce: String? = null,
)

interface VaultGateway { suspend fun fetch(): VaultParams }

/** Derives the KEK (Argon2id) with server params and unwraps the Vault Key. */
class UnlockVault(private val crypto: Crypto, private val holder: VaultKeyHolder) {

    suspend fun withPassphrase(gateway: VaultGateway, passphrase: ByteArray): Outcome<Unit> {
        val v = try { gateway.fetch() } catch (e: Exception) { return Outcome.Err(ErrorKind.NETWORK, e) }
        if (!v.configured) return Outcome.Err(ErrorKind.NOT_CONFIGURED)
        return try {
            val kek = crypto.deriveKek(passphrase, crypto.b64decode(v.salt!!), v.kdfOps!!, v.kdfMem!!)
            val vk = crypto.secretBoxOpen(crypto.b64decode(v.wrappedVk!!), crypto.b64decode(v.wrapNonce!!), kek)
                ?: return Outcome.Err(ErrorKind.WRONG_PASSPHRASE)
            kek.fill(0)
            holder.set(vk)
            Outcome.Ok(Unit)
        } catch (e: Exception) { Outcome.Err(ErrorKind.DECRYPT, e) }
        finally { passphrase.fill(0) }
    }

    suspend fun withRecoveryCode(gateway: VaultGateway, hexCode: String): Outcome<Unit> {
        val v = try { gateway.fetch() } catch (e: Exception) { return Outcome.Err(ErrorKind.NETWORK, e) }
        if (!v.configured || !v.hasRecovery) return Outcome.Err(ErrorKind.NOT_CONFIGURED)
        return try {
            val recoveryKey = crypto.genericHash32(crypto.fromHex(hexCode))
            val vk = crypto.secretBoxOpen(crypto.b64decode(v.wrappedVkRecovery!!), crypto.b64decode(v.recoveryNonce!!), recoveryKey)
                ?: return Outcome.Err(ErrorKind.WRONG_PASSPHRASE)
            holder.set(vk)
            Outcome.Ok(Unit)
        } catch (e: Exception) { Outcome.Err(ErrorKind.DECRYPT, e) }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*UnlockVaultTest*"`
Expected: PASS (3 tests).

- [ ] **Step 6: Implement `data/VaultRepository.kt`** (real gateway over Retrofit, pinned)

```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.VaultGateway
import de.ledgerline.app.domain.usecase.VaultParams

/** Fetches vault KDF params over the pinned, authenticated session. */
class VaultRepository(private val session: Session) : VaultGateway {
    private val api = NetworkFactory.create(session.baseUrl, tokenProvider = { session.token }, pin = session.spkiPin)

    override suspend fun fetch(): VaultParams {
        val res = api.vault()
        val b = res.body() ?: return VaultParams(configured = false)
        return VaultParams(
            configured = b.configured,
            salt = b.salt, kdfOps = b.kdf_ops, kdfMem = b.kdf_mem,
            wrappedVk = b.wrapped_vault_key, wrapNonce = b.wrap_nonce,
            hasRecovery = b.has_recovery,
            wrappedVkRecovery = b.wrapped_vault_key_recovery, recoveryNonce = b.recovery_nonce,
        )
    }
}
```

- [ ] **Step 7: Build + commit**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.
```bash
git add app/src/main/java/de/ledgerline/app/core/security/VaultKeyHolder.kt app/src/main/java/de/ledgerline/app/domain/usecase/UnlockVault.kt app/src/main/java/de/ledgerline/app/data/VaultRepository.kt app/src/test/
git commit -m "feat: add vault unlock use case with in-memory key holder"
```

---

## Task 9: Vault unlock UI + idle/background auto-lock

**Files:**
- Create: `ui/unlock/UnlockViewModel.kt`, `ui/unlock/UnlockScreen.kt`, `core/security/IdleLocker.kt`
- Modify: `MainActivity.kt` (observe lifecycle → wipe VK)

- [ ] **Step 1: Implement `core/security/IdleLocker.kt`**

```kotlin
package de.ledgerline.app.core.security

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/** Tracks last-interaction time; decides when the VK must be wiped for inactivity. */
@Singleton
class IdleLocker @Inject constructor() {
    @Volatile var timeoutMs: Long = 5 * 60 * 1000  // configurable in settings later
    @Volatile private var lastActive = SystemClock.elapsedRealtime()

    fun touch() { lastActive = SystemClock.elapsedRealtime() }
    fun isExpired(): Boolean = SystemClock.elapsedRealtime() - lastActive >= timeoutMs
}
```

- [ ] **Step 2: Implement `ui/unlock/UnlockViewModel.kt`**

```kotlin
package de.ledgerline.app.ui.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.data.VaultRepository
import de.ledgerline.app.domain.usecase.UnlockVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface UnlockUiState {
    data object Idle : UnlockUiState
    data object Working : UnlockUiState
    data object Unlocked : UnlockUiState
    data object NotConfigured : UnlockUiState
    data class Error(val message: String) : UnlockUiState
}

@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val crypto: Crypto,
    private val holder: VaultKeyHolder,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow<UnlockUiState>(UnlockUiState.Idle)
    val state: StateFlow<UnlockUiState> = _state

    fun unlock(passphrase: CharArray) {
        viewModelScope.launch {
            _state.value = UnlockUiState.Working
            val session = sessionStore.load() ?: run { _state.value = UnlockUiState.Error("no session"); return@launch }
            val bytes = charsToUtf8(passphrase)
            val result = withContext(Dispatchers.Default) { // Argon2id is CPU-heavy
                UnlockVault(crypto, holder).withPassphrase(VaultRepository(session), bytes)
            }
            _state.value = when (result) {
                is Outcome.Ok -> UnlockUiState.Unlocked
                is Outcome.Err -> when (result.kind) {
                    de.ledgerline.app.core.ErrorKind.WRONG_PASSPHRASE -> UnlockUiState.Error("wrong")
                    de.ledgerline.app.core.ErrorKind.NOT_CONFIGURED -> UnlockUiState.NotConfigured
                    else -> UnlockUiState.Error(result.kind.name)
                }
            }
            passphrase.fill(' ')
        }
    }

    private fun charsToUtf8(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(chars))
        return ByteArray(bb.remaining()).also { bb.get(it) }
    }
}
```

- [ ] **Step 3: Implement `ui/unlock/UnlockScreen.kt`**

```kotlin
package de.ledgerline.app.ui.unlock

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R

@Composable
fun UnlockScreen(vm: UnlockViewModel = hiltViewModel(), onUnlocked: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var passphrase by remember { mutableStateOf("") }

    LaunchedEffect(state) { if (state is UnlockUiState.Unlocked) onUnlocked() }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text(stringResource(R.string.unlock_passphrase)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.unlock(passphrase.toCharArray()); passphrase = "" }, enabled = state != UnlockUiState.Working) {
            Text(stringResource(R.string.unlock_button))
        }
        when (val s = state) {
            is UnlockUiState.Working -> { Spacer(Modifier.height(12.dp)); CircularProgressIndicator() }
            is UnlockUiState.NotConfigured -> Text(stringResource(R.string.unlock_not_configured), color = MaterialTheme.colorScheme.error)
            is UnlockUiState.Error -> Text(stringResource(R.string.unlock_error), color = MaterialTheme.colorScheme.error)
            else -> {}
        }
    }
}
```

- [ ] **Step 4: Add strings (both locales)**

`values/strings.xml` (append):
```xml
<string name="unlock_title">Unlock vault</string>
<string name="unlock_passphrase">Passphrase</string>
<string name="unlock_button">Unlock</string>
<string name="unlock_not_configured">No vault yet. Set it up in the web app first.</string>
<string name="unlock_error">Wrong passphrase or unable to unlock.</string>
```
`values-de/strings.xml` (append):
```xml
<string name="unlock_title">Tresor entsperren</string>
<string name="unlock_passphrase">Passphrase</string>
<string name="unlock_button">Entsperren</string>
<string name="unlock_not_configured">Noch kein Tresor. Bitte zuerst in der Web-App einrichten.</string>
<string name="unlock_error">Falsche Passphrase oder Entsperren nicht möglich.</string>
```

- [ ] **Step 5: Wire lifecycle wipe in `MainActivity`** (VK wiped on ON_STOP)

Add a `DefaultLifecycleObserver` in `MainActivity` that injects `VaultKeyHolder` + `IdleLocker`:
```kotlin
// fields (Hilt field injection):
@Inject lateinit var vaultKeyHolder: de.ledgerline.app.core.security.VaultKeyHolder
@Inject lateinit var idleLocker: de.ledgerline.app.core.security.IdleLocker

// in onCreate:
lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { vaultKeyHolder.wipe() }   // background → wipe VK
    override fun onResume(owner: androidx.lifecycle.LifecycleOwner) { if (idleLocker.isExpired()) vaultKeyHolder.wipe() else idleLocker.touch() }
})
```
Requires `MainActivity` annotated `@AndroidEntryPoint` (already) and field injection allowed.

- [ ] **Step 6: Build + commit**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.
```bash
git add app/src/main/java/de/ledgerline/app/ui/unlock/ app/src/main/java/de/ledgerline/app/core/security/IdleLocker.kt app/src/main/res/ app/src/main/java/de/ledgerline/app/MainActivity.kt
git commit -m "feat: add vault unlock screen with background/idle key wipe"
```

---

## Task 10: Hilt wiring (DI modules)

**Files:**
- Create: `di/CryptoModule.kt`, `di/AppModule.kt`

- [ ] **Step 1: Implement `di/CryptoModule.kt`**

```kotlin
package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.crypto.SodiumCrypto

@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {
    @Binds abstract fun bindCrypto(impl: SodiumCrypto): Crypto
}
```

- [ ] **Step 2: Implement `di/AppModule.kt`**

```kotlin
package de.ledgerline.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.data.PairingRepository
import de.ledgerline.app.data.SessionStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun keystoreSealer(): KeystoreSealer = KeystoreSealer()
    @Provides @Singleton fun sessionStore(@ApplicationContext ctx: Context, sealer: KeystoreSealer) = SessionStore(ctx, sealer)
    @Provides @Singleton fun pairingRepository() = PairingRepository()
}
```

- [ ] **Step 3: Build to verify Hilt graph resolves**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (Hilt processes all `@Inject`/`@Binds`/`@Provides`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/di/
git commit -m "feat: wire Hilt modules for crypto, session, and repositories"
```

---

## Task 11: Root navigation + flow gating (assemble the app)

**Files:**
- Modify: `ui/nav/AppNav.kt`, `MainActivity.kt`

- [ ] **Step 1: Implement `ui/nav/AppNav.kt`** (decides pairing → lock → unlock → home)

```kotlin
package de.ledgerline.app.ui.nav

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.ui.pairing.PairingScreen
import de.ledgerline.app.ui.unlock.UnlockScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Destination { LOADING, PAIRING, UNLOCK, HOME }

@HiltViewModel
class RootViewModel @Inject constructor(private val sessionStore: SessionStore) : ViewModel() {
    private val _dest = MutableStateFlow(Destination.LOADING)
    val dest: StateFlow<Destination> = _dest
    init { viewModelScope.launch { _dest.value = if (sessionStore.exists()) Destination.UNLOCK else Destination.PAIRING } }
    fun toUnlock() { _dest.value = Destination.UNLOCK }
    fun toHome() { _dest.value = Destination.HOME }
}

@Composable
fun AppNav(initialPairLink: String? = null, vm: RootViewModel = hiltViewModel()) {
    val dest by vm.dest.collectAsState()
    when (dest) {
        Destination.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        Destination.PAIRING -> PairingScreen(onPaired = { vm.toUnlock() })
        Destination.UNLOCK -> UnlockScreen(onUnlocked = { vm.toHome() })
        Destination.HOME -> HomePlaceholder()
    }
}

@Composable
private fun HomePlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text("Vault unlocked — Phase 2 starts here.")
    }
}
```
Note: App-lock (BiometricPrompt) gates `SessionStore.load()` because the keystore key requires auth. Invoke `AppLock.authenticate(activity, …)` from the `UnlockScreen`/`RootViewModel` entry before the first `sessionStore.load()`. For Phase 1, place the biometric prompt at the transition into `UNLOCK` (before loading the session): call it in `MainActivity` via a launched effect and only render `AppNav` once auth succeeds. Keep the biometric call in `MainActivity` where a `FragmentActivity` is available.

- [ ] **Step 2: Add the app-lock gate in `MainActivity`**

```kotlin
// MainActivity: gate content behind biometric when a session exists.
@Inject lateinit var sessionStore: de.ledgerline.app.data.SessionStore
private val appLock = de.ledgerline.app.core.security.AppLock()

// inside setContent, wrap AppNav:
var authed by remember { mutableStateOf(false) }
LaunchedEffect(Unit) {
    val needsLock = sessionStore.exists()
    authed = if (!needsLock) true
    else appLock.authenticate(this@MainActivity,
        getString(R.string.lock_title), getString(R.string.lock_subtitle)) is de.ledgerline.app.core.security.LockResult.Success
}
if (authed) AppNav(initialPairLink = pairLink)
else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(getString(R.string.lock_locked)) }
```
Add strings `lock_title`/`lock_subtitle`/`lock_locked` to both locales:
`values/strings.xml`:
```xml
<string name="lock_title">Unlock Ledgerline</string>
<string name="lock_subtitle">Authenticate to continue</string>
<string name="lock_locked">Locked. Authenticate to continue.</string>
```
`values-de/strings.xml`:
```xml
<string name="lock_title">Ledgerline entsperren</string>
<string name="lock_subtitle">Zum Fortfahren authentifizieren</string>
<string name="lock_locked">Gesperrt. Zum Fortfahren authentifizieren.</string>
```
Note: `MainActivity` must be a `FragmentActivity` for `BiometricPrompt`. `ComponentActivity` is not a `FragmentActivity`; change `class MainActivity : ComponentActivity()` to `class MainActivity : androidx.fragment.app.FragmentActivity()`. Add `implementation("androidx.fragment:fragment-ktx:1.8.5")` if not transitively present.

- [ ] **Step 3: Build + install + manual end-to-end smoke on emulator**

Run: `./gradlew :app:installDebug`
Manual: fresh install → Pairing screen (no session). After a real pairing + web approval → biometric prompt on next launch → Unlock screen → enter passphrase → Home placeholder.
(Emulator biometrics: enroll a fingerprint via `adb -e emu finger touch 1` after enrolling in Settings.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/ui/nav/ app/src/main/java/de/ledgerline/app/MainActivity.kt app/src/main/res/ app/build.gradle.kts gradle/libs.versions.toml
git commit -m "feat: assemble app flow (pairing → app lock → vault unlock)"
```

---

## Task 12: Final hardening pass + README + branch finish

**Files:**
- Create: `README.md`, `SECURITY.md`
- Verify: manifest flags, cleartext config, log stripping

- [ ] **Step 1: Grep for accidental secret logging**

Run: `grep -rnE 'Log\.(d|v|i|w|e)\(.*(token|passphrase|vk|vault|kek|recovery)' app/src/main || echo CLEAN`
Expected: `CLEAN`. If any hits, remove them.

- [ ] **Step 2: Verify hardening invariants**

Run: `grep -q 'allowBackup="false"' app/src/main/AndroidManifest.xml && grep -q 'FLAG_SECURE' app/src/main/java/de/ledgerline/app/MainActivity.kt && grep -q 'cleartextTrafficPermitted="false"' app/src/main/res/xml/network_security_config.xml && echo HARDENING_OK`
Expected: `HARDENING_OK`.

- [ ] **Step 3: Run the whole unit-test suite + a release build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleRelease`
Expected: all tests PASS; release APK builds (R8 strips logs). (Release signing config is out of scope for Phase 1 — an unsigned/`debug`-signed release build is acceptable to validate R8.)

- [ ] **Step 4: Write `README.md`** (English; setup, architecture, security model, build)

Include: project purpose, zero-knowledge model summary, Phase-1 scope, build prerequisites (JDK 21, API-36 SDK/emulator), `./gradlew :app:assembleDebug`, how pairing works, and the explicit note that lazysodium ships a prebuilt `.so` (documented deviation from full F-Droid reproducibility).

- [ ] **Step 5: Write `SECURITY.md`** (threat model + data handling for GDPR transparency)

Include: what is stored (sealed token/baseUrl/SPKI-pin only, AES-256-GCM keystore-gated), what is never stored (VK, passphrase, recovery code — memory only), transport (TLS 1.2+/TOFU SPKI pin), app-lock model, and how to revoke (delete device in web profile → re-pair).

- [ ] **Step 6: Commit + finish the feature branch**

```bash
git add README.md SECURITY.md
git commit -m "docs: add README and security model documentation"
```
Then invoke the `superpowers:finishing-a-development-branch` skill to merge `feature/phase1-*` work into `develop` and decide on tagging `v0.1.0`.

---

## Self-Review Notes (author checklist — completed)

- **Spec coverage:** pairing (T6/T7), token storage own-keystore (T3), app-lock biometric+credential (T4/T11), vault unlock incl. recovery (T8/T9), never-persist VK + idle/background wipe (T8/T9), TOFU SPKI pin (T5/T6), TLS-only + cleartext off (T1/T5), FLAG_SECURE + backup off (T1/T12), i18n DE+EN (T1/T7/T9/T11), Hilt/Retrofit/OkHttp (T1/T5/T10), ML-Kit→ZXing override (T7), JDK/emulator blockers (T0). All spec sections map to a task.
- **Placeholder scan:** no TBD/TODO; every code step contains literal code. `HomePlaceholder` is an intentional Phase-2 boundary marker, not a plan placeholder.
- **Type consistency:** `Crypto`, `PollResult`, `PairingGateway`, `VaultGateway`, `VaultParams`, `Session`, `PairingState`, `VaultKeyHolder`, `KeystoreSealer` signatures are identical across the tasks that define and consume them.
- **Known implementation risks flagged inline:** exact lazysodium `cryptoPwHash`/`cryptoSecretBox*` overloads for v5.1.0; OkHttp `HttpUrl.get` vs `toHttpUrl()`; `ComponentActivity`→`FragmentActivity` for BiometricPrompt; `okhttp-tls`/`robolectric` test deps to add. Each has a concrete resolution note.
```
