# Ledgerline Android — Phase 2 Design: Workspace Store (read-only)

**Date:** 2026-07-10
**Status:** Approved (design)
**Depends on:** Phase 1 (pairing, vault unlock, `Crypto`, `VaultKeyHolder`,
`NetworkFactory`, session).

## 1. Goal

Load the sealed workspace manifest, decrypt it on-device with the Vault Key, and
render four sections **read-only**: Files, Notes, Bookmarks, Todos. Pull-to-refresh
reloads the manifest. No file-content download and no writes (those are Phase 3).

## 2. Ground truth (from the web client)

`resources/js/app.js` `LLStore` blank manifest:

```js
{ v: 1, notes: [], bookmarks: [], bookmarkFolders: [],
  todos: [], todoLists: [], files: [], fileFolders: [] }
```

Approximate entry shapes (tolerate unknown/missing fields — additive schema):

- **note**: `{ id, title, content /*markdown*/, tags[], pinned, trashed, updated }`
- **file**: `{ id, blob, encFileKey, name, mime, size, folder /*folderId|null*/,
  created, versions[] }`
- **fileFolder**: `{ id, name, parent /*folderId|null*/ }`
- **bookmark**: `{ id, folderId, title, url, description, tags[], favorite,
  readLater, trashed? }`
- **bookmarkFolder**: `{ id, name, parent }`
- **todoList**: `{ id, name }`
- **todo**: `{ id, listId, title, description, url, priority, marked, tags[],
  due, done, trashed }`

Transport: `GET /api/v1/store` → `{ ciphertext: <string|null>, version: <int> }`.
`ciphertext` is the sealed manifest string `{"c":...,"n":...}`; `null` = empty
workspace. `version` is retained for Phase-3 optimistic writes.

## 3. Crypto (new primitive)

Add to the `Crypto` interface + `SodiumCrypto`:

```
openManifest(ciphertext: String): String   // returns the decrypted JSON text
```

Steps (byte-compatible with `vault.js` `openManifest`):
1. `JSON` parse `ciphertext` → `{ c, n }`.
2. `plain = secretBoxOpen(b64decode(c), b64decode(n), VK)` — VK from `VaultKeyHolder`.
3. Return `utf8(plain)`. The 4-KiB whitespace padding is ignored by the JSON
   parser downstream.

`openManifest` needs the VK, so it takes the key as a parameter
(`openManifest(ciphertext, vk)`) to keep `Crypto` stateless; the repository passes
`VaultKeyHolder.get()`. Returns `null` on decrypt failure (locked/tampered).

Instrumented byte-parity test: seal a known manifest with a VK via secretbox, then
`openManifest` recovers the exact JSON.

## 4. Data layer

- `data/remote/dto/StoreDtos.kt`: `StoreResponse(ciphertext: String?, version: Int)`.
- `LedgerlineApi`: `@GET("api/v1/store") suspend fun store(): Response<StoreResponse>`.
- `domain/model/Workspace.kt`: `@Serializable` models with `ignoreUnknownKeys`
  (`Note`, `FileEntry`, `FileFolder`, `Bookmark`, `BookmarkFolder`, `TodoList`,
  `TodoItem`, `WorkspaceManifest`). All fields nullable/defaulted so partial or
  future manifests parse. String IDs.
- `data/WorkspaceRepository.kt`: `suspend fun load(): Outcome<Workspace>`:
  `GET /store` → if `ciphertext==null` return empty `Workspace(version)`; else
  `openManifest(ciphertext, vk)` → `Json{ignoreUnknownKeys=true}.decodeFromString`
  → `Workspace(manifest, version)`. Maps: no VK → `Err(DECRYPT)` (locked); HTTP 401
  → `Err(HTTP)` (re-pair); network → `Err(NETWORK)`.
- `domain/usecase/LoadWorkspace.kt`: thin wrapper over the repo returning the parsed
  `Workspace`. Enables a fake in ViewModel unit tests.

