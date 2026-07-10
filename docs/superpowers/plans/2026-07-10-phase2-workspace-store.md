# Ledgerline Android — Phase 2 Implementation Plan (Workspace Store, read-only)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After vault unlock, load and decrypt the workspace manifest on-device and render Files, Notes, Bookmarks, and Todos read-only under a bottom-nav, with pull-to-refresh.

**Architecture:** Add an `openManifest` primitive to the existing `Crypto` layer. A `WorkspaceRepository` fetches `GET /api/v1/store`, decrypts with the in-memory Vault Key, and parses a tolerant kotlinx.serialization manifest. A `@Singleton WorkspaceCache` (StateFlow) is shared by four Hilt tab ViewModels. The session (baseUrl/token/pin), captured at unlock, lives in an in-memory `SessionHolder` cleared alongside the Vault Key.

**Tech Stack:** Kotlin, Compose (Material 3, `PullToRefreshBox`), Hilt, Retrofit/OkHttp, kotlinx.serialization, lazysodium. Reuses all Phase-1 infrastructure.

**Reference:** Spec `docs/superpowers/specs/2026-07-10-phase2-workspace-store-design.md`. Crypto ground truth `~/Entwicklung/ledgerline/resources/js/vault.js` (`openManifest`). Manifest shape `~/Entwicklung/ledgerline/resources/js/app.js` (`LLStore`).

**Conventions:** English only. Conventional Commits. Work on `feature/phase2` off `develop`. Physical device id `62021JEBF09273` (target it explicitly with `adb -s`). JVM unit tests: `./gradlew :app:testDebugUnitTest`. Instrumented: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`. Keep FLAG_SECURE and all Phase-1 hardening intact.

---

## Preflight

- [ ] **Create the feature branch**

```bash
cd /Users/malte.kiefer/Entwicklung/ledgerline-android
git checkout develop && git checkout -b feature/phase2
```

---

## File Structure

```
core/crypto/Crypto.kt                 (+openManifest)
core/crypto/SodiumCrypto.kt           (+openManifest impl)
core/SessionHolder.kt                 (new — in-memory Session after unlock)
core/WorkspaceCache.kt                (new — shared decrypted manifest)
data/remote/dto/StoreDtos.kt          (new)
data/remote/LedgerlineApi.kt          (+store())
domain/model/Workspace.kt             (new — manifest models + Workspace wrapper)
domain/usecase/LoadWorkspace.kt       (new)
data/WorkspaceRepository.kt           (new)
di/AppModule.kt                       (+SessionHolder, WorkspaceCache, repo, usecase provider)
ui/unlock/UnlockViewModel.kt          (set SessionHolder on success)
MainActivity.kt                       (clear SessionHolder + cache on wipe)
ui/nav/AppNav.kt                      (HOME -> WorkspaceScaffold; lock observe)
ui/workspace/WorkspaceScaffold.kt     (new — bottom nav)
ui/workspace/common/Format.kt         (new — size formatter)
ui/workspace/common/States.kt         (new — EmptyState, ErrorState, RefreshableList)
ui/workspace/files/FilesViewModel.kt, FilesScreen.kt   (new)
ui/workspace/notes/NotesViewModel.kt, NotesScreen.kt, NoteDetailScreen.kt, Markdown.kt   (new)
ui/workspace/bookmarks/BookmarksViewModel.kt, BookmarksScreen.kt   (new)
ui/workspace/todos/TodosViewModel.kt, TodosScreen.kt   (new)
res/values/strings.xml, res/values-de/strings.xml     (+workspace strings)
```

---

## Task 1: `openManifest` crypto primitive

**Files:** Modify `core/crypto/Crypto.kt`, `core/crypto/SodiumCrypto.kt`. Test `app/src/androidTest/java/de/ledgerline/app/core/crypto/OpenManifestTest.kt`.

- [ ] **Step 1: Add to the `Crypto` interface**

In `core/crypto/Crypto.kt`, add:
```kotlin
    /**
     * Decrypt a sealed manifest string `{"c":...,"n":...}` with the vault key.
     * Returns the plaintext JSON (trailing 4-KiB whitespace padding intact; the
     * JSON parser ignores it), or null if decryption fails.
     */
    fun openManifest(ciphertext: String, vk: ByteArray): String?
