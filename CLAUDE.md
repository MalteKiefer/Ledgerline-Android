# Ledgerline Android — Projekt- & Technik-Kontext

Native Android-Client für die selbst-gehostete **Ledgerline** Personal Cloud
(Dateien, Workspace = Notizen/Lesezeichen/Todos, Galerie). Der Server ist ein
Laravel-13-Backend mit einer versionierten Mobile-API unter `/api/v1`.

> **Diese Datei ist der vollständige Bauplan.** Das Backend (API + Auth) ist
> fertig und deployed (ledgerline v1.390.0). Hier drin steht alles, was die App
> braucht: Auth-Flow, API-Referenz, und — am wichtigsten — der **Krypto-Contract**,
> den die App **byte-genau** nachbauen muss. Ground truth für die Krypto ist
> `resources/js/vault.js` im ledgerline-Repo; die Konstanten hier sind daraus
> extrahiert.

---

## 0. Das eine Prinzip: ZERO-KNOWLEDGE

Der Server sieht **niemals** Klartext. Er speichert nur: Ciphertext-Blobs,
versiegelte (client-verschlüsselte) Manifeste, KDF-Parameter und eine Byte-Quota.
**Alle Ver-/Entschlüsselung passiert in der App.** Das Bearer-Token beweist nur
**Identität** — es entsperrt nichts. Der Vault-Key (VK) wird aus der Passphrase
abgeleitet und verlässt das Gerät nie.

Konsequenz: Es gibt keine „REST-API die Dateien liefert". Die App bekommt
Ciphertext + versiegeltes Manifest und entschlüsselt selbst. Wer das ignoriert,
bricht das ganze Sicherheitsmodell.

---

## 1. Server-Basis-URL

Self-hosted, also variabel. Die App bekommt die Base-URL **aus dem QR-Code beim
Pairing** (siehe §2). Alle Endpunkte sind relativ dazu, nur über **HTTPS/TLS**.
Referenz-Instanz: `https://home.kiefer-networks.de`.

---

## 2. Authentifizierung — QR Device Pairing

Kein OIDC in der App. Der Nutzer ist im **Web** (per Pocket-ID) eingeloggt und
autorisiert dort jedes neue Gerät. Ablauf:

1. **Web-Profil** → „Gerät verbinden" erzeugt einen QR mit einem Deep-Link:
   ```
   ledgerline://pair?url=<url-encoded base_url>&code=<one-time-code>
   ```
   Der `code` ist einmalig, ~2 min gültig, 256-bit. Der echte Token steht **nicht**
   im QR.
2. **App** scannt den QR (CameraX + ML Kit Barcode). Parst `url` + `code`.
3. **App** claimt: `POST {url}/api/v1/auth/pair` mit `{ "code": "...", "device_name": "Pixel 8" }`.
   Antwort `{ "status": "pending" }`. (Kein Token — der Nutzer muss erst im Web bestätigen.)
4. **Web** zeigt „Gerät <device_name> verbinden? [Erlauben]". Nutzer bestätigt.
5. **App** pollt: `GET {url}/api/v1/auth/pair?code=<code>` alle ~2 s.
   - solange `{ "status": "pending" }` → weiter pollen.
   - bei Freigabe **einmalig** `{ "status": "approved", "token": "<sanctum>", "user": {...} }`.
   - danach ist der Code verbraucht; erneutes Pollen → `410`.
   - abgelaufen/abgelehnt/unbekannt → `410`.
6. App speichert das **Bearer-Token** sicher (§ Token-Storage) und nutzt es für
   alle weiteren `/api/v1`-Requests: Header `Authorization: Bearer <token>`.

**Token-Modell:** langlebiges Sanctum-Token, einzeln widerrufbar. **Kein Refresh-
Grant.** Wird ein Token widerrufen/verloren → neu pairen (neuen QR scannen).
Der Nutzer sieht/entfernt Geräte im Web-Profil; max. `PAIRING_MAX_DEVICES`
(default 3) — ein neues Pairing über dem Limit wirft das älteste raus.