**Caching:** a `@Singleton WorkspaceCache` holds the last-loaded `Workspace` in a
`StateFlow`, so the four tab ViewModels share one fetch. Pull-to-refresh calls
`WorkspaceRepository.load()` and updates the cache; on VK wipe the cache clears.

## 5. UI

Replace `HomePlaceholder` with `ui/workspace/WorkspaceScaffold.kt`: a `Scaffold`
with a **Material 3 bottom navigation** (4 destinations) hosting a nested nav.
Each tab is a Composable + `@HiltViewModel` reading `WorkspaceCache`. Every tab list
is wrapped in a `PullToRefreshBox` that triggers a manifest reload.

- **Files** (`FilesScreen`): folder-tree navigation via an in-screen back stack of
  folder ids (root = `null`). Rows: folders first (name, chevron), then files
  (type icon, name, human size). Tapping a file shows a "content in a later
  version" note (no download in Phase 2). Back button pops the folder stack.
- **Notes** (`NotesScreen` + `NoteDetailScreen`): list sorted pinned-first then by
  `updated` desc (title + first-line preview + pin indicator). Detail renders the
  markdown `content` read-only via a small in-house markdown → `AnnotatedString`
  renderer (headings, bold, italic, inline code, bullet/numbered lists, links).
  No third-party markdown dependency.
- **Bookmarks** (`BookmarksScreen`): grouped by `bookmarkFolder` (ungrouped last).
  Row: favicon-less title + host + description; tap → open `url` in the system
  browser (`Intent.ACTION_VIEW`), validated `http(s)` only. Favorite/read-later
  badges.
- **Todos** (`TodosScreen`): section per `todoList`; rows show a **disabled**
  checkbox (`done`), title, due date, priority chip. Read-only.

`trashed == true` entries are hidden everywhere. Each tab has an empty state.

## 6. Errors, lock, lifecycle

- VK missing/wiped (background/idle) → the workspace cache is empty and the app
  routes back to `UNLOCK` (RootViewModel observes `VaultKeyHolder.unlocked`).
- Load failure → inline error + retry button (and pull-to-refresh).
- HTTP 401 → message "Session expired — re-pair this device."
- Bookmarks open externally; nothing else leaves the app.

## 7. Testing

- **Instrumented:** `openManifest` byte-parity (seal → open round-trip on device).
- **Unit:** manifest parsing with a JSON fixture that includes unknown fields and
  missing optional fields (must not throw); `trashed` filtering; folder-tree
  grouping; pinned-first sort; the four tab ViewModels' state (loading/loaded/
  empty/error) against a fake `LoadWorkspace`.
- **On-device smoke:** unlock a real vault, see the four tabs populate, pull-to-
  refresh works.

## 8. File structure

```
data/remote/dto/StoreDtos.kt
data/WorkspaceRepository.kt
domain/model/Workspace.kt
domain/usecase/LoadWorkspace.kt
core/WorkspaceCache.kt
ui/workspace/WorkspaceScaffold.kt
ui/workspace/files/FilesScreen.kt, FilesViewModel.kt
ui/workspace/notes/NotesScreen.kt, NoteDetailScreen.kt, NotesViewModel.kt
ui/workspace/notes/Markdown.kt          (in-house markdown -> AnnotatedString)
ui/workspace/bookmarks/BookmarksScreen.kt, BookmarksViewModel.kt
ui/workspace/todos/TodosScreen.kt, TodosViewModel.kt
ui/workspace/common/          (size formatter, empty state, refresh box helpers)
```
Modify: `Crypto`/`SodiumCrypto` (openManifest), `LedgerlineApi` (store), `AppNav`
(HOME → WorkspaceScaffold), DI (`WorkspaceCache`, repo/usecase), strings (en+de).

## 9. Out of scope (Phase 2)

File/blob content download + secretstream decrypt, any writes/edits, Padmé,
gallery, offline cache, search. Deferred to Phase 3+.
