# Share target (§12) — design

**Goal:** Register the app as an Android share target so images/videos land in the
Gallery import and any other file lands in the Files import, reusing the existing
encrypt+upload pipelines (incl. Padmé). Multi-select supported. Unlock-gated.

**Runtime:** a dedicated `ShareActivity` (`@AndroidEntryPoint`, `singleTask`,
`exported=true`, `FLAG_SECURE`) receives `ACTION_SEND`/`ACTION_SEND_MULTIPLE`. It hosts
a small Compose flow: unlock (if locked) → confirm sheet → upload via `OperationManager`
→ finish. VK/session/caches are the same in-memory singletons the main app uses.

---

## S1 — ShareActivity skeleton: manifest, intent parse, unlock gate, lock lifecycle

### Manifest
Add a new activity with the share intent-filters:
```xml
<activity android:name=".ui.share.ShareActivity" android:exported="true"
          android:launchMode="singleTask" android:excludeFromRecents="true"
          android:taskAffinity="" android:theme="@style/Theme.Ledgerline">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="image/*" /><data android:mimeType="video/*" />
        <data android:mimeType="*/*" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.SEND_MULTIPLE" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="image/*" /><data android:mimeType="video/*" />
        <data android:mimeType="*/*" />
    </intent-filter>
</activity>
```
(Use the app's actual theme name — grep `android:theme` on the existing MainActivity.)

### `ui/share/ShareActivity.kt` (`@AndroidEntryPoint`)
- `FLAG_SECURE`, `enableEdgeToEdge` dark (mirror MainActivity).
- Inject `VaultKeyHolder`, `SessionHolder`, `IdleLocker`, `VaultLocker`, `LockGuard`,
  `OperationManager`, `SettingsStore`; `private val appLock = AppLock()`.
- Build the same `authorize: suspend (Cipher) -> Cipher?` as MainActivity (via
  `appLock.authenticate(this, lockTitle, lockSubtitle, CryptoObject(cipher))` +
  `idleLocker.touch()`).
- Parse the incoming intent → a `List<SharedItem>`:
  ```kotlin
  data class SharedItem(val uri: Uri, val mime: String, val name: String)
  // ACTION_SEND: EXTRA_STREAM (Parcelable Uri); ACTION_SEND_MULTIPLE: EXTRA_STREAM (ArrayList<Uri>)
  // mime: intent.type / contentResolver.getType(uri); name: OpenableColumns.DISPLAY_NAME (fallback by mime)
  ```
  Classify each: `image/*`||`video/*` → GALLERY else FILES. If the item list is empty
  → finish() with a toast.
- Lock lifecycle: a `DefaultLifecycleObserver` `onStop` that wipes via `locker.lock()`
  unless `lockGuard.consumeSkip()` OR (`operationManager.isBackgroundEnabled() && operationManager.hasActive()`)
  — mirror MainActivity's deferred-lock rule (so an in-flight upload survives per the
  Phase-4d model). `onResume` idle check like MainActivity (skip wipe while `hasActive()`).
- Compose content: observe `vaultKeyHolder.unlocked`. If not unlocked → `UnlockScreen`
  (reuse `ui/unlock/UnlockScreen`, passing `authorize` + `onUnlocked = {}` — it flips
  `unlocked` via the holder). If unlocked → the import flow (S2; for S1 render a
  placeholder confirm that lists item counts + a "Cancel"/"Import (todo)" and `finish()`).
- Provide `finish()` paths: Cancel → finish; success (S2) → finish.

### Tests
- A JVM test for the intent-parsing/classification helper: extract a pure
  `fun classify(mime: String): ShareTarget` and `fun itemsFrom(...)` where feasible;
  test `image/png`→GALLERY, `video/mp4`→GALLERY, `application/pdf`→FILES,
  `text/plain`→FILES, null/`*/*`→FILES.

### On-device (S1)
- Build + install. From another app (e.g. a file/photo app) tap Share → confirm
  "Ledgerline" appears as a target; selecting it opens the ShareActivity, shows the
  unlock screen (if locked), unlock works, placeholder confirm shows correct counts.
  Crash check.