**Token-Storage (Android):** Token in **EncryptedSharedPreferences** (Android-
Keystore-gebacken). Access-Token nach Möglichkeit nur im Speicher halten und beim
App-Start aus dem Keystore laden. Optional BiometricPrompt davor.

**Auth-Endpunkte:**
| Methode | Pfad | Auth | Zweck |
|---|---|---|---|
| POST | `/api/v1/auth/pair` | public (rate-limited) | Code claimen: `{code, device_name}` → `{status:'pending'}` |
| GET | `/api/v1/auth/pair?code=` | public (rate-limited) | Pollen; nach Freigabe `{status:'approved', token, user}` (einmalig) |
| GET | `/api/v1/me` | Bearer | `{ user:{id,name,email,locale,groups}, usage:{files,gallery} }` |
| DELETE | `/api/v1/auth/session` | Bearer | Aktuelles Token widerrufen (App-Logout) |

Rate-Limit auf `/auth/pair`: 30/min/IP. Bei `429` mit Backoff neu versuchen.

---

## 3. API-Referenz — `/api/v1` (alles Bearer, außer /auth/pair)

Alle Payloads sind **opak** (Ciphertext / versiegeltes Manifest / KDF-Params).
Owner-scoped: ein Token sieht nur die eigenen Daten (fremder Blob → `404`).

### Vault (Entsperren)
- `GET /vault` → `{ configured, salt, kdf_ops, kdf_mem, wrapped_vault_key,
  wrap_nonce, has_recovery, wrapped_vault_key_recovery, recovery_nonce }`
  (oder `{ configured:false }`). Siehe §4 zur Nutzung.

### Workspace-Store (Dateibaum + Notizen/Lesezeichen/Todos = ein versiegeltes Manifest)
- `GET /store` → `{ ciphertext: <string|null>, version: <int> }`
- `PUT /store` `{ ciphertext, version }` → `{ version }` bei Erfolg; **`409`** bei
  Versionskonflikt (anderes Gerät hat inzwischen geschrieben → neu laden, mergen,
  mit neuer Version erneut PUT). Optimistic Concurrency.

### Files-Blobs (opake Inhalts-Bytes + Quota)
- `GET /files/usage` → `{ used, quota }` (Bytes)
- `GET /files/raw/{blob}` → **Ciphertext-Bytes** (octet-stream). Entschlüsseln per §4.
- `POST /files/upload` (multipart, Feld `file`) → `{ id }` (201). Für Dateien
  bis ~2 GB Body; darüber chunked (unten).
- `POST /files/upload/init` `{ size }` → `{ token, id, partSize }` (S3-Multipart starten)
- `POST /files/upload/part` (multipart: `token`, `part`, `chunk`) → `{ part, etag }`
- `POST /files/upload/complete` `{ token, parts:[{part,etag}] }` → `{ id }` (201)
- `POST /files/upload/abort` `{ token }` → `{ ok }`
- `DELETE /files/blob/{blob}` → `{ deleted:true }` (idempotent)
- `POST /files/blobs/reconcile` `{ blobs:[uuid,...] }` → `{ used, quota }`
  (Server gibt eigene Blobs frei, die **nicht** in der Liste sind und älter als das
  Grace-Fenster — so gibt gelöschter Inhalt Quota frei. Die Liste = alle vom
  Manifest noch referenzierten Blob-IDs.)

### Gallery-Store (versiegelter Index: Fotos/Alben/People)
- `GET /gallery/store` → `{ ciphertext, version }`
- `PUT /gallery/store` `{ ciphertext, version }` → `{ version }` / `409`

