# Ledgerline Android — Projekt- & Technik-Kontext

Native Android-Client für die selbst-gehostete **Ledgerline** Personal Cloud
(Dateien, Workspace = Notizen/Lesezeichen/Todos/Kontakte, Galerie). Der Server ist
ein Laravel-13-Backend mit einer versionierten Mobile-API unter `/api/v1`
(aktuell **Server v1.505.x**). Es gibt drei Clients: die **Web-App** (Referenz /
Superset), der native **iOS-Client** (`../ledgerline-ios`, SwiftUI) und **diese
Android-App**. Web ist Ground Truth für Krypto und Transport-Contract; iOS ist
Ground Truth für **Look & Feel** (die App soll den iOS-Stil treffen).

> **Diese Datei ist der Bauplan und MUSS den aktuellen Stand widerspiegeln.**
> Nach jeder Änderung mitpflegen. Ground truth Krypto: `../ledgerline/resources/js/vault.js`.
> Ground truth Design: `../ledgerline-ios/App/DesignSystem/{Theme,Components}.swift`.
> Ground truth API: `../ledgerline/openapi.yaml` + `../ledgerline/routes/api.php`.

---

## 0. Das eine Prinzip: ZERO-KNOWLEDGE

Der Server sieht **niemals** Klartext. Er speichert nur: Ciphertext-Blobs,
versiegelte (client-verschlüsselte) Manifeste, KDF-Parameter und eine Byte-Quota.
**Alle Ver-/Entschlüsselung passiert in der App.** Das Bearer-Token beweist nur
**Identität** — es entsperrt nichts. Der Vault-Key (VK) wird aus der Passphrase
abgeleitet und verlässt das Gerät nie.

Konsequenz: Es gibt keine „REST-API die Klartext-Dateien liefert". Die App bekommt
Ciphertext + versiegeltes Manifest und entschlüsselt selbst. Wer das ignoriert,
bricht das ganze Sicherheitsmodell.

---

## 1. Server-Basis-URL

Self-hosted, also variabel. Die App bekommt die Base-URL **aus dem QR-Code beim
Pairing** (§2). Alle Endpunkte sind relativ dazu, nur über **HTTPS/TLS** (SPKI-Pinning,
fail-closed). Referenz-Instanz: `https://home.kiefer-networks.de`.

---

## 2. Authentifizierung — QR Device Pairing

Kein OIDC in der App. Der Nutzer ist im **Web** (per Pocket-ID) eingeloggt und
autorisiert dort jedes neue Gerät.

1. **Web-Profil** → „Gerät verbinden" erzeugt einen QR mit Deep-Link:
   `ledgerline://pair?url=<url-encoded base_url>&code=<one-time-code>` (256-bit,
   ~2 min gültig; der echte Token steht **nicht** im QR).
2. **App** scannt (CameraX + ML Kit Barcode), parst `url` + `code`.
3. **App** claimt: `POST {url}/api/v1/auth/pair` `{ code, device_name }` → `{ status:'pending' }`.
4. **Web** bestätigt „Gerät <device_name> verbinden?".
5. **App** pollt `POST {url}/api/v1/auth/pair/collect` `{ code }` alle ~2 s:
   - `pending` → weiter; **einmalig** `approved` → `{ token, user }`; danach `410`.

   > **Behoben 2026-07-25:** der Poll war früher `GET /auth/pair?code=`; der Server hat
   > ihn auf `POST /auth/pair/collect { code }` verschoben (GET 405t jetzt) → Kopplung
   > schlug mit NETWORK fehl. `pollPair` in `LedgerlineApi` ist umgestellt (Test
   > `NetworkFactoryPairingTest` prüft POST + Pfad).
6. App speichert das **Bearer-Token** in **EncryptedSharedPreferences** (Keystore),
   optional Biometric davor. Header `Authorization: Bearer <token>` für alle `/api/v1`.

**Token-Modell:** langlebiges Sanctum-Token, einzeln widerrufbar. **Kein Refresh** —
widerrufen/verloren → neu pairen. Rate-Limit `/auth/pair`: 30/min/IP; bei `429` Backoff.

**Auth-/Konto-Endpunkte (Bearer, außer /auth/pair):**
| Methode | Pfad | Zweck |
|---|---|---|
| POST | `/api/v1/auth/pair` | Code claimen |
| POST | `/api/v1/auth/pair/collect` `{code}` | Pollen; nach Freigabe `{token,user}` (einmalig) |
| GET | `/api/v1/me` | `{ user, usage, wipe?, prefs }` (Remote-Wipe-Flag, §Kill-Switch) |
| GET | `/api/v1/avatar` | IdP-Avatar streamen |
| POST | `/api/v1/device/heartbeat` | Idle/Sync melden → Wipe-Flag |
| DELETE | `/api/v1/auth/session` | Aktuelles Token widerrufen (Logout) |
| GET/DELETE | `/api/v1/devices` · `/devices/{token}` · `POST /devices/{token}/wipe` | Geräte-Verwaltung + Remote-Wipe (**noch nicht in Android**) |
| GET | `/api/v1/notifications` · `POST .../read` · `.../read-all` | Benachrichtigungen (**noch nicht in Android**) |
| POST | `/api/v1/locale` · `/theme` · `/preferences` | Sprache/Theme/Einheiten (server-seitig) |
| GET | `/api/v1/account/export` · DELETE `/account` | Export / Krypto-Shred (**noch nicht in Android**) |

---

## 3. API-Referenz — `/api/v1` (Store v3)

Alle Payloads sind **opak** (Ciphertext / versiegeltes Manifest / KDF-Params).
Owner-scoped (fremder Blob → `404`). **Wichtige Änderung ggü. der ersten Fassung:**
der Monolith `GET/PUT /api/v1/store` ist **serverseitig entfernt** — die Route ist
`/store/{module}` (`whereAlpha`), bare `/store` **404t**. Der Workspace nutzt jetzt die
**per-Modul-Stores** (notes/todos/bookmarks/contacts, §14 R0 erledigt); Gallery **und
Files** nutzen jetzt den v3-Sharded-Store (§14 R1 erledigt 2026-07-26).

### Vault (Entsperren)
- `GET /vault` → `{ configured, salt, kdf_ops, kdf_mem, wrapped_vault_key,
  wrap_nonce, has_recovery, wrapped_vault_key_recovery, recovery_nonce }`. Siehe §4.

### Store v3 — pro Modul ein versiegelter Store
- `GET /store/{module}` → `{ ciphertext|null, version }` (ETag/304).
- `PUT /store/{module}` `{ ciphertext, version, shards?[] }` → `{ version }`; **`409`**
  bei Versionskonflikt (neu laden → mergen → mit neuer `version` erneut PUT; optimistic
  concurrency, Fremd-Keys IMMER erhalten). `422 missing_shard` bei referenzieller Lücke.
  - `module ∈ { notes, todos, bookmarks, contacts, invoices, passwords, health, sharing, explore }`.
