# Workspace CRUD — Todos, Notes, Bookmarks (create/edit/delete)

**Goal:** Make the workspace modules writable (they are read-only today). Add
create/edit/delete + the module-specific actions, matching the existing rich data
model and the web client. Files already has CRUD — this fills Todos, Notes, Bookmarks.

**Pattern (mirror Files):** each module's ViewModel injects `MutateWorkspace`
(`suspend fun invoke(mutate: (WorkspaceManifest)->WorkspaceManifest): Outcome<Workspace>`,
409-merge-safe). Mutations are expressed as pure manifest transforms in a small
unit-tested `*Ops` object (mirror `AlbumOps`). UI mirrors Files: a FAB to add
(dialog/editor), item row actions (toggle/overflow), a detail/editor screen.
Soft-delete sets `trashed = true` (the read side already filters `!trashed`).

Models (existing, `domain/model/Workspace.kt`):
- `TodoItem(id, listId?, title, description, url, priority, marked, due, done, trashed)`, `TodoList(id, name)`.
- `Note(id, title, content /*markdown*/, pinned, trashed, updated?)`.
- `Bookmark(id, folderId?, title, url, description, favorite, readLater, trashed)`, `NamedFolder bookmarkFolders`.

Common: generate ids with `UUID.randomUUID().toString()`; timestamps
`OffsetDateTime.now(ZoneOffset.UTC).toString()`. Every mutation runs in
`viewModelScope.launch { mutate.invoke { Ops.xxx(it, ...) } }`. Surface failures via a
transient `_message` like the existing modules.

---

## W1 — Todos

### `domain/workspace/TodoOps.kt` (pure, unit-tested)
```kotlin
object TodoOps {
    fun addTodo(m, id, title, listId, priority, due, description, url): WorkspaceManifest  // append TodoItem(done=false, marked=false, trashed=false)
    fun editTodo(m, id, title, listId, priority, due, description, url): WorkspaceManifest
    fun toggleDone(m, id): WorkspaceManifest
    fun toggleMarked(m, id): WorkspaceManifest
    fun trashTodo(m, id): WorkspaceManifest        // trashed = true
    fun addList(m, id, name); fun renameList(m, id, name)
    fun deleteList(m, id): WorkspaceManifest        // remove list; orphan its todos → listId = null
}
```
Trim strings; unknown id → safe no-op.

### `TodosViewModel`
Inject `MutateWorkspace`. Expose `lists: StateFlow<List<TodoList>>`, current filter
(`activeList: String?`, null = all), and the delegating mutation methods above.
Keep the existing read/recompute; todos filtered by `!trashed` and (activeList null ||
listId == activeList), sorted (done last, then priority/marked as the web does — a
simple `sortedWith(compareBy({ it.done }, { priorityRank(it.priority) }))` is fine).

### UI (`TodosScreen` + `TodoDetailScreen` → editable, `TodoEditor`)
- FAB → `TodoEditor` (a dialog or full-screen form): title (required), list picker
  (existing lists + "New list…"), priority dropdown (low/normal/high/urgent — reuse
  `priority_*` strings), due (a date field — simple text or a date picker), description
  (multiline), url (optional). Save → `vm.addTodo(...)`.
- List row: a leading checkbox toggling `done` (`vm.toggleDone`), title (strikethrough
  when done), a star/flag for `marked` (`vm.toggleMarked`), tap → detail.
- A list filter (chips/dropdown of `lists` + "All") at the top; a "manage lists" entry
  (add/rename/delete list).
- `TodoDetailScreen`: show all fields; an Edit action opening `TodoEditor` prefilled
  (`vm.editTodo`), a Delete action (`vm.trashTodo` → back). Keep the existing detail
  layout, add the edit/delete actions in its top bar.
- Strings (both locales) as needed: `todo_new`, `todo_edit`, `todo_delete`,
  `todo_title_hint`, `todo_list`, `todo_no_list`, `todo_new_list`, `todo_description`,
  `todo_url_hint`, `todo_due_hint`, `list_rename`, `list_delete`, `action_save` (exists).

### Tests: `TodoOpsTest` — add/edit/toggleDone/toggleMarked/trash/addList/renameList/deleteList(orphan), unknown-id no-op.

## W2 — Notes