### Gallery-Blobs (identisch zu Files, Prefix `gallery`)
- `GET /gallery/usage` · `GET /gallery/raw/{blob}` · `POST /gallery/upload`
  · `POST /gallery/upload/{init,part,complete,abort}` · `DELETE /gallery/blob/{blob}`
  · `POST /gallery/blobs/reconcile`

### Gallery-Processing (stateless — für neue Foto-Uploads)
- `POST /gallery/process` (multipart, `file` = **Klartext-Foto/-Video**) → JSON mit
  abgeleiteten Daten: `{ thumb, medium, motion?, exif, place, embedding, phash, faces:[...] }`.
  Der Server verarbeitet in-memory und **verwirft die Bytes sofort** (nichts wird
  gespeichert/geloggt). Die App verschlüsselt die zurückgegebenen Renditions als
  eigene Blobs und referenziert sie im Gallery-Store. Enthält Thumbnails/EXIF/GPS/
  CLIP-Embedding/pHash/Gesichter.
- `POST /gallery/embed-text` `{ q }` → `{ embedding }` (CLIP-Text-Embedding für die
  semantische Suche; Cosinus gegen die gecachten Foto-Embeddings).
- `GET /gallery/reverse?lat=&lng=` → `{ place, address:{…} }` (Reverse-Geocoding eines
  Foto-Standorts für die Viewer-Anzeige). Server nutzt self-hosted Photon zuerst
  (ZK-in-boundary), Fallback auf konfigurierten Nominatim; Koordinate wird vor Egress
  grob gerastert und **nie server-seitig gecacht**. Die App cached das Ergebnis lokal
  **verschlüsselt** (VK-versiegelt, Coarse-Grid). Nur genutzt, wenn das Foto keinen
  `place` im Meta-Blob hat (Server geocodet beim Upload nur bei aktivem
  `GALLERY_GEOCODE_ON_UPLOAD`, Default aus).

Pro-Route-Throttles sind gesetzt (Upload/Chunk großzügig, Blob-Delete 3000/min).
Bei `429` → Backoff/Retry. **Bulk-Delete/Reconcile client-seitig throtteln** (4
parallele Lanes, 429-aware) — der Web-Client macht genau das.

---

## 4. Krypto-Contract (EXAKT — aus vault.js)

Bibliothek: **libsodium** (Android: `com.goterl:lazysodium-android` + JNA). Alle
Werte hier sind die libsodium-Konstanten/Algorithmen, die der Web-Client nutzt.

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
  opslimit= vault.kdf_ops,                     // beim Setup: OPSLIMIT_SENSITIVE
  memlimit= vault.kdf_mem,                     // beim Setup: MEMLIMIT_MODERATE
  alg     = crypto_pwhash_ALG_ARGON2ID13
)
```
**Wichtig:** `opslimit`/`memlimit` NICHT hardcoden — immer die Werte aus
`GET /vault` (`kdf_ops`, `kdf_mem`) nehmen. (Setup nutzt SENSITIVE ops + MODERATE
mem; Argon2id ist rechenintensiv — im Hintergrund-Thread laufen lassen.)

### VK entsperren (nach `GET /vault`)
```
KEK = deriveKek(passphrase, salt, kdf_ops, kdf_mem)
VK  = crypto_secretbox_open_easy(
        base64-decode(wrapped_vault_key),
        base64-decode(wrap_nonce),
        KEK)                                   // wirft bei falscher Passphrase
```
Recovery-Pfad: `recoveryBytes = from_hex(code ohne Leerzeichen)`;
`recoveryKey = crypto_generichash(32, recoveryBytes)`;
`VK = secretbox_open(wrapped_vault_key_recovery, recovery_nonce, recoveryKey)`.
Recovery-Code-Anzeige-Format: hex, in 4er-Gruppen mit Leerzeichen.

### „seal/open" = secretbox (für Keys, Metadaten, Manifest)
```
seal(data, key):  nonce = randombytes(crypto_secretbox_NONCEBYTES /*24*/)
                  cipher = crypto_secretbox_easy(data, nonce, key)
                  → { c: base64(cipher), n: base64(nonce) }