## S2 — Confirm sheet + upload

### `ui/share/ShareViewModel.kt` (`@HiltViewModel`)
Inject `GalleryUploader`/the gallery upload path + `FileBlobs` + `MutateWorkspace` +
`WorkspaceCache` (for the folder list) + `OperationManager` + content reading via an
injected `@ApplicationContext`. Expose:
- `val fileFolders: StateFlow<List<NamedFolder>>` (from `WorkspaceCache`, for the picker).
- `fun import(items: List<SharedItem>, targetFolder: String?)`:
  - Split into gallery items + file items.
  - **Gallery:** map to `PhotoSource(name, mime, read = { contentResolver.openInputStream(uri)!!.readBytes() }, lat=null, lng=null)` and run the existing gallery `uploadAll` logic (route through `OperationManager` `OpKind.UPLOAD`, dedup by sig, append entries via `MutateGallery`). Reuse `GalleryViewModel.uploadAll` if practical by extracting the core to a shared use-case, OR replicate its loop here calling `GalleryUploader.upload` + `MutateGallery`. Prefer extracting a small `GalleryImport` use-case shared by both.
  - **Files:** for each, `OperationManager.run(OpKind.UPLOAD, total)` → `FileBlobs.upload(name, mime, size, open)` → on Ok append a `FileEntry(id, blob, encFileKey, name, mime, size, folder = targetFolder, created)` via `MutateWorkspace`. Reuse `FilesViewModel.uploadPicked`'s logic — extract a shared `FileImport` use-case taking an explicit `folder` (uploadPicked hardcodes cwd), used by both the Files screen and share.
  - Read `size` from `OpenableColumns.SIZE` (fallback: read bytes length).
  - Surface failures as a count message; success → the activity finishes after the op drains.
- Progress via the shared `OpProgressOverlay` (reads `OperationManager.active`).

### `ui/share/ShareScreen.kt` (composable used by ShareActivity when unlocked)
- A `ModalBottomSheet`/card: title `share_title`, a summary line ("N photos/videos → Gallery, M files → Files" via `share_summary_*`), a folder selector (`share_target_folder`, only shown when there are file items — dropdown of `fileFolders` + a "Root" option), and **Import** (`share_import`) + **Cancel** (`action_cancel`) buttons.
- Import → `vm.import(items, folder)`; show `OpProgressOverlay`; when `OperationManager.active` drains after an import was started, `finish()` the activity (pass a `onDone` callback). Show a snackbar on partial failure before finishing.

### Refactor (shared use-cases, keep existing screens working)
- Extract `domain/usecase/ImportFile` (or a `FileImport` class): `suspend fun invoke(name, mime, size, folder, open): Outcome<Unit>` doing `FileBlobs.upload` + `MutateWorkspace` append. Refactor `FilesViewModel.uploadPicked` to call it with `folder = cwd`.
- Extract `domain/usecase/ImportPhotos` from `GalleryViewModel.uploadAll` (the dedup+upload+mutate loop) taking `List<PhotoSource>`; refactor `GalleryViewModel.uploadAll` to delegate. Both run through `OperationManager`.
Keep all existing behaviour identical (tests green).

### Strings (both locales)
`share_title`, `share_summary_photos` ("%1$d photos/videos → Gallery"),
`share_summary_files` ("%1$d files → Files"), `share_target_folder`, `share_root`,
`share_import`, `share_imported` ("Imported %1$d item(s)"), `share_import_failed`
("%1$d import(s) failed"), `share_locked_hint` (optional).

### On-device (S2)
- Share a single photo → Gallery import runs, appears in gallery. Share a PDF → Files
  import into chosen folder. Share multiple mixed → both. Verify from the web the
  uploaded items appear (byte-compatible). Offline → failure message (queue is 5b).

## Security checklist
- ShareActivity is `FLAG_SECURE`, unlock-gated (no VK → UnlockScreen), and wipes on
  background via `VaultLocker` (respecting active ops). Shared bytes are read into
  memory, encrypted (Padmé), uploaded; no plaintext written to disk. Temp nothing.
- Content URIs are read via `contentResolver` with the grant from the share intent
  only. No extra permissions.