### `domain/workspace/NoteOps.kt` (pure)
```kotlin
object NoteOps {
    fun addNote(m, id, nowIso): WorkspaceManifest          // empty title+content, pinned=false
    fun updateNote(m, id, title, content, nowIso): WorkspaceManifest   // updated = nowIso
    fun togglePin(m, id): WorkspaceManifest
    fun trashNote(m, id): WorkspaceManifest
}
```

### `NotesViewModel`
Inject `MutateWorkspace`. `addNote()` returns/exposes the new id so the UI can open the
editor on it (or add-then-open by newest). Methods: `updateNote(id,title,content)`,
`togglePin(id)`, `trashNote(id)`. Notes sorted pinned-first then `updated` desc.

### UI (`NotesScreen` + `NoteDetailScreen` → editable editor)
- FAB → create a note then open `NoteDetailScreen` in edit mode.
- `NoteDetailScreen`: a title `TextField` + a multiline content `TextField`
  (markdown as plain text; a simple monospace/plain editor is fine — no rich renderer
  required this phase, but keep the read view rendering the text). Auto-save on back or
  a Save action (`vm.updateNote`). A pin toggle + delete (`vm.trashNote`) in the top bar.
  Show `updated` timestamp.
- List row: title (or first line), pin indicator, tap → detail; overflow delete.
- Strings: `note_new`, `note_title_hint`, `note_body_hint`, `note_pin`, `note_unpin`,
  `note_delete`, `note_updated`.

### Tests: `NoteOpsTest` — add/update(updated set)/togglePin/trash, unknown-id no-op.

## W3 — Bookmarks

### `domain/workspace/BookmarkOps.kt` (pure)
```kotlin
object BookmarkOps {
    fun addBookmark(m, id, url, title, description, folderId): WorkspaceManifest
    fun editBookmark(m, id, url, title, description, folderId): WorkspaceManifest
    fun toggleFavorite(m, id); fun toggleReadLater(m, id)
    fun trashBookmark(m, id): WorkspaceManifest
    fun addFolder(m, id, name); fun renameFolder(m, id, name)
    fun deleteFolder(m, id): WorkspaceManifest    // remove folder; orphan its bookmarks → folderId = null
}
```

### `BookmarksViewModel`
Inject `MutateWorkspace`. Expose `folders: StateFlow<List<NamedFolder>>` (from
`bookmarkFolders`), `activeFolder: String?`. Methods above. Bookmarks filtered by
`!trashed` + folder, sorted by title.

### UI (`BookmarksScreen` + add/edit dialog)
- FAB → add-bookmark dialog: url (required), title, description, folder picker
  (+ "New folder…"). Save → `vm.addBookmark`. (Optional: prefill title from url host.)
- Row: title + host, favorite star + read-later toggle, tap → open url (existing),
  overflow → edit / delete / toggle favorite / toggle read-later.
- Folder filter (chips/dropdown) + manage folders (add/rename/delete).
- Strings: `bm_new`, `bm_edit`, `bm_delete`, `bm_url_hint`, `bm_title_hint`,
  `bm_desc_hint`, `bm_folder`, `bm_no_folder`, `bm_new_folder`, `bm_favorite`,
  `bm_read_later`, `folder_rename`, `folder_delete`.

### Tests: `BookmarkOpsTest` — add/edit/toggleFavorite/toggleReadLater/trash/addFolder/renameFolder/deleteFolder(orphan), unknown-id no-op.

## Cross-cutting
- All writes go through `MutateWorkspace` (offline write is 5b — for now a failure
  surfaces a message; the optimistic cache update in `save` still reflects locally on
  success). No new endpoints; manifest schema unchanged (all fields already exist).
- Keep the existing read/recompute + `!trashed` filtering.
- Reuse existing dialogs/patterns from Files (`TextInputDialog`-style) where possible.

## Verification (per module)
- `testDebugUnitTest` (the `*Ops` tests are the correctness gate) + `assembleDebug` +
  `assembleRelease`.
- On-device: create/edit/delete an item in each module; toggle done/pin/favorite;
  create a list/folder; confirm it persists (reopen) and shows in the web (byte-compat).

## Out of scope (later)
- Trash bin UI / restore (soft-delete works; a restore screen is a later polish).
- Tags (not in the current model). Markdown rich rendering. 5b offline write queue.
