# iOS-Parität & Restyle — Design

- **Datum:** 2026-07-24
- **Status:** Pass 1 (Design-System + Icon) umgesetzt; Rest als Roadmap.
- **Kontext-Quellen:** `../ledgerline-ios` (SwiftUI-Client, Ground truth Design),
  `../ledgerline` (Web, Ground truth Contract, v1.505.x), diese App.

## Ziel

Die Android-App im **Stil des iOS-Clients** aufziehen (Indigo/Violett-Verlauf,
adaptives Light/Dark, Icon-Chips, Grouped-Cards, Gradient-Buttons, neues App-Icon)
und die **Gaps zu iOS/Web klar dokumentieren**. Feature-Parität (Passwörter,
Sharing, PQ-Krypto, Store-v3-Migration) wird spezifiziert, aber in Pass 1 nicht
gebaut (bewusste Entscheidung des Owners).

## Entscheidungen (Owner)

1. **Theme:** volles **adaptives Light + Dark** (wie iOS), nicht always-dark.
2. **Restyle-Umfang Pass 1:** Foundation + Chrome (Theme, Design-System-Komponenten,
   Icon, angewandt auf Nav-Akzent + Unlock/Welcome). Per-Screen-Reskin = Roadmap.
3. **Store-v3-Breakage:** in Pass 1 nur spezifiziert (nicht gefixt). **Aber P0.**

## Ist-Analyse (Kurz)

- **Android-Style:** flaches Teal `#4FD8C4`, always-dark, keine Verläufe, kein
  Chip/Card-System. (`ui/theme/*`, Icon teal Shield.)
- **iOS-Style:** Indigo→Violett `#7066F5→#9E70FA`, adaptiv, `IconChip`/`HeroIcon`/
  `.cardSurface()`/`PrimaryButtonStyle`, Grouped-Lists, monochrome SF-Symbols,
  App-Icon Shield+weißes Schloss auf Indigo-Verlauf.
- **Contract-Drift (P0):** Android ruft monolith `GET/PUT api/v1/store`; Server hat
  das entfernt (`/store/{module}`, bare `/store` 404t) → Workspace kaputt. Uploads
  single-shot (kein chunked), Gallery v2-inline statt v3-sharded.
- **Feature-Lücken:** siehe CLAUDE.md §14 (Passwörter, Shares, Vaults+PQ, Passkeys,
  Devices/Wipe-UI, Notifications, Explore/Maps, Health, Invoices, Export).

## Pass 1 — umgesetzt

**Neue/geänderte Files:**
- `ui/theme/Color.kt` — komplette Light- **und** Dark-M3-Rollen aus der Indigo-Familie.
- `ui/theme/Theme.kt` — `LedgerlineTheme(darkTheme = isSystemInDarkTheme())`,
  Light-/Dark-Scheme-Auswahl.
- `ui/theme/Brand.kt` (neu) — Design-System-Port aus iOS: `Brand`
  (accent/accentSoft/tints/`accentGradient`/Metriken), `IconChip`, `HeroIcon`,
  `Modifier.cardSurface()`, `PrimaryGradientButton`, `SecondaryBrandButton`,
  `LedgerlineBackground`.
- Icon: `drawable/ic_launcher_background.xml` (Gradient), `…_foreground.xml`
  (Shield + weißes Schloss), `…_monochrome.xml`, `mipmap-anydpi-v26/ic_launcher[_round].xml`
  (Gradient-BG + Monochrome), `drawable/ic_ledgerline_logo.xml` (In-App-Mark).
- `values/colors.xml` + `values-night/colors.xml`, `values/themes.xml` +
  `values-night/themes.xml` — adaptives Fenster-Theme (kein Pre-Compose-Flash).
- `MainActivity.kt` — System-Bar-Style nach `uiMode` (Light/Dark), statt hardcoded dark.
- `ui/unlock/UnlockScreen.kt`, `ui/onboarding/WelcomeScreen.kt` — Haupt-CTAs auf
  `PrimaryGradientButton` (iOS-Signatur-Verlauf).

**Akzeptanz:** `:app:compileDebugKotlin :app:processDebugResources` grün. Visuell am
Gerät in **beiden** Modi prüfen (FLAG_SECURE blockt Screenshots).

## Roadmap (nach Pass 1, priorisiert)

### R0 — Workspace-Module-Stores — ERLEDIGT (2026-07-24)
- `LedgerlineApi`: `moduleStore(module)`/`putModuleStore(module,body)` = `GET/PUT
  /api/v1/store/{module}`; altes `store()` @Deprecated.
- `WorkspaceRepository` fächert auf notes/todos/bookmarks/contacts auf: per-Modul-Version,
  409-Merge pro Modul (reload nur des Konflikt-Moduls → re-mutate → retry), per-Modul-
  Offline-Cache `workspace_<mod>`. App-Contract (`WorkspaceManifest` + `save(mutate)`)
  unverändert → keine ViewModel-Änderungen.
- Wire-Shapes `NotesManifest`/`TodosManifest`/`BookmarksManifest`/`ContactsManifest`
  (`v:3`, exakt die Web-`MODULE_BLANKS`-Keys). Kein Canonical-JSON nötig (Modul-Stores
  hashen den Klartext nicht; JSON.parse ist reihenfolge-unabhängig).
- **Datei-Guard:** solange `/files/store` (sharded) nicht migriert ist, lehnt `save`
  Datei-/Ordner-Mutationen ab (kein stiller Verlust) statt sie zu droppen.
- Tests: `WorkspaceSaveTest` (Notes-409-Merge + Datei-Reject), `OfflineStoreLoadTest`
  (per-Modul-Cache online→offline). Volle Unit-Suite grün.
- **Offen (R0-Rest → R1):** `/files/store` + `/gallery/store` sharded schreiben.

### R1 — Gallery v3 sharded + chunked Upload
- Root `{v:3,shards,collections}` lesen; Shard-/Collection-Blobs parallel entschlüsseln;
  dirty-save. `raw-batch` für Thumbnail-Batches. `upload/init|part|complete|abort` für >64 MiB.

### R2 — Per-Screen-Reskin auf das Design-System
- Files/Gallery/Notes/Todos/Bookmarks/Contacts/Settings + Viewer auf `cardSurface`,
  `IconChip`, `HeroIcon`, Grouped-List-Optik, Gradient-CTAs. Empty-/Loading-States.

### R3 — Feature-Parität (groß, je eigener Spec)
Passwort-Manager (+Autofill-Service), Share-Links, Cross-User-Sharing + PQ-Hybrid-KEM,
Passkeys, Devices/Remote-Wipe-UI, Notifications, Konto-Export/Löschen, Explore/Maps,
Health, Invoices. Jeweils Web-Spec unter `../ledgerline/docs/superpowers/specs/` als Vorlage.

### R4 — Nav-Angleichung
Optional Passwords-Tab wie iOS; Tab-Reihenfolge/Icons an iOS annähern, ohne die
Android-only-Module (Notes/Todos/Bookmarks/Contacts) zu verlieren.

## Nicht-Ziele (Pass 1)
Keine Krypto-Änderung (Contract eingefroren, §4 CLAUDE.md). Kein Feature-Neubau.
Kein Dynamic Color.
