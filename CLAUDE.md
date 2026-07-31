# Ledgerline Android — Projekt- & Technik-Kontext

Native Android-Client für die selbst-gehostete **Ledgerline**. **Server-Pivot v1.5xx (Juli 2026):
das Zero-Knowledge-/Vault-/Sealed-Store-Modell wurde vollständig entfernt.** Der Server ist jetzt
eine **plaintext-relationale Finance-API** (Laravel-13, `/api/v1`, aktuell **v1.526.x**). Es gibt nur
noch ein Modul: **Finance** (Rechnungen, Bank-Umsätze, Zahlungsmittel, Geschäftspartner, Projekte,
Kategorien) + Firmenprofil. Web-App = Referenz/Superset; iOS = Look-&-Feel-Referenz.

> **Diese Datei MUSS den aktuellen Stand widerspiegeln. Nach jeder Änderung pflegen.**
> Ground truth API: `../ledgerline/openapi.yaml` (title „Ledgerline API", finance-only) +
> `../ledgerline/routes/api.php`. Ground truth Logik/Design: `../ledgerline` Web + `../ledgerline-ios`.

---

## 0. Architektur-Prinzip (NEU)

**Plaintext-relational.** Jedes Modul = relationale Tabelle über per-record REST. Sensible Spalten
sind **server-seitig** at-rest verschlüsselt (Laravel `encrypted`-Cast, APP_KEY) und kommen
**entschlüsselt über TLS** zurück — der Client sendet/empfängt **Klartext**. **Kein Client-Crypto,
kein Vault, kein Vault-Key, keine sealed Manifeste, kein Passphrase-Unlock.** Analytics
(USt-Voranmeldung, KPIs, Duplikate, Kategorie-Vorschläge) werden **server-berechnet**.

Konsequenz: das Bearer-Token gibt **direkten** Datenzugriff. Es gibt nur einen **lokalen
biometrischen App-Lock** (Datenschutz am Gerät), keine kryptografische Entsperrung.

---

## 1. Basis-URL & TLS

Self-hosted, variabel — die App bekommt die Base-URL aus dem QR-Pairing (§2). Nur HTTPS,
`RESTRICTED_TLS` + TOFU-SPKI-Pinning (fail-closed, `CertificatePinner`, bei Pairing gesetzt).

## 2. Authentifizierung — QR Device Pairing (unverändert)

1. Web-Profil erzeugt QR mit Deep-Link `ledgerline://pair?url=<base>&code=<one-time>`.
2. App scannt (CameraX + ZXing), `POST /auth/pair {code, device_name}` → `{status:'pending'}`.
3. Web bestätigt. App pollt `POST /auth/pair/collect {code, install_id?, app_version?, os_version?}`
   → `pending` … dann **einmalig** `approved` → `{token, user}`; danach `410`.
4. Token wird **Keystore-versiegelt** gespeichert (ein biometrischer `CryptoObject`-Prompt beim
   Pairing autorisiert das Siegeln). Header `Authorization: Bearer <token>` für alle `/api/v1`.
5. **Kein Refresh** — widerrufen/verloren → neu pairen. Rate-Limit `/auth/pair*`: 30/min/IP.

**App-Lock:** Beim Öffnen liest **ein** biometrischer/Geräte-PIN-Prompt das versiegelte Token in den
`SessionHolder` (`AppLockScreen`/`AppLockViewModel`). Hintergrund (`ON_STOP`) + Idle-Timeout →
`AppLockState.lock()` → Lock-Screen. `FLAG_SECURE` app-weit (Release). Remote-Wipe-Kill-Switch:
`GET /me` `wipe:true` → `AuthEventBus.wipe` → `ForceLogout` (alles lokal löschen + neu pairen);
auch bei authentifiziertem `401` (widerrufenes Token).

## 3. API — `/api/v1` (finance-only)

**Konto/Gerät:** `GET /me` (`{user, wipe}`; `user.modules ∈ {finance}`), `GET /avatar`,
`POST /device/heartbeat`, `DELETE /auth/session`, `GET/DELETE /devices`, `POST /devices/{t}/wipe`,
`GET /notifications` (+read/read-all), `GET /account/export`, `DELETE /account`,
`DELETE /account/sessions/{id}`, `POST /locale` · `/theme` · `/preferences`, `GET/PUT /settings`,
`/user/two-factor/*`, `PUT /user/password`, `/device-pairings/*` (owner-side).

**Finance** (alle `Bearer`, `403` wenn Modul deaktiviert):
- `GET /finance/data` → Snapshot `{invoices, partners, paymentMethods, projects, financeCategories,
  transactions}`.
- **Analytics (read-only, server-berechnet):** `GET /finance/reports?year` (VatReturn/KPIs/customers/
  months), `/finance/reports/account-vat?account_id&year`, `/finance/duplicates`,
  `/finance/category-suggestions`, `/finance/trash`.
- **Per-record CRUD** (POST create, PUT update mit `version` → **409 `{error,version}`** bei Konflikt,
  DELETE = soft-delete, `POST …/{id}/restore`, `DELETE …/{id}/force`) für: `invoices` (+`/finalize`
  = gapless GoBD-Nr, `GET/POST /pdf`), `transactions` (+`/bulk` sig-dedup, `+/receipts` multipart,
  `/receipts/{r}/raw`), `partners`, `payment-methods`, `projects` (+`/move`), `categories`.
- `POST /invoices/ocr` (multipart, transient Klartext → `{text, source, pages}`).
- `GET/PUT /company` (Firmenprofil + Rechnungs-Defaults; PUT JSON oder multipart mit `logo`/
  `remove_logo`), `GET /company/logo`.

**Record-Shape:** int `id`, `version` (optimistic), `created_at/updated_at`, meist `deleted_at`.
Additiv/lenient dekodieren (`ignoreUnknownKeys`); offene Spalten (invoice `customer`/`lines`,
tx `receipts`, partner `contacts`) als rohes `JsonObject` halten.

## 4. Sicherheit (NEU, reduziert)

- **Kein Client-Crypto mehr.** Einziges persistiertes Secret: das Bearer-Token, **AES-256-GCM im
  Keystore** (`KeystoreSealer`, StrongBox wo verfügbar, `setUserAuthenticationRequired(true)` +
  per-use `CryptoObject`-Biometrie). `allowBackup=false`, Data-Extraction-Rules schließen Cloud/
  Device-Transfer aus.
- Transit: HTTPS erzwungen (`network_security_config` cleartext=false), TOFU-SPKI-Pinning fail-closed.
- `FLAG_SECURE` (Screenshot/Recents-Block, Release). Biometrischer App-Lock + Idle-Timeout +
  Hintergrund-Lock. Remote-Wipe-Kill-Switch. Kein Secret-Logging.
- **Bewusst NICHT mehr vorhanden** (mit dem ZK-Modell entfernt): Vault/Passphrase/VK, PQ-KEM,
  Remember-Vault, Duress-Wipe, Unlock-Throttle, Clock-Guard, Security-Audit-Log, Integrity-Attestation,
  Autofill/Passkey-Provider. Server hält die Daten (encrypted-at-rest via APP_KEY) — Vertrauen liegt
  jetzt server-seitig, nicht mehr zero-knowledge.

## 5. Tech-Stack

Kotlin + Jetpack Compose (Material 3 Expressive), Hilt, Retrofit + OkHttp (Bearer-Interceptor,
SPKI-Pinning, 429-Backoff, forced-logout-Interceptor bei 401), kotlinx.serialization
(`ignoreUnknownKeys`, snake_case via `@SerialName`), CameraX + ZXing (QR), AndroidX Biometric,
EncryptedSharedPreferences/Keystore, DataStore (UI-Prefs). `minSdk 36`, `targetSdk 36`,
`compileSdk 37`. Paket `de.ledgerline.app`. Versions-Policy: neueste stabile Libs (§`gradle/
libs.versions.toml`), material3 1.5.0-alphaXX bewusst adoptiert.

**Schichten:** `ui/*` (Compose + VMs), `domain/model/finance/*` + `domain/model/Session`,
`data/*` (Repos), `core/*`, `di/*`.

## 6. Android-Architektur (Ist)

- **Flow** (`ui/nav/AppNav`): `WELCOME → PAIRING → LOCK (biometrisch) → HOME (FinanceShell)`.
  `RootViewModel` gated auf `AppLockState.unlocked`; 401/Remote-Wipe → `ForceLogout` + re-pair.
- **`ui/money/FinanceShell`** — 4-Tab-Bottom-Nav (Dashboard/Rechnungen/Umsätze/Mehr) über geteiltes
  **`FinanceViewModel`**; Detail/Edit/Listen als `MoneyRoute`-Overlays (`ui/money/MoneyScreens.kt`).
  Screens: Dashboard (Server-KPIs/USt/Top-Kunden), Rechnungen (Liste/Anlegen/Bearbeiten/Finalize/
  Löschen), Umsätze (Liste/Bearbeiten), Partner/Zahlungsmittel/Projekte (Listen+Edit), Firmenprofil,
  Settings (Sperren/Trennen).
- **`data/finance/FinanceRepository`** — cache-first Read (Klartext-Disk-Cache `finance_data.json`) +
  Online-CRUD (patcht In-Memory-Snapshot `StateFlow<FinanceData>` + Disk), live Analytics/Company.
  `NetworkFactory.createFinance` (+ `FinanceApi`). **Offline:** Reads aus Cache; **Writes offline
  liefern derzeit NETWORK-Fehler** (kein Datenverlust) — Offline-Write-Queue = TODO.
- **`core`:** `AppLockState`, `SessionHolder`, `AuthEventBus`, `ServerReachability` (GET /up →
  Offline-Modus), `ModuleAccess`, `AvatarCache`, `AccountSnapshotCache`, `prefs/DisplayPrefs`,
  `security/{KeystoreSealer,VaultAuthorizers,AppLock,BiometricAvailability}`, `offline/Connectivity`.
- **`data`:** `AccountRepository` (/me, avatar, devices, notifications, account, preferences, heartbeat),
  `SessionStore` (Keystore-sealed Token), `PairingRepository`, `SettingsStore` (timeout/theme/keep-
  screen/display-prefs), `ForceLogoutImpl`, `remote/LedgerlineApi` (Konto/Gerät — enthält noch tote
  Alt-Endpunkte, TODO trimmen) + `FinanceApi` + dto.
- **`i18n`:** EN/DE/RU (`values[-de|-ru]/strings.xml`). Neue Strings in allen dreien.
- **Design:** `ui/theme/Brand.kt` (`Brand` accent-gradient indigo→violett, tints, `cardSurface()`,
  `IconChip`, `HeroIcon`, `PrimaryGradientButton`, `LedgerlineBackground`), `ui/common/{AppScaffold,
  AppTopBar,SectionLabel}`. `FLAG_SECURE` → visuelle Änderungen muss der Nutzer am Gerät prüfen.

## 7. Stand & TODO (finance-pivot)

**Erledigt:** Datenschicht (Modelle + `FinanceApi` + Repository cache-first/CRUD + Offline-Write-Queue
`FinanceOutbox` für update/delete), biometrischer App-Lock, Finance-Shell + alle Kern-Screens, Nav,
Manifest/DI/Entry-Points auf finance-only rewired, ZK-Stack gelöscht (355→~77 Quell-Dateien). **4b
UI-Tiefe weitgehend erledigt:** Dashboard-Jahr-Picker, Rechnungs-Positionen-Editor (Live-Summen),
importierte Rechnung (Read-only-Kernfelder + Original-PDF via FileProvider/`DocOpener`), PDF-Upload,
Belege anhängen/anzeigen/löschen (multipart), Bulk-CSV-Import (`core/finance/BankCsv`), OCR-Scan,
Insights (Duplikate + Kategorie-Vorschläge), Account-Security (Passwort/2FA/Export-SAF/Löschen),
Geräte + Notifications + Theme. Dead-Libs entfernt (lazysodium/PdfBox/BouncyCastle/mapsforge/Media3/
credentials/autofill/documentfile). `LedgerlineApi` + tote dto getrimmt. Tests: `FinanceOutboxTest`,
`BankCsvTest`. `assembleDebug` + Unit-Tests grün. **On-device-Verifikation weiter offen** (Gerät fiel
wiederholt beim Install ab).

**TODO (Rest):**
- **On-device:** Pairing (QR) → biometrisch entsperren → `/finance/data` lädt → Rechnung/Umsatz
  anlegen/PDF/Beleg/CSV-Import durchklicken. FLAG_SECURE → visuell prüfen.
- **Bulk-Import:** MT940/CAMT.053-Formate (nur CSV fertig).
- **Offline-Create** (provisional int-id-Remap über Fremdschlüssel) — bewusst verschoben (riskant).
- **Firmenlogo** Upload/Anzeige (multipart `logo`/`remove_logo`); ungenutzte xml/drawables der
  gelöschten Features entfernen; mehr Finance-Unit-Tests.

## 8. Referenzen
- API: `../ledgerline/openapi.yaml` · `../ledgerline/routes/api.php`
- Web (Logik/Design): `../ledgerline` · iOS: `../ledgerline-ios`