open(c, n, key):  crypto_secretbox_open_easy(b64dec(c), b64dec(n), key)
```
Base64 = libsodium `base64_variants.ORIGINAL` (Standard, mit Padding).

### Manifest ver-/entsiegeln (`/store`, `/gallery/store`)
```
sealManifest(obj):
  json = JSON.stringify(obj)
  bucket = 4096
  target = ceil((json.length+1)/bucket)*bucket
  json += " " * (target - json.length)          // 4-KiB-Padding, hidet Größe
  { c, n } = seal(utf8(json), VK)
  ciphertext = JSON.stringify({ c, n })          // <- das ist das "ciphertext"-Feld

openManifest(ciphertext):
  { c, n } = JSON.parse(ciphertext)
  obj = JSON.parse(utf8(open(c, n, VK)))         // JSON.parse ignoriert das Padding
```
Beim Speichern die aktuelle `version` mit-PUTten; bei `409` neu laden + mergen.

### Inhalts-Blobs ver-/entschlüsseln (Dateien, Fotos, Renditions)
Secretstream XChaCha20-Poly1305, gechunkt, **eigener Key pro Blob**:
```
CHUNK = 4 MiB
Verschlüsseln:
  fk = crypto_secretstream_xchacha20poly1305_keygen()
  {state, header} = ..._init_push(fk)
  blob-bytes = header ++ für jeden 4-MiB-Slice:
       cipher = ..._push(state, slice, null, isLast ? TAG_FINAL : TAG_MESSAGE)
       frame  = u32le(cipher.length) ++ cipher        // 4-Byte LE Längenpräfix
  (leere Datei → genau ein finaler leerer Chunk)
  encFileKey = JSON.stringify(seal(fk, VK))            // {c,n} des gewrappten fk
  encMeta    = JSON.stringify(seal(utf8(JSON({name,mime,size})), VK))

Entschlüsseln (aus GET .../raw/{blob}):
  fk = open(JSON.parse(encFileKey).c/.n, VK)
  state = ..._init_pull(bytes[0..HEADERBYTES], fk)
  off = HEADERBYTES
  loop: len = u32le(bytes[off..off+4]); off+=4
        {message, tag} = ..._pull(state, bytes[off..off+len]); off+=len
        append message; bis tag == TAG_FINAL
```
Konstanten: `..._HEADERBYTES` (24), `..._ABYTES` (17). Framed-Größe eines
Klartexts `total`: `HEADER + total + chunks*(ABYTES+4)`.

### Padmé-Padding vor dem Upload (Größen-Hiding)
Vor dem Upload jeden Ciphertext-Blob auf einen Padmé-Bucket auffüllen (zufällige
Bytes ans Ende — nach den self-delimiting Frames, werden beim Entschlüsseln
ignoriert). So verrät die gespeicherte Blob-Größe nicht die exakte Klartextlänge:
```
padmeSize(n):  e = floor(log2(n)); s = floor(log2(e))+1; bits = e-s
               if bits<=0: return n
               mask = (1<<bits)-1; return (n + mask) & ~mask
padBlob(blob): pad = padmeSize(blob.size) - blob.size
               blob ++ pad zufällige Bytes  (falls pad>0)
