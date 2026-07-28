package de.ledgerline.app.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.floor

/**
 * Health-module data model (ZK — everything lives in the sealed `store/health` monolith:
 * `{ v:3, healthEntries[], healthProfile, healthFasts[] }`). Known fields are typed; the original
 * decoded JSON is kept in `raw` so foreign/future keys survive an Android read-modify-write — the
 * same no-data-loss overlay as [de.ledgerline.app.domain.model.ExploreTrack] / FileRecordCodec.
 */

/** One measurement. Canonical units at rest; `v2` = diastolic for `bp`, else null. */
data class HealthEntry(
    val id: String,
    val ts: String,
    val metric: String,
    val v: Double,
    val v2: Double? = null,
    val note: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** Display-unit tokens (byte-compatible with the web `healthProfile.units`). */
data class HealthUnits(
    val weight: String = "kg",   // kg | lb
    val glucose: String = "mgdl", // mgdl | mmoll
    val temp: String = "c",       // c | f
)

/** Master data. Height in cm, weight goal in kg (canonical). `sex` ∈ m|f|x|"". */
data class HealthProfile(
    val birthdate: String = "",
    val heightCm: Double? = null,
    val sex: String = "",
    val weightGoalKg: Double? = null,
    val units: HealthUnits = HealthUnits(),
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** An intermittent-fast record. `end == null` ⇒ active. `targetHours` = fasting window. */
data class HealthFast(
    val id: String,
    val start: String,
    val end: String? = null,
    val targetHours: Int? = null,
    val note: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** Decrypted `store/health` manifest. [extra] preserves `v` + any unknown top-level keys. */
data class HealthManifest(
    val entries: List<HealthEntry> = emptyList(),
    val profile: HealthProfile = HealthProfile(),
    val fasts: List<HealthFast> = emptyList(),
    val extra: JsonObject = JsonObject(emptyMap()),
)

/** Manifest + optimistic-concurrency version. */
data class HealthStore(val manifest: HealthManifest, val version: Int)

/**
 * (De)serialisation for the health manifest with a raw-JSON overlay. Web writes numbers as clean
 * JSON tokens (integers without `.0`); [numToken] mirrors that. Unknown top-level keys land in
 * [HealthManifest.extra]; unknown per-record keys survive via each record's `raw`.
 */
object HealthRecordCodec {
    private const val ENTRIES = "healthEntries"
    private const val PROFILE = "healthProfile"
    private const val FASTS = "healthFasts"
    private val TOP_OWNED = setOf(ENTRIES, PROFILE, FASTS)

    private val ENTRY_OWNED = setOf("id", "ts", "metric", "v", "v2", "note")
    private val PROFILE_OWNED = setOf("birthdate", "heightCm", "sex", "weightGoalKg", "units")
    private val FAST_OWNED = setOf("id", "start", "end", "targetHours", "note")

    // ---- decode ------------------------------------------------------------

    fun decodeManifest(root: JsonObject): HealthManifest {
        val entries = (root[ENTRIES] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::decodeEntry) }
        val fasts = (root[FASTS] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::decodeFast) }
        val profile = (root[PROFILE] as? JsonObject)?.let(::decodeProfile) ?: HealthProfile()
        val extra = JsonObject(root.filterKeys { it !in TOP_OWNED })
        return HealthManifest(entries, profile, fasts, extra)
    }

    private fun decodeEntry(o: JsonObject): HealthEntry? {
        val id = o.str("id") ?: return null
        val metric = o.str("metric") ?: return null
        val v = o["v"]?.jsonPrimitive?.doubleOrNull ?: return null
        return HealthEntry(
            id = id,
            ts = o.str("ts") ?: "",
            metric = metric,
            v = v,
            v2 = o["v2"]?.let { if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull },
            note = o.str("note") ?: "",
            raw = o,
        )
    }

    private fun decodeFast(o: JsonObject): HealthFast? {
        val id = o.str("id") ?: return null
        val start = o.str("start") ?: return null
        return HealthFast(
            id = id,
            start = start,
            end = o["end"]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull },
            targetHours = o["targetHours"]?.jsonPrimitive?.intOrNull,
            note = o.str("note") ?: "",
            raw = o,
        )
    }

    private fun decodeProfile(o: JsonObject): HealthProfile {
        val u = (o["units"] as? JsonObject)
        return HealthProfile(
            birthdate = o.str("birthdate") ?: "",
            heightCm = o["heightCm"]?.let { if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull },
            sex = o.str("sex") ?: "",
            weightGoalKg = o["weightGoalKg"]?.let { if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull },
            units = HealthUnits(
                weight = u?.str("weight") ?: "kg",
                glucose = u?.str("glucose") ?: "mgdl",
                temp = u?.str("temp") ?: "c",
            ),
            raw = o,
        )
    }

    // ---- encode (raw overlay) ----------------------------------------------

    fun encodeManifest(m: HealthManifest): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["v"] = JsonPrimitive(3)
        // Preserve any unknown top-level keys the manifest carried (minus v, which we just set).
        for ((k, v) in m.extra) if (k != "v") out[k] = v
        out[ENTRIES] = JsonArray(m.entries.map(::encodeEntry))
        out[PROFILE] = encodeProfile(m.profile)
        out[FASTS] = JsonArray(m.fasts.map(::encodeFast))
        return JsonObject(out)
    }

    private fun encodeEntry(e: HealthEntry): JsonObject {
        val out = e.raw.toMutableMap()
        out["id"] = JsonPrimitive(e.id)
        out["ts"] = JsonPrimitive(e.ts)
        out["metric"] = JsonPrimitive(e.metric)
        out["v"] = numToken(e.v)
        out["v2"] = e.v2?.let(::numToken) ?: JsonNull
        out["note"] = JsonPrimitive(e.note)
        return JsonObject(out)
    }

    private fun encodeFast(f: HealthFast): JsonObject {
        val out = f.raw.toMutableMap()
        out["id"] = JsonPrimitive(f.id)
        out["start"] = JsonPrimitive(f.start)
        out["end"] = f.end?.let { JsonPrimitive(it) } ?: JsonNull
        out["targetHours"] = f.targetHours?.let { JsonPrimitive(it) } ?: JsonNull
        out["note"] = JsonPrimitive(f.note)
        return JsonObject(out)
    }

    private fun encodeProfile(p: HealthProfile): JsonObject {
        val out = p.raw.toMutableMap()
        setOrNull(out, "birthdate", if (p.birthdate.isEmpty()) null else JsonPrimitive(p.birthdate))
        out["heightCm"] = p.heightCm?.let(::numToken) ?: JsonNull
        setOrNull(out, "sex", if (p.sex.isEmpty()) null else JsonPrimitive(p.sex))
        out["weightGoalKg"] = p.weightGoalKg?.let(::numToken) ?: JsonNull
        out["units"] = JsonObject(
            linkedMapOf(
                "weight" to JsonPrimitive(p.units.weight),
                "glucose" to JsonPrimitive(p.units.glucose),
                "temp" to JsonPrimitive(p.units.temp),
            ),
        )
        return JsonObject(out)
    }

    // ---- helpers -----------------------------------------------------------

    /** Emit an integral value as an integer token (`120`), else a decimal (`72.5`) — web parity. */
    private fun numToken(d: Double): JsonPrimitive =
        if (!d.isInfinite() && !d.isNaN() && d == floor(d) && kotlin.math.abs(d) < 1e15) {
            JsonPrimitive(d.toLong())
        } else {
            JsonPrimitive(d)
        }

    private fun setOrNull(out: MutableMap<String, JsonElement>, key: String, value: JsonElement?) {
        if (value != null) out[key] = value else out[key] = JsonNull
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    // Suppress unused-import lints for the boolean helper kept for symmetry with sibling codecs.
    @Suppress("unused")
    private fun JsonObject.boolOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
}