- **Files-Index (sharded, eigener Store):** `GET/PUT /files/store`.
- **Gallery-Index (sharded):** `GET/PUT /gallery/store`.
- **Shared-Vault-Manifest:** `GET/PUT /vaults/{vault}/store`.

> **Sharded-Store-Format (Files/Gallery, „v3"):** der Store-Ciphertext entsiegelt zu
> einem kleinen **Root** `{ v:3, shards:[…], collections:[…] }`; die eigentlichen
> Records liegen in separaten, content-addressed **Shard-Blobs** (id-Buckets) und
> **Collection-Blobs** (Alben/People/Ordner). Laden = Root ziehen → Shard-/Collection-
> Blobs parallel entschlüsseln. Speichern = „dirty-save": nur geänderte Buckets +
> geänderte Collections + Root neu versiegeln, verwaiste Blobs freigeben, PUT mit
> 409-Retry. Records als roher `JSONValue` halten → Fremd-Felder überleben read-modify-write.
> (Android liest Gallery-v2 heute inline; v3-Migration nötig, §14.)

### Blobs (Files / Gallery / Contacts / Explore / Vault) — opake Inhalts-Bytes + Quota
Identisches Muster je Prefix (`files`, `gallery`, `contacts`, `explore`, `vaults/{vault}`):
- `GET /{p}/usage` → `{ used, quota }`
- `GET /{p}/raw/{blob}` → **Ciphertext-Bytes** (octet-stream), §4 entschlüsseln
- `POST /{p}/raw-batch` `{ blobs:[…] }` → gerahmter Concat (bis 512 Blobs, spart Roundtrips)
- `POST /{p}/upload` (multipart `file`) → `{ id }` (201; <64 MiB)
- `POST /{p}/upload/init` `{ size }` → `{ token, id, partSize }` · `.../part`
  (multipart `token,part,chunk` → `{ part, etag }`) · `.../complete` `{ token, parts[] }`
  → `{ id }` · `.../abort` `{ token }` (S3-Multipart, ≥64 MiB, ≥5-MiB-Parts)
- `DELETE /{p}/blob/{blob}` → `{ deleted:true }` (idempotent)
- `POST /{p}/blobs/reconcile` `{ blobs:[uuid,…] }` → `{ used, quota }` (living-set,
  24 h Grace; Liste = alle vom Manifest referenzierten Blob-IDs)

> **Android-Stand:** nur single-shot `POST /files|gallery|contacts/upload` +
> `raw/{blob}` + `blob/{blob}`. **Fehlt:** `upload/init|part|complete|abort`
> (chunked >64 MiB), `raw-batch`, `files/usage`-Nutzung, `explore/*`, `vaults/*/blobs/*`.

### Gallery-Processing (stateless, Klartext transient, sofort verworfen)
- `POST /gallery/process` (multipart `file` = Klartext) → `{ thumb, medium, motion?, exif,
  place, embedding, phash, faces:[…] }`. Renditions + `meta`-JSON client-verschlüsseln,
  padden, hochladen, im Gallery-Store referenzieren.
- `POST /gallery/analyze` → nur CLIP-Embedding + Faces (deferred Backfill).
- `POST /gallery/embed-text` `{ q }` → `{ embedding }` (semantische Suche, Cosinus lokal).
- `GET /gallery/reverse?lat=&lng=` → `{ place, address }` (Reverse-Geocode; grob gerastert,
  nie server-gecacht; App cached lokal **VK-verschlüsselt**, Coarse-Grid).
- `GET /gallery/geocode?q=` → Vorwärts-Geocode (Ort → Koordinate).

### Öffentliche Share-Links (**noch nicht in Android**)
- Files/Ordner: `POST /files/shares` · `PUT/DELETE /files/shares/{token}`
- Gallery-Alben: `POST /gallery/shares` · `PUT/DELETE /gallery/shares/{token}`
- Sealed Manifest + `blob_refs` + optional Passwort/Ablauf/Download; Share-Key nur im
  URL-Fragment (`#s:`), verlässt den Server nie.

### Cross-User Shared Vaults / Ordner (**noch nicht in Android**, PQ-Krypto)
- Identität: `GET/PUT /vaults/keys` (X25519 **und** ML-KEM-768; write-once, `409` bei Änderung).
- `GET/POST /vaults` · `DELETE /vaults/{vault}` · `GET/PUT /vaults/{vault}/store`
- `POST /vaults/{vault}/resolve-recipient` (enum-resistent) · `GET/POST members` ·
  `PATCH/DELETE members/{member}` · `POST members/{member}/accept` · `POST /vaults/{vault}/rotate`
  (Re-Key bei Mitglied-Entfernen = kryptografische Rücknahme).
- Wrap/Unwrap von VK_vault per **PQ-Hybrid** (§4, unten): X25519+ML-KEM-768 KEM.

### Passwörter (Anreicherung — nichts wird gespeichert; **noch nicht in Android**)
- Records leben im `store/passwords`-Manifest. Server-Hilfen: `GET /passwords/icon`
  (Favicon/BIMI → data-URI), `GET /passwords/breach` (HIBP k-Anonymität, 5-Hex-Prefix),
  `GET /passwords/tfa-directory`.

### Explore / Maps (**noch nicht in Android**)
- Track-Blobs `explore/*`; `GET /maps/route` (Waypoints → Straßen), `GET /maps/resolve`
  (Google-Maps-Shortlink → Koordinate).

### Contacts (Android hat: Avatar-Blobs)
- `GET /contacts/usage` · `POST /contacts/blobs/reconcile` · `POST /contacts/upload`
  · `GET /contacts/raw/{blob}` · `DELETE /contacts/blob/{blob}` · `POST /contacts/notify`
  (Geburtstags-/Jahrestags-Relay, **noch nicht in Android**). Kontakt-Records selbst
  leben im `store/contacts`-Manifest.

Pro-Route-Throttles gesetzt. Bei `429` → Backoff/Retry-After. **Bulk-Delete/Reconcile
client-seitig throtteln** (4 parallele Lanes, 429-aware) — wie der Web-Client.

---

## 4. Krypto-Contract (EXAKT — aus vault.js; unverändert gültig)

Bibliothek: **libsodium** (Android: `com.goterl:lazysodium-android` + JNA).

### Key-Hierarchie
```
Passphrase --Argon2id--> KEK --secretbox-open--> Vault Key (VK, 32 B)
VK --secretbox--> per-blob keys, Datei-Metadaten, Manifest
Recovery-Code (32 B) --generichash--> Recovery Key --secretbox-open--> VK
```

### KDF (Passphrase → KEK)
```
crypto_pwhash(
  outlen  = crypto_secretbox_KEYBYTES (32),
  passwd  = UTF-8 passphrase,
  salt    = base64-decode(vault.salt),        // crypto_pwhash_SALTBYTES = 16
  opslimit= vault.kdf_ops,                     // Setup: OPSLIMIT_SENSITIVE
  memlimit= vault.kdf_mem,                     // Setup: MEMLIMIT_MODERATE
  alg     = crypto_pwhash_ALG_ARGON2ID13
)
```
**`opslimit`/`memlimit` NICHT hardcoden** — immer aus `GET /vault`. Argon2id im
`Dispatcher.Default`.

### VK entsperren (nach `GET /vault`)
```
KEK = deriveKek(passphrase, salt, kdf_ops, kdf_mem)
VK  = crypto_secretbox_open_easy(b64dec(wrapped_vault_key), b64dec(wrap_nonce), KEK)
```
Recovery: `recoveryBytes = from_hex(code ohne Spaces)`; `recoveryKey =
crypto_generichash(32, recoveryBytes)`; `VK = secretbox_open(wrapped_vault_key_recovery,
recovery_nonce, recoveryKey)`. Anzeige-Format hex, 4er-Gruppen.

### „seal/open" = secretbox (Keys, Metadaten, Manifest)
```
seal(data,key): nonce=randombytes(24); cipher=crypto_secretbox_easy(data,nonce,key)
                → { c: base64(cipher), n: base64(nonce) }
open(c,n,key):  crypto_secretbox_open_easy(b64dec(c), b64dec(n), key)
```
Base64 = libsodium `base64_variants.ORIGINAL` (Standard, mit Padding).

### Manifest ver-/entsiegeln (jeder `store/{module}`, `files/store`, `gallery/store`)
```
sealManifest(obj):
  json = JSON.stringify(obj); bucket = 4096
  target = ceil((json.length+1)/bucket)*bucket
  json += " " * (target - json.length)          // 4-KiB-Padmé-Floor, hidet Größe
  { c, n } = seal(utf8(json), VK)
  ciphertext = JSON.stringify({ suite:1, c, n }) // <- Feld "ciphertext"
openManifest(ciphertext):
  { c, n } = JSON.parse(ciphertext); obj = JSON.parse(utf8(open(c, n, VK)))
```
Beim Speichern aktuelle `version` mit-PUTten; bei `409` neu laden + mergen. Der neue
`suite`-Marker: unbekannte Suite → fail-closed.

### Inhalts-Blobs (Dateien, Fotos, Renditions) — secretstream, eigener Key/Blob
```
CHUNK = 4 MiB
Encrypt: fk = crypto_secretstream_xchacha20poly1305_keygen()
  {state,header}=init_push(fk); blob = header ++ für jeden 4-MiB-Slice:
     cipher=push(state,slice,null,isLast?TAG_FINAL:TAG_MESSAGE)
     frame = u32le(cipher.length) ++ cipher        // 4-Byte LE Längenpräfix
  encFileKey = JSON.stringify(seal(fk, VK))         // {c,n} des gewrappten fk
  encMeta    = JSON.stringify(seal(utf8(JSON({name,mime,size})), VK))
Decrypt (aus GET .../raw/{blob}):
  fk = open(JSON.parse(encFileKey), VK); state=init_pull(bytes[0..24], fk); off=24
  loop: len=u32le(bytes[off..off+4]); off+=4; {msg,tag}=pull(state,bytes[off..off+len])
        off+=len; append msg; bis tag==TAG_FINAL
```
Konstanten: HEADERBYTES 24, ABYTES 17. Framed-Größe: `HEADER + total + chunks*(ABYTES+4)`.

### Padmé-Padding vor Upload (Größen-Hiding)
```
padmeSize(n): e=floor(log2(n)); s=floor(log2(e))+1; bits=e-s
              if bits<=0: return n; mask=(1<<bits)-1; return (n+mask) & ~mask
padBlob(blob): pad = padmeSize(size)-size; blob ++ pad zufällige Bytes (falls pad>0)
```
Web+iOS machen das für Files UND Gallery. Gallery snappt `created_at` server-seitig auf
die Stunde.

### PQ-Hybrid-KEM (`core/crypto/PQKEM.kt` — IMPLEMENTIERT + byte-exakt, 2026-07-25)
Für geteilte Vaults/Ordner wird VK_vault per **Hybrid-KEM** gewrappt: `MLKEM768`
(64-Byte-Seed, **BouncyCastle 1.84 FIPS-203**) **und** `X25519` (libsodium scalarmult),
kombiniert via `HKDF-SHA256(ss_ec‖ss_pq, salt=∅, info="ledgerline/kem/v1"+ctx)` (BC HKDF),
dann `crypto_secretbox`. Envelope `{suite:1,epk,kem_ct,c,n}` (base64 ORIGINAL). ML-KEM-Leg
in `MlKem768` (sodium-frei, unit-testbar). **Byte-exakt zu Web `shared/pq-kem.js` + iOS
`PQKEM.swift`:** ML-KEM gegen die FIPS-203-NIST-KAT verifiziert (`PQKEMKatTest`,
seed→ek/ct/ss byte-gleich), Hybrid-Roundtrip on-device (`PQKEMInstrumentedTest`,
fail-closed bei falschem Context/Recipient/Suite). TOFU-Anker = X25519-Fingerprint (Identity
+ Sharing-Flows = §14 R-S2/S3, folgen).

---

## 5. Datenmodell (Manifest-Schemata)

Client-versiegeltes JSON. **Nur additive Felder annehmen; unbekannte Felder beim
Schreiben ERHALTEN (roher JSONValue, nicht in getippte Modelle zwingen — sonst
Datenverlust).** Ground truth: `../ledgerline/resources/js/app.js`.

**Per-Modul-Stores** (jeweils `store/{module}`, entsiegelt):
```jsonc
// store/notes      → { "notes":     [ { id, title, content /*md*/, tags[], trashed, … } ] }
// store/bookmarks  → { "bookmarks": [ { id, url, title, description, tags[], folder, … } ] }
// store/todos      → { "todos":     [ { id, title, done, list, due, … } ] }
// store/contacts   → { "contacts":  [ { id, name, fields[], avatarRef?, avatarKey?, personIds?[], … } ] }
// store/passwords  → { "secrets":   [ { id, type, name, vault, tags[], fields…, totp?, favorite, trashed } ] }
// store/sharing    → TOFU-Fingerprint-Map für geteilte Vaults
```

**Files-Index** (`files/store`, sharded v3):
```jsonc
{ "files":   [ { id, blob, encFileKey /*{c,n}-JSON-string*/, name, mime, size,
                 folder /*id|null*/, tags[], favorite, trashed, created,
                 versions:[ { blob, encFileKey, created, … } ], share? } ],
  "folders": [ { id, name, parent, share? } ] }
```
Datei laden: `GET /files/raw/{file.blob}` → mit `encFileKey` entschlüsseln (§4).

**Gallery-Index** (`gallery/store`, sharded v3):
```jsonc
{ "photos": [ { id, media_type /*image|video*/, sig,
                originalRef,originalKey, thumbRef,thumbKey, mediumRef,mediumKey,
                motionRef?,motionKey?, metaRef,metaKey, faceCropRefs[],
                lat,lng, deviceAssetId?, trashed, created, … } ],
  "albums": [ { id, name, photoIds[], cover, created, share? } ],
  "people": [ { id, name, hidden, centroid[], contactId?,
                faces:[ { photoId, idx, cropRef, cropKey } ] } ] }
```
`metaRef` entsiegelt zu `{ exif, place, embedding, phash, faces:[{embedding,cropRef,cropKey}] }`.
`sig` = SHA-256 der Original-Bytes (Exakt-Dedup, §13).

---

## 6. Kern-Flows

**Entsperren:** `GET /vault` → Passphrase → VK ableiten (§4) → VK nur im Speicher; auf
Hintergrund/Idle löschen (außer opt-in „Remember-Vault", §Security).

**Datei/Foto ansehen:** Modul-Manifest laden → entsiegeln → Ref+Key aus Eintrag →
`GET .../raw/{blob}` → secretstream-entschlüsseln → anzeigen. Thumbnails lazy + cache-first.

**Datei-Upload:** bytes → secretstream → padBlob → `POST /files/upload` (chunked >64 MiB) →
Eintrag ins `files/store`-Manifest → dirty-save PUT.

**Foto-Upload:** Original verschlüsseln+padden+upload; parallel Klartext an
`POST /gallery/process` → Renditions+meta verschlüsseln+padden+upload → Photo-Eintrag mit
allen `*Ref`/`*Key` → `PUT /gallery/store`.

**Löschen:** Eintrag entfernen → PUT store → `DELETE .../blob/{blob}` je freiem Blob
(throtteln, 429-aware). Alternativ periodisch `reconcile` mit allen referenzierten IDs.

---

## 7. Tech-Stack (Ist-Stand)
- **Kotlin** + **Jetpack Compose** (Material 3), **Hilt** DI, **Retrofit**+**OkHttp**
  (Bearer-Interceptor, SPKI-Pinning, 429-Backoff), **lazysodium** (Argon2id im
  `Dispatcher.Default`, VK in-memory), **CameraX**+**ML Kit** (QR), **MapLibre**
  (Galerie-Karte, geclusterte GeoJSON-Marker), **EncryptedSharedPreferences**
  (Keystore, optional Biometric), **WorkManager** (Backup/Sync while-alive).
- `minSdk 36`, `targetSdk 36`, `compileSdk 37`. Paket `de.ledgerline.app`.
- Schichten: `ui/*` (Compose-Screens + ViewModels), `domain/*` (Modelle, UseCases),
  `data/*` (Repositories, `remote/LedgerlineApi.kt`, offline-Cache), `core/*`
  (crypto, security, ops, backup, offline), `di/*`.

---

## 8. Design-System — iOS-Look (indigo/violet, adaptiv)

Ziel: die App trifft den **iOS-Stil** byte-nah. Tokens gespiegelt aus
`../ledgerline-ios/App/DesignSystem/{Theme,Components}.swift`.

**Palette (Brand):** Akzent-Verlauf **Indigo `#7066F5` → Violett `#9E70FA`**
(diagonal top-left→bottom-right), fix in Light **und** Dark. Kategorie-Tints (Icon-Chips,
web-aligned): blue `#3B9FD6`, green `#59AD6B`, orange `#E2915A`, teal `#3FAE9F`,
violet `#9E70FA`, gray `#6B7280`.

**Schemes:** vollständige M3 Light- **und** Dark-Schemes, indigo-Familie, kein Dynamic
Color. Aktives Scheme folgt der System-Einstellung (adaptiv, wie iOS). Dark =
„Vault-Depth" (`#131318` Grund, faint indigo). Files:
- `ui/theme/Color.kt` — Light-/Dark-Rollen (`Light*`/`Dark*`).
- `ui/theme/Theme.kt` — `LedgerlineTheme(darkTheme = isSystemInDarkTheme())`.
- `ui/theme/Type.kt` — M3-Typescale, Platform-Font (privacy, kein Bundle).
- `ui/theme/Shape.kt` — Corner-Ramp 6/10/14/20/28 dp (etwas runder = Expressive).
- `ui/theme/Brand.kt` — **das Design-System**: `Brand` (accent/accentSoft/tints/
  `accentGradient`/Metriken), `IconChip`, `HeroIcon`, `Modifier.cardSurface()`,
  `PrimaryGradientButton`, `SecondaryBrandButton`, `LedgerlineBackground` (Grund +
  zwei blasse Akzent-Glows). **Neue UI nutzt diese Bausteine.**

**Metriken (iOS-gleich):** cardRadius 18, chipRadius 12, chipSize 38, cardPadding 16,
screenPadding 20 dp.

**System-Bars:** `MainActivity` wählt SystemBarStyle + `isAppearanceLightStatusBars`
nach `uiMode` (Light→dunkle Icons, Dark→helle). XML-Fallback-Theme: `values/themes.xml`
(Light) + `values-night/themes.xml` (Dark), Fenster-Background aus `values[-night]/colors.xml`.

**App-Icon:** Shield + weißes Vorhängeschloss auf Indigo→Violett-Verlauf (wie iOS).
Adaptive Icon: `drawable/ic_launcher_background.xml` (Gradient-Vektor),
`drawable/ic_launcher_foreground.xml` (transluzenter Shield + weißes Schloss),
`drawable/ic_launcher_monochrome.xml` (themed icons). In-App-Mark:
`drawable/ic_ledgerline_logo.xml` (Gradient-Shield + weißes Schloss).

**Navigation (Ist Android):** Bottom-Bar Files · Gallery · Todos · Notes + „More"
(Bookmarks, Contacts, Settings) in `ui/workspace/WorkspaceScaffold.kt`. **iOS** hat
stattdessen Passwords · Files · Gallery · Settings. Angleichung/Passwords-Tab = Roadmap
(§14) — keine harte Anforderung dieses Passes.

**Verifikation:** `FLAG_SECURE` blockt `adb screencap` (schwarz) — visuelle Änderungen
muss der Nutzer am Gerät prüfen. Immer light **und** dark testen.

---

## 9. Versionierung / Stabilität
- Basis-Pfad `/api/v1`. Additiv; unbekannte Felder ignorieren/erhalten. Breaking →
  `/api/v2` (`Sunset`-Header) + In-App-„bitte updaten".
- **Eingefroren (Transport-Contract):** Blob-UUIDs, `{ciphertext,version}`-Envelope,
  KDF-Feldnamen, Upload/Chunk-Protokoll, secretstream-Framing, Padmé.
- **App-Sache:** Klartext-Manifest-Schema darf additiv wachsen.

## 10. Sicherheits-Checkliste
- Nur HTTPS + SPKI-Pinning fail-closed. Token in Keystore, Access-Token bevorzugt nur RAM.
- VK nie unverschlüsselt persistieren; bei Hintergrund/Idle löschen. (Ausnahme: opt-in
  „Remember-Vault" = biometric-versiegelter VK mit TTL — bewusste, dokumentierte Abweichung.)
- Padmé vor jedem Blob-Upload. Kein Klartext-Cache auf Disk. Temp-Dateien überall löschen.
- Bulk-Blob-Deletes throtteln (429-aware, Retry-After). Nie VK/Passphrase/Recovery loggen/senden.
- `FLAG_SECURE` (Screenshot/Recording/Recents-Block) an. Remote-Wipe-Kill-Switch (`/me`
  `wipe:true` → alles lokal löschen + neu pairen), geprüft bei Unlock + BackgroundSync.

## 11. Offline-Verfügbarkeit (Kern-Anforderung)
Kein Sync außerhalb dieser App. **Lokaler Cache = Ciphertext** (versiegelte Manifeste +
Blob-Bytes, wie vom Server). Entschlüsselt nur in-memory bei Zugriff (VK nötig). Gesperrt =
kein Zugriff. Online → ziehen+cachen; offline → aus Cache. Schreib-Ops offline in **Queue** →
bei Reconnect Manifest-PUT mit optimistic `version` (409 → merge, last-write-wins pro Feld);
Blob-Uploads resumable nachholen. Settings: Master-Schalter „Alles offline" + pro Modul
(Files/Gallery/Notizen/Lesezeichen/Todos), Blob-Strategie (alle/Favoriten/Thumbnails/aus),
Wi-Fi-/Laden-Constraint, Cache-Limit + „Cache leeren" pro Modul.

## 12. Share-Target (Kern-Anforderung)
App als `ACTION_SEND`/`ACTION_SEND_MULTIPLE`-Ziel. `image/*`,`video/*` → Gallery-Import;
alles andere → Files-Import (Ziel-Ordner wählbar). Kurzes Bestätigungs-Sheet. Offline → Queue.
Gleiche Verschlüsselungs-/Upload-Logik inkl. Padmé.

## 13. Roadmap (später): Immich-artiger Auto-Upload
Hintergrund-Backup gewählter Geräte-Alben (WorkManager, Wi-Fi/Laden), resumable, Status-UI.
On-Device-Exakt-Dedup via `sig` (SHA-256 Original) vor Upload. EXIF/GPS/Zeit lokal auslesen.
Motion-Photos als `motionRef`. **Nur bei entsperrtem Vault** (kein app-closed Sync — Token
ist biometric-versiegelt).

---

## 14. Parität & Gaps — Android vs iOS/Web (verbindliches Register)

Web (`v1.505.x`) ist der Superset; beide Mobile-Clients hängen unterschiedlich hinterher.
iOS (`v0.6.x`) ist beim Contract + Sharing/Passwords voraus, hat aber Notes/Todos/Bookmarks/
Contacts NICHT. Ehrlich geführt, nicht schöngeredet.

**P0 — Store-v3-Contract-Drift:**
1. **Workspace-Module-Stores — ERLEDIGT (2026-07-24).** `WorkspaceRepository` fächert
   jetzt auf `GET/PUT /store/{notes,todos,bookmarks,contacts}` auf (per-Modul-Version,
   409-Merge pro Modul, per-Modul-Offline-Cache `workspace_<mod>`). App-Contract
   (`WorkspaceManifest` + `save(mutate)`) unverändert; Tests grün (`WorkspaceSaveTest`,
   `OfflineStoreLoadTest`). Modul-Wire-Shapes = `NotesManifest`/`TodosManifest`/
   `BookmarksManifest`/`ContactsManifest` (`v:3`, byte-gleich zu Web `MODULE_BLANKS`).
   `moduleStore()`/`putModuleStore()` in `LedgerlineApi`; altes `store()` @Deprecated.
2b. **Gallery-v3-Sharded-Write — ERLEDIGT (2026-07-25).** `GalleryRepository` schreibt jetzt
   den v3-Sharded-Root (Photos → Shard-Content-Blobs, Albums/People → Collection-Blobs,
   `shards[]`-Guard, 409-Rebase, Dirty-Save) und **liest Collection-Blobs** (Albums/People
   waren bei web-geschriebenem v3 vorher leer). Byte-exakte Bausteine: `CanonicalJson`
   (code-point-sortiert, ECMAScript-Escaping, dec6 — gegen die Web-`canonical-json`-Fixture
   verifiziert), `GallerySharding` (bucketOf/recommendedShardBits/shardHash), `GalleryShardWriter`
   (Dirty-Save-Engine, Unit-getestet). `StorePutRequest` trägt jetzt optionales `shards[]`.
   **Byte-exakte Record-Parität + kein Datenverlust:** `GalleryRecordCodec` serialisiert
   Photo/Album/Person **byte-identisch zum Web** — encodet aus dem originalen Raw-JsonObject
   (bei Load erfasst) und überlagert nur Android-editierte Felder web-gerendert (lat/lng→dec6-
   String, trashed→ISO/null, Faces web-shape nur bei Änderung). Unbekannte Web-Felder + rohe
   Float-Tokens (`centroid` `1e-7`) bleiben verbatim erhalten (kotlinx bewahrt das Zahlen-Literal
   → kein JS-Float-Formatter nötig). Verifiziert gegen eine **web-generierte Fixture**
   (`app/src/test/resources/gallery-record-canonical.json`, `GalleryRecordCodecTest`).
   **Chunked Upload (2026-07-25):** `ChunkedUpload` (S3-Multipart, `init→part…→complete`/`abort`,
   verschlüsselt zu Temp-Datei → Teile ≥ Server-`partSize`, konstanter Speicher) für Dateien
   ≥64 MiB; `FileBlobRepository.upload` schaltet ab dem Schwellwert um. API/DTOs für Files
   **und** Gallery vorhanden (`{files,gallery}/upload/{init,part,complete,abort}`).
   **Offen:** Gallery-Original streamt noch aus In-Memory-Bytes (`GalleryUploader`) — für
   Riesen-Videos den Import auf Stream-from-URI + Chunked umstellen (OOM-Fix, wie iOS).
2. **Files-Store (sharded `/files/store`) — ERLEDIGT (2026-07-26, on-device-Verifikation offen).**
   `WorkspaceRepository` lädt/schreibt die Files-Slice jetzt über den v3-Sharded-Store (identische
   Engine wie Gallery), der App-Contract `WorkspaceManifest`/`save(mutate)` bleibt unverändert —
   `FilesViewModel`/UI/`FileOps` sind unberührt. Bausteine: `FilesRoot`/`GalleryShard`-Descriptor,
   `FilesShardWriter` (Dirty-Save, eine `fileFolders`-Collection → `foldersRef/foldersKey/foldersHash`,
   `GallerySharding`+`CanonicalJson` wiederverwendet), `FileRecordCodec` (Raw-Overlay wie
   `GalleryRecordCodec` → **kein Datenverlust** bei web-Feldern `encMeta`/`note`/`share`/… + `trashed`
   ISO-or-null), `GET/PUT /files/store` in `LedgerlineApi`. Load: Root → Shard-Blobs parallel +
   `fileFolders`-Collection; Save: Dirty-Save + Seal + PUT mit `shards[]`-Guard + 409-Rebase. Tests
   `FilesShardWriterTest` + `WorkspaceSaveTest`. **Offen:** Offline-Cache der Files-Slice (heute
   online-only — transienter Netzfehler ⇒ leere Files, kein Crash), iOS' 422-`missing_shard`-Full-
   Rederive + degraded-Read-Freeze (Gallery fehlt beides ebenfalls). Root-Schema: `{v:3,suite:1,
   shardBits,shards[],caps:{},foldersRef/Key/Hash}`; Bucket = `uint32(hexPrefix8(id)) >>> (32-shardBits)`.
3. **Gallery-v3-Sharded schreiben** (liest heute schon v2/v3-Shards) + **Chunked Upload**
   (`upload/init|part|complete|abort`) für Dateien/Videos >64 MiB; `raw-batch` für Thumbnail-Batches.

**P1 — Feature-Paritätslücken (iOS/Web haben, Android nicht):**
- **Passwort-Manager — GRUNDGERÜST ERLEDIGT (2026-07-26).** `store/passwords`-Modul via
  `PasswordsRepository` (409-Merge), `SecretItem`-Model (opake `fields:JsonObject` = verlustfrei,
  9 Typen `SecretTypes`), `PasswordsViewModel` (Liste/Suche/Filter/Favoriten/Trash/Versionen),
  UI `ui/passwords/PasswordsScreen` (Liste→Detail→Edit, Typ-Picker, Live-TOTP, Reveal/Copy,
  Generator). Logik: `Totp` (RFC-6238 SHA1/6/30, gegen RFC-Vektor getestet), `PasswordStrength`
  (pwScore), `BreachCheck` (HIBP k-Anon), `PasswordGenerator`. Enrichment-API
  (`/passwords/{breach,icon,tfa-directory}`) + DTOs vorhanden. **Passwords ist jetzt primärer
  Tab** (Todos ins More-Sheet). Secret-Copy nutzt `SecureClipboard` (sensitive + Auto-Clear).
  **Autofill — ERLEDIGT (2026-07-26, on-device-Verifikation offen):** `AutofillService`
  (`core/autofill/LedgerlineAutofillService`) ist **zero-knowledge** — `onFillRequest` inspiziert
  nur die `AssistStructure` (Feld-Klassifikation `AutofillParsing`: AutofillHints + Heuristik) und
  liefert **eine** auth-gated Dataset-Zeile. Tap öffnet `AutofillUnlockActivity` (FragmentActivity,
  reused `UnlockScreen`+`VaultAuthorizers` wie ShareActivity): entsperrt (Biometrie/Passphrase),
  matcht Credentials an Domain/Package (`DomainMatch`: eTLD+1 + Package-Token, Test
  `DomainMatchTest`), zeigt Picker, gibt `Dataset` zurück. `onSaveRequest` bietet neu eingegebene
  Logins zum Speichern an (`SecretItem` login). Manifest: Service (`BIND_AUTOFILL_SERVICE`) +
  `xml/autofill_service`; aktivieren via Settings→Autofill (`ACTION_REQUEST_SET_AUTOFILL_SERVICE`).
  Kein Klartext verlässt die App vor Auth. Strings EN/DE/RU.
  **Favicon + TFA-Anzeige — ERLEDIGT (2026-07-26).** `SecretAvatar` zeigt das Site-Favicon
  (`GET /passwords/icon?domain=`, server-proxied → data-URI; `Favicons`-Decoder für PNG/JPEG,
  SVG/ICO-Fallback auf Typ-Icon; VM cacht pro Domain) in Liste + Detail, sonst Typ-`IconChip`.
  Detail zeigt eine „Diese Seite bietet 2FA"-Zeile für Logins **ohne** TOTP, deren Domain in der
  `GET /passwords/tfa-directory` (2fa.directory) steht — mit „Einrichten"-Link (Doku-URL). Match
  = Host + Parent-Domains (web `_tfaMatch`). Strings EN/DE/RU.
  **Passkeys — ERLEDIGT (2026-07-26, on-device-Verifikation offen).** App ist WebAuthn-
  **Credential-Provider** via Android Credential Manager (`androidx.credentials` 1.6.0). Krypto-Kern
  (byte-exakt zu Web `passkey.js` + iOS, unit-getestet `PasskeyCryptoTest`): `core/passkey/` —
  `P256Key` (JCA secp256r1/ES256, JWK `{kty,crv,d,x,y}`, DER-Signatur), `WebAuthnCbor` (hand-rolled
  COSE_Key + authData create 0x5D/assert 0x1D + none-Attestation, kein CBOR-Lib), `PasskeyStore`
  (standalone `passkey`-Item **oder** in `login.fields["passkeys"]` eingebettet; `candidates`/
  `standaloneItem`/`attach`/`rpIdAllowed`), `PasskeyResponses`/`PasskeyRequests` (WebAuthn-JSON).
  `LedgerlinePasskeyService` (BeginCreate→CreateEntry, BeginGet→**AuthenticationAction** da locked)
  + `PasskeyProviderActivity` (unlock-gated wie Autofill/Share; CREATE→generieren+speichern,
  GET→entsperren+Kandidaten enumerieren, ASSERT→signieren). Manifest `BIND_CREDENTIAL_PROVIDER_SERVICE`
  + `xml/passkey_provider`; aktivieren via Settings→Passkeys. **Offen/best-effort:** clientDataJSON-
  Origin für App-Caller (Browser liefern `clientDataHash`, korrekt behandelt; App-Caller-Origin
  `https://<rpId>` provisorisch), on-device-Ceremony-Verifikation.
  **Offen:** geteilte Passwort-Vaults (PQ-Sharing, §S3), Inline-Autofill-Presentations
  (Menu-Presentation, `Dataset.Builder(RemoteViews)` deprecated aber funktional), volle
  RU-Übersetzung der übrigen Passwort-UI-Strings.
- **Öffentliche Share-Links** (Files/Gallery, `*/shares`, Key im URL-Fragment).
- **Cross-User Shared Vaults/Ordner** (Rollen, Rotation, TOFU) + **PQ-Hybrid-KEM** (§4).
- **Passkeys/WebAuthn.**
- **Geräte-Verwaltung + Remote-Wipe-UI**, **Notifications**, **Konto-Export/Löschen**.
- **Explore/Maps** (Tracks, Routing), **Health-Modul**, **Invoices-Modul**.
- Galerie-ML-Parität: semantische Suche (embed-text ist da), Duplikate, Alben-Feinschliff.

**P2 — Nav-/UX-Angleichung an iOS:** Tab-Struktur (Passwords-Tab), Grouped-List +
IconChip überall, Wischgesten konfigurierbar, Info-Sheets.

**Android-only (iOS fehlt):** Notes/Todos/Bookmarks-Tabs, Geräte-Adressbuch-Sync
(vault↔device Contacts). Nicht wegwerfen — Web hat diese Module ebenfalls.

**Dieser Pass liefert:** Design-System + adaptives Light/Dark-Theme + neues Icon +
Gradient-CTAs auf Chrome (Unlock/Welcome) + dieses Register. Store-v3 + Feature-Parität =
Folge-Arbeit (siehe Spec `docs/superpowers/specs/2026-07-24-ios-parity-and-restyle-design.md`).

---

## 15. SECURITY-STANDARD & GAP-REGISTER (verbindlich)

Ehrlich geführt wie in der iOS-App (`../ledgerline-ios/CLAUDE.md`) — nicht
schöngeredet, nicht erfüllte Punkte stehen offen hier. Ausführliche
Nutzer-Transparenz: `SECURITY.md`. Dimensionen an den iOS-„Security-Engineering"-
Standard angelehnt (ZK, Primitives, at-rest, Transit, Side-Channels, PQ, Metadaten,
Supply-Chain). **Bei jeder Änderung mitpflegen.**

**Erfüllt / großteils:**
- **ZK (Kern):** Server nur Ciphertext; VK on-device abgeleitet, nur in-memory,
  auf `ON_STOP` + 5-min-Idle genullt. Kein Klartext at-rest (Offline-Cache =
  Ciphertext). Einziges persistiertes Secret: Session-Blob (AES-256-GCM, Keystore).
- **Vetted Primitives:** libsodium (Argon2id/secretbox/secretstream/generichash),
  byte-exakt zu Web `vault.js`. Kein Custom-Krypto. KDF-Params server-geliefert, nie hardcoded.
- **Transit:** HTTPS erzwungen (`network_security_config` cleartext=false),
  `RESTRICTED_TLS`, **TOFU-SPKI-Pinning** (leaf SPKI-SHA-256 bei Pairing, `CertificatePinner`,
  fail-closed). Alle Payloads opak.
- **At-rest / Keystore:** AES-256-GCM-Key im Keystore (StrongBox wo verfügbar),
  `setUserAuthenticationRequired(true)` + `setInvalidatedByBiometricEnrollment(true)`.
  **Per-use `CryptoObject`-gebundene Biometrie** (ein Prompt authorisiert genau den
  Keystore-Decrypt, keine Zeitfenster-Lücke). `allowBackup=false`, `fullBackupContent=false`,
  Data-Extraction-Rules schließen Cloud-/Device-Transfer aus.
- **Side-Channels (teilweise):** `FLAG_SECURE` app-weit (Screenshot/Recording/Recents-Block).
  Kein Secret-Logging (R8 strippt Debug; CI-Greps). **Idle-Lock monotonic** (`IdleLocker`
  elapsedRealtime — Wall-Clock-Sprung verschiebt Lock nicht).
- **Clock-Rollback-Guard (2026-07-25):** forward-only Wall-Clock-High-Water
  (`ClockRollbackGuard`, Keystore-versiegelt); ein Rückwärts-Sprung > 5 min sperrt den
  passphrase-freien Remember-Vault-Pfad (fail-closed → Passphrase). Gate in `UnlockViewModel`.
- **Constant-Time-Compare (2026-07-25):** `ConstantTime.equal` für Secret-Vergleiche
  (TOFU-Fingerprints etc.). Uniformer opaquer Decrypt-Fehler: `SodiumCrypto` liefert für
  alle Fehlermodi `null` (ununterscheidbar).
- **Unlock-Throttle (2026-07-25):** monotoner exponentieller Lockout (`UnlockThrottle`,
  elapsedRealtime, 3 Freiversuche → 2s/4s/8s… cap 300s, **nie destruktiv**). Gate im
  `UnlockViewModel`; nur echte Passphrase-Fehler zählen. Tests `SecurityGuardsTest`.
- **Duress-Auto-Wipe (2026-07-25):** nach X falschen Passphrasen alles Lokale löschen
  (`DuressGuard`+`WipePolicy`, Schwellen {3,5,10,15,20}, **Default 10, immer an, kein Aus** —
  wie iOS). Zähler Keystore-versiegelt (überlebt Force-Quit). Nur echte Passphrase-Fehler
  (kein Biometric/Recovery/Transport). Wipe reused den Remote-Wipe-Pfad (`AuthEventBus.emitWipe`
  → `ForceLogout`). Settings-Warnung im Footer.
- **Security-Audit-Log (2026-07-25):** verschlüsseltes, gerätelokales Ereignis-Log
  (`SecurityLog`, Keystore-AES-GCM, `requireAuth=false` → schreibt auch pre-unlock;
  Ring 300). Events: PAIRED (Kopplung), UNLOCK_SUCCESS/FAILED, RECOVERY_UNLOCK,
  THROTTLE_LOCKOUT, DURESS_WIPE. Ansicht + „Löschen" in Settings→Sicherheit; von jedem
  Full-Wipe (`ForceLogout`) mitgelöscht.
- **Remote-Wipe-Kill-Switch:** `/me` `wipe:true` → `AuthEventBus`/`ForceLogout` löscht alles
  Lokale + erzwingt Re-Pair; geprüft bei Unlock **und** BackgroundSync.
- **Opt-in „Remember-Vault":** biometric-versiegelter VK mit TTL — bewusste, dokumentierte
  Abweichung von „VK nie persistieren" (§10).

- **Zwischenablage-Härtung (2026-07-26):** `SecureClipboard` — kopierte Secrets als
  `EXTRA_IS_SENSITIVE` markiert (raus aus Clipboard-History/Preview) + Auto-Clear nach 60 s.
  Genutzt beim Passwort-/TOTP-/Card-Copy im Passwort-Manager.

**Teilweise / offen (ehrlich):**
- **Post-Quantum (Krypto-Kern erledigt 2026-07-25):** `PQKEM` (ML-KEM-768 FIPS-203 +
  X25519 + HKDF-SHA256, byte-exakt, KAT + on-device verifiziert, §4).
- **Sharing-Identität (2026-07-25):** `IdentityCrypto`+`IdentityRepository` — X25519+ML-KEM
  erzeugen, Secrets unter VK versiegeln (`Crypto.sealValue`), publish `PUT /vaults/keys`
  (write-once), unwrap fail-closed bei Fingerprint-Mismatch. `ensure()` post-unlock;
  Secrets nur in-memory (clear bei Lock/Logout). Verifiziert `IdentityCryptoInstrumentedTest`.
- **Share-Link-Krypto (2026-07-25):** `ShareCrypto` (SK im URL-Fragment, per-file-key re-wrap
  + Manifest-Seal unter SK), fixture-verifiziert (`ShareCryptoInstrumentedTest`). REST/UI = §14 R-S3/S4.
- **Verbleibend:** Sharing-Flows (Invite/Accept/Rotate, TOFU-Map), Share-REST/UI, Passkeys — §14.
- **Unbekannte Felder (Integrität) — GESCHLOSSEN (2026-07-26, Rebuild-Phase 0).** Notes/Todos/
  Bookmarks/Contacts nutzen jetzt den **Raw-JSON-Overlay** (`WorkspaceRecordCodec`, wie
  `FileRecordCodec`): jeder Record trägt sein originales `@Transient raw:JsonObject`, beim Save
  werden nur die bekannten Felder web-shaped überlagert (presence-aware) → **jedes unbekannte
  Top-Level-Record-Feld von Web/iOS überlebt** den Android-Round-Trip. Zusätzlich: Bookmark-Ordner
  mappen `parent↔parentId` (keine flach-gedrückte Hierarchie mehr), `Contact.vatId` ergänzt,
  `trashed` wird `false | ISO` gerendert (Timestamp nicht mehr auf bool kollabiert), IDs sind
  32-Hex (`Ids.newId`, byte-shape wie Web). Test `WorkspaceRecordCodecTest`.
- **Konto-Kontrolle:** Remote-Devices-Liste/-Widerruf, Notifications, Export/Löschen (`/devices`,
  `/notifications`, `/account/*`) **noch nicht in Android** (nur Web).

**Nicht umgesetzt (backend-/prozess-abhängig, wie iOS deferred):** Metadaten-Schutz
(OHTTP/Privacy-Pass/Cover-Traffic/Padding-Klassen), Binary-Transparency (Reproducible/
Signing/Runtime-Self-Verify), SBOM/Dependency-Scan/HSM-Signing, Audit-Log, Shamir/Social-
Recovery, signierte Server-Zeit für absolute Deadlines. Argon2-Parameter (ops/mem) sind
web-geteilt fixiert → Client kann keinen höheren Floor erzwingen (Byte-Kompat-Zwang).

**Konflikt (eskaliert, nicht still kompromittiert):** Argon2 ops/mem kommen vom Server
(`GET /vault`); ein client-fixierter höherer Floor bräche die Byte-Kompat der KEK/VK zu
Web + iOS. Nur cross-repo gemeinsam änderbar. OFFEN.

---

## 16. Dependency-Aktualität (Policy — verbindlich)

**Alle Libs/Plugins/Abhängigkeiten MÜSSEN immer der neuesten stabilen Version
entsprechen. Das wird VOR JEDEM Build und Release geprüft.** Single source of truth:
`gradle/libs.versions.toml` (Kommentarkopf trägt das letzte Prüfdatum).

Prüfung (Maven-Central + Google-Maven-Metadata, Prereleases ausschließen):
```
# Google Maven (androidx/agp/compose):  https://dl.google.com/android/maven2/<group/path>/<artifact>/maven-metadata.xml
# Maven Central (rest):                 https://repo1.maven.org/maven2/<group/path>/<artifact>/maven-metadata.xml
# je Artefakt letzte <version> ohne alpha|beta|rc|dev|snapshot|M|eap nehmen.
```
Nach jedem Bump: `:app:testDebugUnitTest` + `:app:assembleDebug` grün, Krypto-Interop-
Tests grün (`PQKEMKatTest` on-JVM, `*InstrumentedTest` on-device).

**Bewusst zurückgehalten (dokumentiert, bei jeder Prüfung neu bewerten):**
- **BouncyCastle 1.84** (statt 1.85/1.85.1): ab 1.85 sind bcutil-Klassen in bcprov
  gemergt → `checkDebugDuplicateClasses`-Konflikt mit dem transitiven bcutil. 1.84 ist die
  neueste saubere Version und hat FIPS-203 ML-KEM.
- **Retrofit 2.11.0 / OkHttp 4.12.0 gehalten** (statt 3.0.0 / 5.4.0): der Bump brach on-device
  das QR-Pairing (NETWORK, obwohl der Server den Claim erhielt); Unit-Tests (Fakes + CLEARTEXT-
  MockWebServer) fangen den Real-TLS-Pfad nicht. Erst nach First-Party-Converter + OkHttp-5-TLS-
  Review + On-Device-Pairing-Test re-adoptieren.
- Sonst alle auf neuestem Stand (2026-07-25): AGP 9.3.1, Kotlin 2.4.10, lifecycle 2.11.0,
  camerax 1.6.1, mockk 1.14.11, MapLibre 13.4.1, jna 5.19.1, zxing 3.5.4, BC 1.84,
  androidx.credentials 1.6.0 (Passkeys; 1.7.0 ist alpha → gehalten).
  androidx.autofill 1.3.0 (Inline-Autofill-Presentations).
  (KSP 2.3.10, Hilt 2.60.1, Compose-BOM 2026.06.01 waren bereits latest.)

---

## 17. Referenzen
- Krypto ground truth: `../ledgerline/resources/js/vault.js` · PQ: `../ledgerline/resources/js/pq-kem.js`
- Manifest/Flows: `../ledgerline/resources/js/app.js` (`vaultFiles`, `vaultGallery`)
- API: `../ledgerline/openapi.yaml` · `../ledgerline/routes/api.php`
- iOS-Design: `../ledgerline-ios/App/DesignSystem/{Theme,Components}.swift`
- iOS-Contract: `../ledgerline-ios/Sources/LedgerlineKit/{Store,Net,Crypto}/`
- iOS-Kontext: `../ledgerline-ios/CLAUDE.md`
- Web-Specs: `../ledgerline/docs/superpowers/specs/` (files-redesign, files-api-and-shared-folders,
  pwmanager-tier1, vault-sharing, nav-and-dashboard, health-module, passkeys, zk-gallery)
- Restyle-/Parität-Spec: `docs/superpowers/specs/2026-07-24-ios-parity-and-restyle-design.md`