```
(Web macht das für Files UND Gallery. Gallery snappt `created_at` server-seitig
auf die Stunde — App muss nichts tun.)

---

## 5. Datenmodell (Manifest-Schemata)

Die Manifeste sind JSON, client-versiegelt. Die App entschlüsselt sie und rendert
daraus. **Nur additive Felder annehmen; unbekannte Felder tolerieren.** Ground
truth für die exakten Felder ist `resources/js/app.js` (Alpine-Komponenten
`vaultFiles` + `vaultGallery`); grobe Form:

**Workspace-Store** (`/store`, entsiegelt):
```jsonc
{
  "files":   [ { "id", "blob", "encFileKey" /*{c,n} JSON-string*/, "name", "mime",
                 "size", "parent" /*folder-id|null*/, "tags":[], "favorite", "trashed",
                 "created", "versions":[ { "blob", "encFileKey", "created", ... } ] } ],
  "folders": [ { "id", "name", "parent" } ],
  "notes":     [ { "id", "title", "content" /*markdown*/, "tags":[], "trashed", ... } ],
  "bookmarks": [ { "id", "url", "title", "description", "tags":[], "folder", ... } ],
  "todos":     [ { "id", "title", "done", "list", "due", ... } ]
}
```
Um eine Datei zu laden: `GET /files/raw/{file.blob}` → mit `file.encFileKey`
entschlüsseln (§4). Name/mime stehen im Manifest im Klartext (das Manifest selbst
ist ja versiegelt). Schreiben = Manifest mutieren → `sealManifest` → `PUT /store`.

**Gallery-Store** (`/gallery/store`, entsiegelt):
```jsonc
{
  "photos": [ { "id", "media_type" /*image|video*/,
                "originalRef","originalKey", "thumbRef","thumbKey",
                "mediumRef","mediumKey", "motionRef?","motionKey?",
                "metaRef","metaKey", "faceCropRefs":[...],
                "sig", "lat","lng", "trashed", "created", ... } ],
  "albums": [ { "id","name","photoIds":[],"cover","created" } ],
  "people": [ { "id","name","hidden","centroid":[...],
                "faces":[ { "photoId","idx","cropRef","cropKey" } ] } ]
}
```
Jedes `*Ref` ist eine Blob-UUID, jedes `*Key` der zugehörige gewrappte Key
(`{c,n}` JSON-string) → `GET /gallery/raw/{ref}` + entschlüsseln. `metaRef`
entschlüsselt zu `{ exif, place, embedding, phash, faces:[{embedding,cropRef,cropKey}] }`.

---

## 6. Kern-Flows

**Entsperren:** `GET /vault` → Passphrase abfragen → VK ableiten (§4) → VK sicher
im Speicher halten (nicht persistieren; auf App-Hintergrund/Timeout löschen).

**Datei/Foto ansehen:** Manifest laden (`GET /store` bzw. `/gallery/store`) →
entsiegeln → Blob-Ref + Key aus dem Eintrag → `GET .../raw/{blob}` → secretstream-
entschlüsseln → anzeigen. Thumbnails lazy laden (nur sichtbare).

**Datei-Upload:** bytes → `encryptContent` (secretstream) → `padBlob` → `POST
/files/upload` (oder chunked bei >64 MB) → `{id}` → Manifest-Eintrag `{id, blob:id,
encFileKey, name, mime, size, parent}` anhängen → `PUT /store`.

**Foto-Upload:** Original verschlüsseln+padden+`upload`; parallel Klartext an
`POST /gallery/process` → Renditions (thumb/medium/motion) + meta (exif/embedding/
phash/faces) → jede Rendition + ein `meta`-JSON-Blob verschlüsseln+padden+`upload`
→ Face-Crops ebenso → Photo-Eintrag mit allen `*Ref`/`*Key` in den Gallery-Store →
`PUT /gallery/store`.

**Löschen:** Eintrag aus dem Manifest entfernen → `PUT store` → `DELETE
.../blob/{blob}` für jeden freigewordenen Blob (client-seitig throtteln, 429-aware).
Alternativ periodisch `POST .../blobs/reconcile` mit allen noch referenzierten IDs.

---

## 7. Tech-Stack-Empfehlung (Vorschlag)
- **Kotlin** + **Jetpack Compose**.
- Krypto: **libsodium** via `com.goterl:lazysodium-android` (+ JNA). Argon2id im
  Dispatcher.Default; VK nur in-memory.
- HTTP: **Retrofit** + **OkHttp** (Bearer-Interceptor, TLS, Timeouts, 429-Backoff).
  Blob-Streams als `ResponseBody`/`RequestBody` (nicht ganz in RAM).
- QR: **CameraX** + **ML Kit Barcode Scanning**.
- Secure Storage: **EncryptedSharedPreferences** (Keystore), optional Biometric.
- JSON: kotlinx.serialization / Moshi.

## 8. Versionierung / Stabilität
- Basis-Pfad `/api/v1`. Additiv; unbekannte Felder ignorieren. Breaking →
  `/api/v2` (Server kündigt via `Sunset`-Header an) + In-App-„bitte updaten".
- Der **Transport-Contract** (Blob-UUIDs, `{ciphertext,version}`-Envelope,
  KDF-Feldnamen, Upload/Chunk-Protokoll, secretstream-Framing) ist eingefroren.
  Das **Klartext-Manifest-Schema** ist App-Sache und darf ohne Server-Bump wachsen.

## 9. Sicherheits-Checkliste
- Nur HTTPS. Token in Keystore, Access-Token bevorzugt nur im Speicher.
- VK nie persistieren; bei App-Hintergrund/Idle löschen (Web: 10-min-Idle-Lock).
- Padmé-Padding vor jedem Blob-Upload (Größen-Hiding) — nicht vergessen.
- Kein Klartext-Cache auf Disk ohne Verschlüsselung. Temp-Dateien auf allen
  Pfaden löschen.
- Bulk-Blob-Deletes throtteln (429-aware Retry mit Retry-After).
- Nie den rohen Vault-Key/Passphrase/Recovery-Code loggen oder senden.

## 10. Nicht enthalten (Server v1)
Downloads/Export-API · Vault-Setup/Rotate aus der App (nur Web) · Push · Offline-
Sync/Caching · Token-Refresh (widerrufen → neu pairen). Diese sind bewusst
out-of-scope; ggf. später serverseitig ergänzen.

---

## 11. Offline-Verfügbarkeit (Kern-Anforderung)

**Es gibt keinen Sync außerhalb der App.** Kein Desktop-Client, kein Drittanbieter,
keine Hintergrund-Sync-Engine außerhalb dieser App. Die App ist der einzige mobile
Client und **hält die Daten offline vor**, damit sie ohne Netz nutzbar ist.

Modell (ZK-konform):
- **Lokaler Cache = Ciphertext.** Die App cached die versiegelten Manifeste
  (`/store`, `/gallery/store` als `{ciphertext,version}`) und die Blob-Bytes
  (`.../raw/{blob}`) **verschlüsselt wie sie vom Server kommen** — also nie als
  Klartext auf Disk. Entschlüsselt wird nur in-memory bei Zugriff (VK nötig).
- **Gesperrt = kein Zugriff.** Ohne entsperrten Vault (VK) sind auch die
  Offline-Daten unzugänglich. Nach Idle/Hintergrund VK löschen; Offline-Cache
  bleibt (Ciphertext), wird nach erneutem Entsperren wieder lesbar.
- **Sync-Logik (nur diese App):** online → Manifest + benötigte Blobs ziehen und
  cachen; offline → aus dem Cache bedienen. Schreiboperationen offline in eine
  **Queue** legen und bei Reconnect anwenden — Manifest-PUT mit optimistic
  `version`; bei `409` neu laden, mergen, erneut PUT (last-write-wins pro Feld,
  wie im Web). Blob-Uploads aus der Queue resumable nachholen.

**Einstellung — was offline vorgehalten wird:** In den App-Settings konfigurierbar,
**ob alles oder jede Funktion einzeln** offline gecached wird. Also ein
Master-Schalter „Alles offline" **plus** je Modul ein eigener Toggle:
- Files offline (Baum immer; Blobs: alle vorab / nur zuletzt geöffnete / aus)
- Gallery offline (Index immer; Fotos: alle / nur Favoriten-Alben / Thumbnails-only / aus)
- Notizen offline · Lesezeichen offline · Todos offline

Default: Manifeste (klein) immer cachen; Blobs (groß) nach Wahl. „Alles" prefetcht
alle referenzierten Blobs (Wi-Fi/Laden-Constraint anbieten). Pro-Modul-Aus = nur
online, on-demand. Cache-Größe/Storage-Limit + „Cache leeren" pro Modul anbieten.

## 12. Share-Target (Kern-Anforderung)

Die App als **Android-Share-Ziel** registrieren (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`
Intent-Filter im Manifest), damit aus anderen Apps geteilt werden kann:
- **Bilder/Videos** (`image/*`, `video/*`) → landen im **Gallery**-Import
  (verschlüsseln + `POST /gallery/upload` + `/gallery/process`-Pipeline, Photo-
  Eintrag in `/gallery/store`).