```

- [ ] **Step 2: Write the failing instrumented test**

`app/src/androidTest/java/de/ledgerline/app/core/crypto/OpenManifestTest.kt`:
```kotlin
package de.ledgerline.app.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenManifestTest {
    private val crypto = SodiumCrypto()

    @Test fun openManifest_recovers_padded_json_and_rejects_wrong_key() {
        val vk = ByteArray(32) { (it * 3).toByte() }
        val nonce = ByteArray(24) { (it + 1).toByte() }
        // Build a sealed manifest exactly like vault.js: seal(utf8(json+padding), vk)
        val json = """{"v":1,"notes":[]}"""
        val padded = json + " ".repeat(4096 - json.length)   // 4-KiB bucket
        val cipher = crypto.secretBoxSealForTest(padded.toByteArray(), nonce, vk)
        val sealed = """{"c":"${crypto.b64encode(cipher)}","n":"${crypto.b64encode(nonce)}"}"""

        val out = crypto.openManifest(sealed, vk)
        assertTrue(out!!.startsWith("""{"v":1,"notes":[]}"""))
        assertEquals(padded, out)                              // padding preserved

        val wrong = vk.copyOf().also { it[0]++ }
        assertNull(crypto.openManifest(sealed, wrong))
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.ledgerline.app.core.crypto.OpenManifestTest`
Expected: FAIL — `openManifest` not implemented.

- [ ] **Step 4: Implement in `SodiumCrypto`**

Add these imports at the top of `SodiumCrypto.kt`:
```kotlin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
```
Add a private `Json` and the method inside the class:
```kotlin
    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    override fun openManifest(ciphertext: String, vk: ByteArray): String? {
        return try {
            val env = lenientJson.parseToJsonElement(ciphertext) as JsonObject
            val c = env["c"]!!.jsonPrimitive.content
            val n = env["n"]!!.jsonPrimitive.content
            val plain = secretBoxOpen(b64decode(c), b64decode(n), vk) ?: return null
            String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.ledgerline.app.core.crypto.OpenManifestTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/de/ledgerline/app/core/crypto app/src/androidTest
git commit -m "feat: add openManifest primitive to decrypt sealed workspace manifests"
```

---

## Task 2: In-memory `SessionHolder` (session survives unlock, dies with the key)

**Files:** Create `core/SessionHolder.kt`. Modify `ui/unlock/UnlockViewModel.kt`, `MainActivity.kt`. Test `app/src/test/java/de/ledgerline/app/core/SessionHolderTest.kt`.

- [ ] **Step 1: Write the failing unit test**

```kotlin
package de.ledgerline.app.core

import de.ledgerline.app.domain.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionHolderTest {
    @Test fun set_get_clear() {
        val h = SessionHolder()
        assertNull(h.get())
        val s = Session("https://h.example", "tok", "sha256/AAA", "Malte")
        h.set(s)
        assertEquals(s, h.get())
        h.clear()
        assertNull(h.get())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SessionHolderTest*"`
Expected: FAIL — `SessionHolder` undefined.

- [ ] **Step 3: Implement `core/SessionHolder.kt`**

```kotlin
package de.ledgerline.app.core

import de.ledgerline.app.domain.model.Session
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the paired Session (baseUrl/token/SPKI pin) in memory after a successful
 * unlock, so authenticated API calls don't re-prompt biometric on every request.
 * Cleared alongside the Vault Key (background/idle lock).
 */
@Singleton
class SessionHolder @Inject constructor() {
    @Volatile private var session: Session? = null
    fun set(s: Session) { session = s }
    fun get(): Session? = session
    fun clear() { session = null }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SessionHolderTest*"`
Expected: PASS.

- [ ] **Step 5: Populate it on unlock**

In `ui/unlock/UnlockViewModel.kt`, inject `SessionHolder` and store the session after a successful load. Change the constructor to add `private val sessionHolder: SessionHolder` and, in `unlock(...)`, right after `val session = sessionStore.load() ?: ...`, add:
```kotlin
            sessionHolder.set(session)
```
(Place it immediately after the non-null `session` is obtained, before deriving the KEK.)

- [ ] **Step 6: Clear it when the Vault Key is wiped**

In `MainActivity.kt`, inject `SessionHolder` and clear it in both wipe paths. Add the field:
```kotlin
    @Inject lateinit var sessionHolder: de.ledgerline.app.core.SessionHolder
```
In the `DefaultLifecycleObserver`, update `onStop` and the idle branch of `onResume`:
```kotlin
            override fun onStop(owner: LifecycleOwner) {
                vaultKeyHolder.wipe()
                sessionHolder.clear()
            }
            override fun onResume(owner: LifecycleOwner) {
                if (idleLocker.isExpired()) { vaultKeyHolder.wipe(); sessionHolder.clear() } else idleLocker.touch()
            }
```

- [ ] **Step 7: Build + commit**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
```bash
git add app/src/main/java/de/ledgerline/app/core/SessionHolder.kt app/src/main/java/de/ledgerline/app/ui/unlock/UnlockViewModel.kt app/src/main/java/de/ledgerline/app/MainActivity.kt app/src/test
git commit -m "feat: keep paired session in memory after unlock, cleared on lock"
```

---

## Task 3: Store DTO, manifest models, repository, cache, DI

**Files:** Create `data/remote/dto/StoreDtos.kt`, `domain/model/Workspace.kt`, `data/WorkspaceRepository.kt`, `domain/usecase/LoadWorkspace.kt`, `core/WorkspaceCache.kt`. Modify `data/remote/LedgerlineApi.kt`, `di/AppModule.kt`. Tests `app/src/test/java/de/ledgerline/app/domain/model/WorkspaceParsingTest.kt`.

- [ ] **Step 1: Add the store endpoint to `LedgerlineApi.kt`**

Add import `import de.ledgerline.app.data.remote.dto.StoreResponse` and the method:
```kotlin
    @GET("api/v1/store")
    suspend fun store(): Response<StoreResponse>
```

- [ ] **Step 2: Create `data/remote/dto/StoreDtos.kt`**

```kotlin
package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class StoreResponse(val ciphertext: String? = null, val version: Int = 0)
```

- [ ] **Step 3: Create `domain/model/Workspace.kt`** (tolerant models)

```kotlin
package de.ledgerline.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceManifest(
    val v: Int = 1,
    val notes: List<Note> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val bookmarkFolders: List<NamedFolder> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val todoLists: List<TodoList> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val fileFolders: List<NamedFolder> = emptyList(),
)

@Serializable
data class Note(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val pinned: Boolean = false,
    val trashed: Boolean = false,
    val updated: String? = null,
)

@Serializable
data class Bookmark(
    val id: String = "",
    val folderId: String? = null,
    val title: String = "",
    val url: String = "",
    val description: String = "",
    val favorite: Boolean = false,
    val readLater: Boolean = false,
    val trashed: Boolean = false,
)

@Serializable
data class NamedFolder(val id: String = "", val name: String = "", val parent: String? = null)

@Serializable
data class TodoList(val id: String = "", val name: String = "")

@Serializable
data class TodoItem(
    val id: String = "",
    val listId: String? = null,
    val title: String = "",
    val description: String = "",
    val url: String = "",
    val priority: String = "normal",
    val marked: Boolean = false,
    val due: String = "",
    val done: Boolean = false,
    val trashed: Boolean = false,
)

@Serializable
data class FileEntry(
    val id: String = "",
    val blob: String = "",
    val encFileKey: String = "",
    val name: String = "",
    val mime: String = "",
    val size: Long = 0,
    val folder: String? = null,
    val created: String? = null,
    val trashed: Boolean = false,
)

/** The decrypted manifest plus the server version (kept for Phase-3 writes). */
data class Workspace(val manifest: WorkspaceManifest, val version: Int)
```

- [ ] **Step 4: Write the failing parsing test**

`app/src/test/java/de/ledgerline/app/domain/model/WorkspaceParsingTest.kt`:
```kotlin
package de.ledgerline.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parses_manifest_with_unknown_and_missing_fields() {
        // Unknown top-level ("gadgets") and unknown note field ("color") must not throw;
        // missing optional fields must default.
        val text = """
          {"v":1,"gadgets":[1,2],
           "notes":[{"id":"n1","title":"Hi","content":"# H","pinned":true,"color":"red"}],
           "files":[{"id":"f1","blob":"b1","encFileKey":"{}","name":"a.txt","size":12}],
           "fileFolders":[{"id":"d1","name":"Docs"}]}
        """.trimIndent()
        val m = json.decodeFromString<WorkspaceManifest>(text)
        assertEquals(1, m.notes.size)
        assertTrue(m.notes[0].pinned)
        assertEquals("Docs", m.fileFolders[0].name)
        assertEquals(12L, m.files[0].size)
        assertEquals(0, m.bookmarks.size) // missing key defaults to empty
    }
}
```

- [ ] **Step 5: Run to verify it fails, then passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkspaceParsingTest*"`
Expected: first FAIL (models absent) → after Steps 3 exist, PASS. Re-run to confirm PASS.

- [ ] **Step 6: Create `core/WorkspaceCache.kt`**

```kotlin
package de.ledgerline.app.core

import de.ledgerline.app.domain.model.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Shares the last-loaded decrypted workspace across the four tab ViewModels. */
@Singleton
class WorkspaceCache @Inject constructor() {
    private val _value = MutableStateFlow<Workspace?>(null)
    val value: StateFlow<Workspace?> = _value
    fun set(w: Workspace) { _value.value = w }
    fun clear() { _value.value = null }
}
```

- [ ] **Step 7: Create `data/WorkspaceRepository.kt`**

```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/** Loads + decrypts the workspace manifest over the pinned, authenticated session. */
@Singleton
class WorkspaceRepository @Inject constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Outcome<Workspace> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)  // not unlocked/paired
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)   // locked
        val api = NetworkFactory.create(session.baseUrl, tokenProvider = { session.token }, pin = session.spkiPin)
        return try {
            val res = api.store()
            when {
                res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> Outcome.Err(ErrorKind.NETWORK)
                else -> {
                    val body = res.body()!!
                    val manifest = body.ciphertext?.let { ct ->
                        val plain = crypto.openManifest(ct, vk) ?: return Outcome.Err(ErrorKind.DECRYPT)
                        json.decodeFromString<WorkspaceManifest>(plain)
                    } ?: WorkspaceManifest()
                    Outcome.Ok(Workspace(manifest, body.version))
                }
            }
        } catch (e: Exception) {
            Outcome.Err(ErrorKind.NETWORK, e)
        }
    }
}
```

- [ ] **Step 8: Create `domain/usecase/LoadWorkspace.kt`** (seam for ViewModel fakes)

```kotlin
package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Workspace

/** Abstraction the tab ViewModels depend on, so they can be unit-tested with a fake. */
interface LoadWorkspace {
    suspend fun invoke(): Outcome<Workspace>
}
```
And an implementation bridging to the repository + cache, `data/LoadWorkspaceImpl.kt`:
```kotlin
package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.usecase.LoadWorkspace
import javax.inject.Inject

class LoadWorkspaceImpl @Inject constructor(
    private val repository: WorkspaceRepository,
    private val cache: WorkspaceCache,
) : LoadWorkspace {
    override suspend fun invoke(): Outcome<Workspace> {
        val result = repository.load()
        if (result is Outcome.Ok) cache.set(result.value)
        return result
    }
}
```

- [ ] **Step 9: Bind `LoadWorkspace` in DI**

In `di/AppModule.kt` — `AppModule` is an `object` with `@Provides`. Because `LoadWorkspaceImpl` needs an interface binding, add a separate `@Module @InstallIn(SingletonComponent::class) abstract class` in a new file `di/WorkspaceModule.kt`:
```kotlin
package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.data.LoadWorkspaceImpl
import de.ledgerline.app.domain.usecase.LoadWorkspace

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkspaceModule {
    @Binds abstract fun bindLoadWorkspace(impl: LoadWorkspaceImpl): LoadWorkspace
}
```
(`SessionHolder`, `VaultKeyHolder`, `WorkspaceCache`, `WorkspaceRepository` are all `@Inject`-constructable `@Singleton`s — no explicit provider needed.)

- [ ] **Step 10: Build + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL, tests green.
```bash
git add app/src/main/java/de/ledgerline/app/data app/src/main/java/de/ledgerline/app/domain app/src/main/java/de/ledgerline/app/core app/src/main/java/de/ledgerline/app/di app/src/test
git commit -m "feat: add workspace repository, tolerant manifest models, and shared cache"
```

---

## Task 4: Workspace scaffold (bottom nav) + wire into the app flow

**Files:** Create `ui/workspace/WorkspaceScaffold.kt`. Modify `ui/nav/AppNav.kt`. Add strings.

- [ ] **Step 1: Add nav-label strings (both locales)**

`res/values/strings.xml` (append):
```xml
<string name="tab_files">Files</string>
<string name="tab_notes">Notes</string>
<string name="tab_bookmarks">Bookmarks</string>
<string name="tab_todos">Todos</string>
<string name="ws_empty_files">No files yet.</string>
<string name="ws_empty_notes">No notes yet.</string>
<string name="ws_empty_bookmarks">No bookmarks yet.</string>
<string name="ws_empty_todos">No todos yet.</string>
<string name="ws_error">Couldn\'t load your workspace.</string>
<string name="ws_retry">Retry</string>
<string name="ws_reauth">Session expired — re-pair this device.</string>
<string name="ws_file_phase3">Opening files arrives in a later version.</string>
```
`res/values-de/strings.xml` (append):
```xml
<string name="tab_files">Dateien</string>
<string name="tab_notes">Notizen</string>
<string name="tab_bookmarks">Lesezeichen</string>
<string name="tab_todos">Todos</string>
<string name="ws_empty_files">Noch keine Dateien.</string>
<string name="ws_empty_notes">Noch keine Notizen.</string>
<string name="ws_empty_bookmarks">Noch keine Lesezeichen.</string>
<string name="ws_empty_todos">Noch keine Todos.</string>
<string name="ws_error">Workspace konnte nicht geladen werden.</string>
<string name="ws_retry">Erneut versuchen</string>
<string name="ws_reauth">Sitzung abgelaufen — Gerät neu koppeln.</string>
<string name="ws_file_phase3">Dateien öffnen kommt in einer späteren Version.</string>
```

- [ ] **Step 2: Create `ui/workspace/WorkspaceScaffold.kt`**

```kotlin
package de.ledgerline.app.ui.workspace

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.bookmarks.BookmarksScreen
import de.ledgerline.app.ui.workspace.files.FilesScreen
import de.ledgerline.app.ui.workspace.notes.NotesScreen
import de.ledgerline.app.ui.workspace.todos.TodosScreen

private data class Tab(val labelRes: Int, val icon: ImageVector)

@Composable
fun WorkspaceScaffold() {
    val tabs = listOf(
        Tab(R.string.tab_files, Icons.Outlined.Folder),
        Tab(R.string.tab_notes, Icons.Outlined.Description),
        Tab(R.string.tab_bookmarks, Icons.Outlined.Bookmarks),
        Tab(R.string.tab_todos, Icons.Outlined.CheckCircle),
    )
    var selected by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (selected) {
            0 -> FilesScreen(m)
            1 -> NotesScreen(m)
            2 -> BookmarksScreen(m)
            else -> TodosScreen(m)
        }
    }
}
```
Note: `Icons.Outlined.*` used here are in the base `material-icons-core` bundled with Compose (Folder, Description, Bookmarks, CheckCircle all exist there). If any is missing at compile time, substitute a present core icon (e.g. `Icons.Outlined.Star`) rather than adding `material-icons-extended`.

- [ ] **Step 3: Route `HOME` to the scaffold + observe lock in `AppNav.kt`**

In `ui/nav/AppNav.kt`: replace the `HomePlaceholder()` call for `Destination.HOME` with `WorkspaceScaffold()` (add import `de.ledgerline.app.ui.workspace.WorkspaceScaffold`) and delete the `HomePlaceholder` composable. Also make `RootViewModel` observe the vault lock so a background-wipe returns to unlock. Inject `VaultKeyHolder` and collect it:
```kotlin
// RootViewModel constructor:
@HiltViewModel
class RootViewModel @Inject constructor(
    private val sessionStore: SessionStore,
    private val vaultKeyHolder: de.ledgerline.app.core.security.VaultKeyHolder,
) : ViewModel() {
    private val _dest = MutableStateFlow(Destination.LOADING)
    val dest: StateFlow<Destination> = _dest
    init {
        viewModelScope.launch {
            _dest.value = if (sessionStore.exists()) Destination.UNLOCK else Destination.WELCOME
        }
        // If the vault key is wiped while we're past unlock, drop back to UNLOCK.
        viewModelScope.launch {
            vaultKeyHolder.unlocked.collect { unlocked ->
                if (!unlocked && _dest.value == Destination.HOME) _dest.value = Destination.UNLOCK
            }
        }
    }
    ...
}
```
Keep `WELCOME`/`toPairing`/`toUnlock`/`toHome` as they are.

- [ ] **Step 4: Create the four tab screen stubs so the scaffold compiles**

Create minimal placeholder Composables (each replaced by its real task next). Create `ui/workspace/files/FilesScreen.kt`, `ui/workspace/notes/NotesScreen.kt`, `ui/workspace/bookmarks/BookmarksScreen.kt`, `ui/workspace/todos/TodosScreen.kt`, each:
```kotlin
package de.ledgerline.app.ui.workspace.files   // adjust package per file

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun FilesScreen(modifier: Modifier = Modifier) {   // rename per file: NotesScreen/BookmarksScreen/TodosScreen
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Files") }
}
```

- [ ] **Step 5: Build + commit**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
```bash
git add app/src/main/java/de/ledgerline/app/ui app/src/main/res
git commit -m "feat: add workspace bottom-nav scaffold and route home to it"
```

---

## Task 5: Shared UI helpers + Files tab

**Files:** Create `ui/workspace/common/Format.kt`, `ui/workspace/common/States.kt`, `ui/workspace/files/FilesViewModel.kt`, rewrite `ui/workspace/files/FilesScreen.kt`. Tests `app/src/test/java/de/ledgerline/app/ui/workspace/files/FilesViewModelTest.kt`, `app/src/test/java/de/ledgerline/app/ui/workspace/common/FormatTest.kt`.

- [ ] **Step 1: Failing test for the size formatter**

`app/src/test/java/de/ledgerline/app/ui/workspace/common/FormatTest.kt`:
```kotlin
package de.ledgerline.app.ui.workspace.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun formats_bytes() {
        assertEquals("0 B", humanSize(0))
        assertEquals("512 B", humanSize(512))
        assertEquals("1.0 KB", humanSize(1024))
        assertEquals("1.5 KB", humanSize(1536))
        assertEquals("1.0 MB", humanSize(1024L * 1024))
        assertEquals("2.0 GB", humanSize(2L * 1024 * 1024 * 1024))
    }
}
```

- [ ] **Step 2: Implement `ui/workspace/common/Format.kt`**

```kotlin
package de.ledgerline.app.ui.workspace.common

import java.util.Locale

/** Human-readable byte size, base-1024, one decimal above KB. */
fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*FormatTest*"` → PASS.

- [ ] **Step 3: Create `ui/workspace/common/States.kt`** (empty/error/refresh helpers)

```kotlin
package de.ledgerline.app.ui.workspace.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun ErrorBox(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text("Retry") }
    }
}
```

- [ ] **Step 4: Failing test for `FilesViewModel`** (folder tree + trashed filter)

`app/src/test/java/de/ledgerline/app/ui/workspace/files/FilesViewModelTest.kt`:
```kotlin
package de.ledgerline.app.ui.workspace.files

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.*
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FilesViewModelTest {
    private fun ws() = Workspace(
        WorkspaceManifest(
            files = listOf(
                FileEntry(id = "f1", name = "root.txt", size = 10, folder = null),
                FileEntry(id = "f2", name = "in-docs.txt", size = 20, folder = "d1"),
                FileEntry(id = "f3", name = "gone.txt", size = 5, folder = null, trashed = true),
            ),
            fileFolders = listOf(NamedFolder(id = "d1", name = "Docs", parent = null)),
        ),
        version = 3,
    )
    private val cache = WorkspaceCache()
    private val load = object : LoadWorkspace { override suspend fun invoke() = Outcome.Ok(ws()) }

    @Test fun root_shows_folders_then_files_excluding_trashed() = runTest {
        val vm = FilesViewModel(load, cache)
        vm.refresh()
        val ui = vm.state.value
        assertEquals(listOf("Docs"), ui.folders.map { it.name })
        assertEquals(listOf("root.txt"), ui.files.map { it.name })   // f3 trashed hidden, f2 in subfolder
    }

    @Test fun entering_a_folder_shows_its_files() = runTest {
        val vm = FilesViewModel(load, cache)
        vm.refresh()
        vm.open("d1")
        assertEquals(listOf("in-docs.txt"), vm.state.value.files.map { it.name })
        vm.back()
        assertEquals(listOf("root.txt"), vm.state.value.files.map { it.name })
    }
}
```

- [ ] **Step 5: Implement `ui/workspace/files/FilesViewModel.kt`**

```kotlin
package de.ledgerline.app.ui.workspace.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FilesUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val folders: List<NamedFolder> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val canGoBack: Boolean = false,
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
) : ViewModel() {
    private val stack = ArrayDeque<String?>().apply { addLast(null) }   // current folder = last
    private val _state = MutableStateFlow(FilesUi(loading = true))
    val state: StateFlow<FilesUi> = _state

    init { recompute(); if (cache.value.value == null) refresh() else recompute() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        when (load.invoke()) {
            is Outcome.Ok -> recompute()
            is Outcome.Err -> _state.value = _state.value.copy(loading = false, error = true)
        }
    }

    fun open(folderId: String) { stack.addLast(folderId); recompute() }
    fun back() { if (stack.size > 1) { stack.removeLast(); recompute() } }

    private fun recompute() {
        val m = cache.value.value?.manifest
        val cwd = stack.last()
        val folders = m?.fileFolders?.filter { it.parent == cwd }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val files = m?.files?.filter { !it.trashed && it.folder == cwd }?.sortedBy { it.name.lowercase() } ?: emptyList()
        _state.value = FilesUi(false, false, folders, files, canGoBack = stack.size > 1)
    }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*FilesViewModelTest*"` → PASS.

- [ ] **Step 6: Rewrite `ui/workspace/files/FilesScreen.kt`**

```kotlin
package de.ledgerline.app.ui.workspace.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.common.CenteredMessage
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.humanSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(modifier: Modifier = Modifier, vm: FilesViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    when {
        ui.loading -> LoadingBox(modifier)
        ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() }, modifier)
        else -> PullToRefreshBox(isRefreshing = ui.loading, onRefresh = { vm.refresh() }, modifier = modifier) {
            if (ui.folders.isEmpty() && ui.files.isEmpty() && !ui.canGoBack) {
                CenteredMessage(stringResource(R.string.ws_empty_files))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (ui.canGoBack) item {
                        ListItem(
                            headlineContent = { Text("..") },
                            leadingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) },
                            modifier = Modifier.fillMaxWidth().clickable { vm.back() },
                        )
                    }
                    items(ui.folders, key = { it.id }) { f ->
                        ListItem(
                            headlineContent = { Text(f.name) },
                            leadingContent = { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.fillMaxWidth().clickable { vm.open(f.id) },
                        )
                    }
                    items(ui.files, key = { it.id }) { file ->
                        ListItem(
                            headlineContent = { Text(file.name) },
                            supportingContent = { Text(humanSize(file.size)) },
                            leadingContent = { Icon(Icons.Outlined.InsertDriveFile, null) },
                        )
                    }
                }
            }
        }
    }
}
```
Note: `Icons.AutoMirrored.Outlined.ArrowBack`, `Icons.Outlined.Folder`, `Icons.Outlined.InsertDriveFile` are in material-icons-core. If `InsertDriveFile` is absent, use `Icons.AutoMirrored.Outlined.InsertDriveFile` or a present alternative.

- [ ] **Step 7: Build + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` → green.
```bash
git add app/src/main/java/de/ledgerline/app/ui/workspace app/src/test
git commit -m "feat: files tab with folder navigation, size formatting, pull-to-refresh"
```

---

## Task 6: Notes tab + in-house markdown renderer

**Files:** Create `ui/workspace/notes/Markdown.kt`, `ui/workspace/notes/NotesViewModel.kt`, rewrite `ui/workspace/notes/NotesScreen.kt`, create `ui/workspace/notes/NoteDetailScreen.kt`. Tests `app/src/test/java/de/ledgerline/app/ui/workspace/notes/MarkdownTest.kt`, `NotesViewModelTest.kt`.

- [ ] **Step 1: Failing test for the markdown inline parser**

`app/src/test/java/de/ledgerline/app/ui/workspace/notes/MarkdownTest.kt`:
```kotlin
package de.ledgerline.app.ui.workspace.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun splits_blocks() {
        val blocks = markdownBlocks("# Title\n\nHello **world**\n\n- a\n- b")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MdBlock.Paragraph("Hello **world**"), blocks[1])
        assertEquals(MdBlock.BulletList(listOf("a", "b")), blocks[2])
    }

    @Test fun parses_inline_spans() {
        val spans = inlineSpans("a **b** c *d* `e`")
        // Expect literal 'a ', bold 'b', ' c ', italic 'd', ' ', code 'e'
        assertEquals("a ", spans[0].text)
        assertEquals(MdStyle.BOLD, spans[1].style)
        assertEquals("b", spans[1].text)
        assertEquals(MdStyle.ITALIC, spans[3].style)
        assertEquals(MdStyle.CODE, spans[5].style)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*MarkdownTest*"`
Expected: FAIL — symbols undefined.

- [ ] **Step 3: Implement `ui/workspace/notes/Markdown.kt`**

```kotlin
package de.ledgerline.app.ui.workspace.notes

/** A tiny, dependency-free subset of Markdown for read-only note rendering. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class NumberedList(val items: List<String>) : MdBlock
}

enum class MdStyle { PLAIN, BOLD, ITALIC, CODE }
data class MdSpan(val text: String, val style: MdStyle = MdStyle.PLAIN)

/** Split raw markdown into block elements (headings, paragraphs, bullet/numbered lists). */
fun markdownBlocks(raw: String): List<MdBlock> {
    val lines = raw.replace("\r\n", "\n").trimEnd().split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.isBlank() -> i++
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks += MdBlock.Heading(level, line.drop(level).trim())
                i++
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val items = mutableListOf<String>()
                while (i < lines.size && (lines[i].trimStart().startsWith("- ") || lines[i].trimStart().startsWith("* "))) {
                    items += lines[i].trimStart().drop(2).trim(); i++
                }
                blocks += MdBlock.BulletList(items)
            }
            Regex("^\\s*\\d+\\. ").containsMatchIn(line) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && Regex("^\\s*\\d+\\. ").containsMatchIn(lines[i])) {
                    items += lines[i].replaceFirst(Regex("^\\s*\\d+\\. "), "").trim(); i++
                }
                blocks += MdBlock.NumberedList(items)
            }
            else -> {
                val sb = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("#") &&
                    !lines[i].trimStart().startsWith("- ") && !lines[i].trimStart().startsWith("* ")) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(lines[i].trim()); i++
                }
                blocks += MdBlock.Paragraph(sb.toString())
            }
        }
    }
    return blocks
}

/** Parse inline **bold**, *italic*, `code` into styled spans (single level, no nesting). */
fun inlineSpans(text: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    val token = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`")
    var last = 0
    for (m in token.findAll(text)) {
        if (m.range.first > last) spans += MdSpan(text.substring(last, m.range.first))
        val style = when {
            m.groupValues[1].isNotEmpty() -> MdStyle.BOLD
            m.groupValues[2].isNotEmpty() -> MdStyle.ITALIC
            else -> MdStyle.CODE
        }
        val content = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
        spans += MdSpan(content, style)
        last = m.range.last + 1
    }
    if (last < text.length) spans += MdSpan(text.substring(last))
    return spans
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*MarkdownTest*"` → PASS.

- [ ] **Step 4: Add the Compose renderer to `Markdown.kt`**

Append to `Markdown.kt`:
```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
private fun inlineAnnotated(text: String) = buildAnnotatedString {
    for (span in inlineSpans(text)) when (span.style) {
        MdStyle.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
        MdStyle.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
        MdStyle.CODE -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(span.text) }
        MdStyle.PLAIN -> append(span.text)
    }
}

@Composable
fun MarkdownText(raw: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        for (block in markdownBlocks(raw)) {
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineAnnotated(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    color = MaterialTheme.colorScheme.onBackground,
                )
                is MdBlock.Paragraph -> Text(
                    inlineAnnotated(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                is MdBlock.BulletList -> block.items.forEach { item ->
                    Row { Text("•  "); Text(inlineAnnotated(item), color = MaterialTheme.colorScheme.onSurface) }
                }
                is MdBlock.NumberedList -> block.items.forEachIndexed { idx, item ->
                    Row { Text("${idx + 1}.  "); Text(inlineAnnotated(item), color = MaterialTheme.colorScheme.onSurface) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
```

- [ ] **Step 5: Failing test for `NotesViewModel`** (pinned-first, trashed hidden)

`app/src/test/java/de/ledgerline/app/ui/workspace/notes/NotesViewModelTest.kt`:
```kotlin
package de.ledgerline.app.ui.workspace.notes

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesViewModelTest {
    private fun ws() = Workspace(
        WorkspaceManifest(
            notes = listOf(
                Note(id = "a", title = "Alpha", updated = "2026-01-01T00:00:00Z"),
                Note(id = "b", title = "Beta", pinned = true, updated = "2026-01-02T00:00:00Z"),
                Note(id = "c", title = "Gone", trashed = true),
            )
        ),
        version = 1,
    )
    private val cache = WorkspaceCache()
    private val load = object : LoadWorkspace { override suspend fun invoke() = Outcome.Ok(ws()) }

    @Test fun pinned_first_trashed_hidden() = runTest {
        val vm = NotesViewModel(load, cache)
        vm.refresh()
        assertEquals(listOf("Beta", "Alpha"), vm.state.value.notes.map { it.title })
    }
}
```

- [ ] **Step 6: Implement `ui/workspace/notes/NotesViewModel.kt`**

```kotlin
package de.ledgerline.app.ui.workspace.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotesUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val notes: List<Note> = emptyList(),
)

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
) : ViewModel() {
    private val _state = MutableStateFlow(NotesUi(loading = true))
    val state: StateFlow<NotesUi> = _state

    init { if (cache.value.value == null) refresh() else recompute() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        when (load.invoke()) {
            is Outcome.Ok -> recompute()
            is Outcome.Err -> _state.value = NotesUi(loading = false, error = true)
        }
    }

    fun noteById(id: String): Note? = cache.value.value?.manifest?.notes?.firstOrNull { it.id == id }

    private fun recompute() {
        val notes = cache.value.value?.manifest?.notes.orEmpty()
            .filter { !it.trashed }
            .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated ?: "" })
        _state.value = NotesUi(false, false, notes)
    }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*NotesViewModelTest*"` → PASS.

- [ ] **Step 7: Rewrite `ui/workspace/notes/NotesScreen.kt` + create `NoteDetailScreen.kt`**

`NotesScreen.kt`:
```kotlin
package de.ledgerline.app.ui.workspace.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.common.CenteredMessage
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(modifier: Modifier = Modifier, vm: NotesViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var openId by remember { mutableStateOf<String?>(null) }

    val current = openId
    if (current != null) {
        val note = vm.noteById(current)
        if (note != null) { NoteDetailScreen(note, onBack = { openId = null }, modifier = modifier); return }
    }
    when {
        ui.loading -> LoadingBox(modifier)
        ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() }, modifier)
        ui.notes.isEmpty() -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) { CenteredMessage(stringResource(R.string.ws_empty_notes)) }
        else -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.notes, key = { it.id }) { note ->
                    ListItem(
                        headlineContent = { Text(note.title.ifBlank { "(untitled)" }) },
                        supportingContent = { Text(note.content.lineSequence().firstOrNull()?.take(80).orEmpty(), maxLines = 1) },
                        trailingContent = { if (note.pinned) Icon(Icons.Outlined.PushPin, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.fillMaxWidth().clickable { openId = note.id },
                    )
                }
            }
        }
    }
}
```
`NoteDetailScreen.kt`:
```kotlin
package de.ledgerline.app.ui.workspace.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ledgerline.app.domain.model.Note

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(note: Note, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(note.title.ifBlank { "(untitled)" }) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(4.dp))
            MarkdownText(note.content)
        }
    }
}
```

- [ ] **Step 8: Build + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` → green.
```bash
git add app/src/main/java/de/ledgerline/app/ui/workspace/notes app/src/test
git commit -m "feat: notes tab with in-house markdown renderer and detail view"
```

---

## Task 7: Bookmarks tab (grouped, open externally)

**Files:** Create `ui/workspace/bookmarks/BookmarksViewModel.kt`, rewrite `ui/workspace/bookmarks/BookmarksScreen.kt`. Test `app/src/test/java/de/ledgerline/app/ui/workspace/bookmarks/BookmarksViewModelTest.kt`.

- [ ] **Step 1: Failing test** (grouping + trashed hidden + ungrouped last)

```kotlin
package de.ledgerline.app.ui.workspace.bookmarks

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.*
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarksViewModelTest {
    private fun ws() = Workspace(
        WorkspaceManifest(
            bookmarks = listOf(
                Bookmark(id = "1", title = "Grouped", url = "https://a.example", folderId = "g1"),
                Bookmark(id = "2", title = "Loose", url = "https://b.example", folderId = null),
                Bookmark(id = "3", title = "Gone", url = "https://c.example", trashed = true),
            ),
            bookmarkFolders = listOf(NamedFolder(id = "g1", name = "Work")),
        ),
        version = 1,
    )
    private val cache = WorkspaceCache()
    private val load = object : LoadWorkspace { override suspend fun invoke() = Outcome.Ok(ws()) }

    @Test fun groups_by_folder_with_ungrouped_last_and_hides_trashed() = runTest {
        val vm = BookmarksViewModel(load, cache)
        vm.refresh()
        val groups = vm.state.value.groups
        assertEquals(listOf("Work", null), groups.map { it.folderName })
        assertEquals(listOf("Grouped"), groups[0].bookmarks.map { it.title })
        assertEquals(listOf("Loose"), groups[1].bookmarks.map { it.title })
    }
}
```

- [ ] **Step 2: Implement `BookmarksViewModel.kt`**

```kotlin
package de.ledgerline.app.ui.workspace.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookmarkGroup(val folderName: String?, val bookmarks: List<Bookmark>)
data class BookmarksUi(val loading: Boolean = false, val error: Boolean = false, val groups: List<BookmarkGroup> = emptyList())

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
) : ViewModel() {
    private val _state = MutableStateFlow(BookmarksUi(loading = true))
    val state: StateFlow<BookmarksUi> = _state

    init { if (cache.value.value == null) refresh() else recompute() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        when (load.invoke()) {
            is Outcome.Ok -> recompute()
            is Outcome.Err -> _state.value = BookmarksUi(loading = false, error = true)
        }
    }

    private fun recompute() {
        val m = cache.value.value?.manifest
        val folderName = m?.bookmarkFolders?.associate { it.id to it.name }.orEmpty()
        val visible = m?.bookmarks.orEmpty().filter { !it.trashed }
        val byFolder = visible.filter { it.folderId != null }
            .groupBy { it.folderId }
            .toSortedMap(compareBy { folderName[it] ?: "" })
            .map { (fid, list) -> BookmarkGroup(folderName[fid] ?: "?", list.sortedBy { it.title.lowercase() }) }
        val ungrouped = visible.filter { it.folderId == null }
        val groups = byFolder + if (ungrouped.isNotEmpty()) listOf(BookmarkGroup(null, ungrouped.sortedBy { it.title.lowercase() })) else emptyList()
        _state.value = BookmarksUi(false, false, groups)
    }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*BookmarksViewModelTest*"` → PASS.

- [ ] **Step 3: Rewrite `BookmarksScreen.kt`** (open URL externally)

```kotlin
package de.ledgerline.app.ui.workspace.bookmarks

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.common.CenteredMessage
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(modifier: Modifier = Modifier, vm: BookmarksViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun open(url: String) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        }
    }
    when {
        ui.loading -> LoadingBox(modifier)
        ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() }, modifier)
        ui.groups.isEmpty() -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) { CenteredMessage(stringResource(R.string.ws_empty_bookmarks)) }
        else -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) {
            LazyColumn(Modifier.fillMaxSize()) {
                ui.groups.forEach { group ->
                    item(key = "h-${group.folderName ?: "_"}") {
                        Text(
                            group.folderName ?: "Other",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
                        )
                        HorizontalDivider()
                    }
                    items(group.bookmarks, key = { it.id }) { b ->
                        ListItem(
                            headlineContent = { Text(b.title.ifBlank { b.url }) },
                            supportingContent = { Text(b.url, maxLines = 1) },
                            modifier = Modifier.fillMaxWidth().clickable { open(b.url) },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Build + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` → green.
```bash
git add app/src/main/java/de/ledgerline/app/ui/workspace/bookmarks app/src/test
git commit -m "feat: bookmarks tab grouped by folder, opens links externally"
```

---

## Task 8: Todos tab

**Files:** Create `ui/workspace/todos/TodosViewModel.kt`, rewrite `ui/workspace/todos/TodosScreen.kt`. Test `app/src/test/java/de/ledgerline/app/ui/workspace/todos/TodosViewModelTest.kt`.

- [ ] **Step 1: Failing test** (grouped by list, trashed hidden, done last)

```kotlin
package de.ledgerline.app.ui.workspace.todos

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.*
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TodosViewModelTest {
    private fun ws() = Workspace(
        WorkspaceManifest(
            todoLists = listOf(TodoList(id = "l1", name = "Home")),
            todos = listOf(
                TodoItem(id = "t1", listId = "l1", title = "Done one", done = true),
                TodoItem(id = "t2", listId = "l1", title = "Open one", done = false),
                TodoItem(id = "t3", listId = "l1", title = "Gone", trashed = true),
            ),
        ),
        version = 1,
    )
    private val cache = WorkspaceCache()
    private val load = object : LoadWorkspace { override suspend fun invoke() = Outcome.Ok(ws()) }

    @Test fun sections_hide_trashed_and_put_open_first() = runTest {
        val vm = TodosViewModel(load, cache)
        vm.refresh()
        val s = vm.state.value.sections
        assertEquals(listOf("Home"), s.map { it.listName })
        assertEquals(listOf("Open one", "Done one"), s[0].items.map { it.title })
    }
}
```

- [ ] **Step 2: Implement `TodosViewModel.kt`**

```kotlin
package de.ledgerline.app.ui.workspace.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.TodoItem
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TodoSection(val listName: String, val items: List<TodoItem>)
data class TodosUi(val loading: Boolean = false, val error: Boolean = false, val sections: List<TodoSection> = emptyList())

@HiltViewModel
class TodosViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
) : ViewModel() {
    private val _state = MutableStateFlow(TodosUi(loading = true))
    val state: StateFlow<TodosUi> = _state

    init { if (cache.value.value == null) refresh() else recompute() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        when (load.invoke()) {
            is Outcome.Ok -> recompute()
            is Outcome.Err -> _state.value = TodosUi(loading = false, error = true)
        }
    }

    private fun recompute() {
        val m = cache.value.value?.manifest
        val listName = m?.todoLists?.associate { it.id to it.name }.orEmpty()
        val visible = m?.todos.orEmpty().filter { !it.trashed }
        val sections = listName.entries.map { (id, name) ->
            TodoSection(name, visible.filter { it.listId == id }
                .sortedWith(compareBy<TodoItem> { it.done }.thenBy { it.title.lowercase() }))
        }.filter { it.items.isNotEmpty() }
        val orphans = visible.filter { it.listId == null || it.listId !in listName }
        val all = sections + if (orphans.isNotEmpty()) listOf(TodoSection("Other", orphans.sortedWith(compareBy<TodoItem> { it.done }.thenBy { it.title.lowercase() }))) else emptyList()
        _state.value = TodosUi(false, false, all)
    }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests "*TodosViewModelTest*"` → PASS.

- [ ] **Step 3: Rewrite `TodosScreen.kt`** (read-only checkbox)

```kotlin
package de.ledgerline.app.ui.workspace.todos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.common.CenteredMessage
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodosScreen(modifier: Modifier = Modifier, vm: TodosViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    when {
        ui.loading -> LoadingBox(modifier)
        ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() }, modifier)
        ui.sections.isEmpty() -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) { CenteredMessage(stringResource(R.string.ws_empty_todos)) }
        else -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) {
            LazyColumn(Modifier.fillMaxSize()) {
                ui.sections.forEach { section ->
                    item(key = "h-${section.listName}") {
                        Text(
                            section.listName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
                        )
                        HorizontalDivider()
                    }
                    items(section.items, key = { it.id }) { todo ->
                        ListItem(
                            headlineContent = { Text(todo.title) },
                            supportingContent = { if (todo.due.isNotBlank()) Text(todo.due) },
                            leadingContent = { Checkbox(checked = todo.done, onCheckedChange = null, enabled = false) },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Build + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` → green.
```bash
git add app/src/main/java/de/ledgerline/app/ui/workspace/todos app/src/test
git commit -m "feat: todos tab grouped by list, read-only completion state"
```

---

## Task 9: Full verification + on-device smoke + finish

**Files:** none new (verification + docs).

- [ ] **Step 1: Full test sweep**

Run: `./gradlew :app:testDebugUnitTest` → all unit tests PASS.
Run: `./gradlew :app:connectedDebugAndroidTest` (physical device `62021JEBF09273` connected) → all instrumented tests PASS (crypto incl. `OpenManifestTest`, keystore).

- [ ] **Step 2: Release build (R8) still works**

Run: `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL (confirms no serialization/keep-rule regressions).

- [ ] **Step 3: Hardening still intact**

Run: `grep -q 'FLAG_SECURE' app/src/main/java/de/ledgerline/app/MainActivity.kt && grep -q 'allowBackup="false"' app/src/main/AndroidManifest.xml && echo HARDENING_OK`
Run: `grep -rnE 'Log\.(d|v|i|w|e)\(.*(token|passphrase|vk|vault|kek|manifest)' app/src/main || echo CLEAN`
Expected: `HARDENING_OK` and `CLEAN`.

- [ ] **Step 4: On-device smoke**

Install: `./gradlew :app:installDebug` then `adb -s 62021JEBF09273 shell am start -n de.ledgerline.app/.MainActivity`.
Manual (human): unlock the real vault → four tabs populate from `home.kiefer-networks.de` → open a note (markdown renders) → tap a bookmark (opens browser) → pull-to-refresh on each tab reloads. Confirm no crash: `adb -s 62021JEBF09273 logcat -d | grep -iE "FATAL|AndroidRuntime" | grep ledgerline || echo NO_CRASH`.

- [ ] **Step 5: Finish the branch**

Invoke the `superpowers:finishing-a-development-branch` skill: merge `feature/phase2` → `develop`, then `develop` → `main`, tag `v0.2.0`.

---

## Self-Review Notes (author checklist — completed)

- **Spec coverage:** openManifest crypto (T1); store DTO/API + tolerant models + repo + cache (T3); SessionHolder so authenticated calls work post-unlock (T2); bottom-nav scaffold + HOME routing + lock-observe (T4); Files tree + size format + pull-to-refresh (T5); Notes list + markdown detail (T6); Bookmarks grouped + external open (T7); Todos grouped read-only (T8); errors/lock/401 handled in each VM + States helpers (T5–T8); tests incl. tolerant-parse + byte-parity + VM states (T1,T3,T5–T8); on-device smoke + finish (T9). All spec sections map to a task.
- **Placeholder scan:** no TBD/TODO; every code step has literal code. Task 4's tab stubs are explicitly replaced in T5–T8 (not placeholders left in the final product).
- **Type consistency:** `LoadWorkspace.invoke()`, `WorkspaceCache.value`/`set`/`clear`, `SessionHolder.get/set/clear`, `Workspace(manifest, version)`, `Outcome.Ok/Err`, `Crypto.openManifest(ciphertext, vk)` and the manifest model field names are identical across all tasks that define and consume them.
- **Known API risks flagged inline:** `PullToRefreshBox` + `ListItem`/`TopAppBar` are `ExperimentalMaterial3Api` (opt-in added); `Icons.*` limited to material-icons-core with substitution notes; `String.toUri` via `androidx.core.net`. Each has a concrete note.
```