- **Alle anderen Dateitypen** (`*/*`) → landen im **Files**-Import
  (verschlüsseln + `POST /files/upload`, Eintrag in `/store`, Ziel-Ordner wählbar).
- Mehrfachauswahl (`SEND_MULTIPLE`) unterstützen. Beim Empfang ein kurzes
  Sheet: Ziel (Files-Ordner / Gallery-Album) bestätigen. Offline → in die
  Upload-Queue (§11) legen und später hochladen.

Der Share-Flow nutzt dieselbe Verschlüsselung/Upload-Logik wie der normale
Upload (§6) — inkl. Padmé-Padding.

## 13. Roadmap (später): Immich-artiger Foto-Auto-Upload

Automatischer Kamerarollen-Upload in die Gallery, wie Immich — als eigenständige
spätere Ausbaustufe:
- **Hintergrund-Backup** der gewählten Geräte-Alben (WorkManager, Constraints:
  Wi-Fi/Laden/optional), resumable, mit Fortschritts-/Status-UI.
- **On-Device-Duplikat-Check VOR dem Upload:** lokal eine exakte Signatur des
  Fotos berechnen (z. B. SHA-256 der Original-Bytes = das `sig`-Feld, das der
  Gallery-Store pro Foto führt) und überspringen, wenn der entschlüsselte
  Gallery-Index bereits ein Foto mit dieser `sig` hat → keine Doppel-Uploads.
  (Near-Duplicate via pHash/CLIP macht der Server im `/gallery/process`; der
  On-Device-Check ist der schnelle Exakt-Abgleich, spart Bandbreite.)
- **Geo + Metadaten:** EXIF (inkl. GPS lat/lng), Aufnahmezeit, Kamera lokal
  auslesen und in den Photo-Eintrag/`meta`-Blob übernehmen (der Server liefert
  zusätzlich place/embedding/faces via `/gallery/process`).
- Live-/Motion-Photos (Android motion) wie im Web als `motionRef` mitführen.
- Konfigurierbar: welche Geräte-Alben gesichert werden, Original vs. skaliert,
  nur neue ab Datum X. Diese Stufe ist **nicht** Teil des ersten App-Wurfs —
  zuerst manueller Upload/Share (§6, §12), dann Auto-Backup.

---

## Referenzen im ledgerline-Repo (`$HOME/Entwicklung/ledgerline`)
- Krypto ground truth: `resources/js/vault.js`
- Manifest-Felder / Upload-Flows: `resources/js/app.js` (`vaultFiles`, `vaultGallery`)
- API-Routen: `routes/api.php` · Auth: `app/Http/Controllers/Api/AuthController.php`,
  `app/Services/Auth/Pairing.php` · Vault-Shape: `app/Http/Controllers/VaultController.php`
- Design/Spec: `docs/superpowers/specs/2026-07-10-android-mobile-api-{design,plan}.md`
