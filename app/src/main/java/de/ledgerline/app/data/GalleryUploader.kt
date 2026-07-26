package de.ledgerline.app.data

import android.util.Base64
import androidx.annotation.VisibleForTesting
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.usecase.GalleryUploadApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads one photo end-to-end and returns the [GalleryPhoto] index entry.
 *
 * Byte-for-byte mirror of the web client's `_processOne` (app.js): upload the
 * encrypted original, run `/gallery/process` on the plaintext, upload the derived
 * renditions (thumb/medium/motion) and per-face crops, assemble the sealed meta
 * blob (re-serialising the opaque exif/place/embedding/phash unchanged), and build
 * the entry with all refs/keys plus denormalised exif fields.
 */
@Singleton
class GalleryUploader @VisibleForTesting internal constructor(
    private val blobs: GalleryUploadApi,
    /** Base64 decoder for the process response's renditions; injected so JVM tests can
     *  swap in `java.util.Base64` (the Android `android.util.Base64` stub throws off-device). */
    private val decodeBase64: (String) -> ByteArray,
) {
    /** Production constructor: `android.util.Base64` decodes the server's standard (padded) base64. */
    @Inject constructor(blobs: GalleryUploadApi) : this(blobs, { s -> Base64.decode(s, Base64.NO_WRAP) })

    private val json = Json { encodeDefaults = true }

    suspend fun upload(
        name: String,
        mime: String,
        sig: String,
        bytes: ByteArray,
        createdIso: String,
        lat: Double? = null,
        lng: Double? = null,
    ): Outcome<GalleryPhoto> {
        val original = blobs.uploadBytes(bytes, name).okOr { return it }
        val d = blobs.process(bytes, name, mime).okOr { return it }

        val thumb = d.thumb?.let { blobs.uploadBytes(decodeBase64(it), "thumb.enc").okOr { e -> return e } }
        val medium = d.medium?.let { blobs.uploadBytes(decodeBase64(it), "medium.enc").okOr { e -> return e } }
        val motion = d.motion?.let { blobs.uploadBytes(decodeBase64(it), "motion.enc").okOr { e -> return e } }

        // Faces: upload each crop, remember its ref, and re-emit the opaque face fields.
        val faceRefs = mutableListOf<String>()
        val metaFaces = buildJsonArray {
            for (f in d.faces) {
                val crop = f.crop?.let { blobs.uploadBytes(decodeBase64(it), "crop.enc").okOr { e -> return e } }
                if (crop != null) faceRefs += crop.id
                add(buildJsonObject {
                    f.score?.let { put("score", JsonPrimitive(it)) }
                    f.box?.let { put("box", it) }
                    f.embedding?.let { put("embedding", it) }
                    if (crop != null) {
                        put("cropRef", JsonPrimitive(crop.id))
                        put("cropKey", JsonPrimitive(crop.encFileKey))
                    }
                })
            }
        }

        // Meta blob: opaque exif/place/embedding/phash preserved verbatim.
        val metaObj = buildJsonObject {
            d.exif?.let { put("exif", it) }
            d.place?.let { put("place", it) }
            d.embedding?.let { put("embedding", it) }
            d.phash?.let { put("phash", it) }   // opaque JsonElement, preserved verbatim
            put("faces", metaFaces)
            d.width?.let { put("width", JsonPrimitive(it)) }
            d.height?.let { put("height", JsonPrimitive(it)) }
            d.duration?.let { put("duration", JsonPrimitive(it)) }
            d.content_id?.let { put("content_id", JsonPrimitive(it)) }
        }
        val meta = blobs.uploadBytes(
            json.encodeToString(JsonObject.serializer(), metaObj).toByteArray(),
            "meta.enc",
        ).okOr { return it }

        // Denormalised fields read from the opaque exif object.
        val exif = d.exif as? JsonObject
        fun exifStr(k: String): String? = (exif?.get(k) as? JsonPrimitive)?.contentOrNull
        fun exifDbl(k: String): Double? = (exif?.get(k) as? JsonPrimitive)?.doubleOrNull

        val entry = GalleryPhoto(
            id = de.ledgerline.app.core.Ids.newId(),
            media_type = if (mime.startsWith("video")) "video" else "image",
            originalRef = original.id, originalKey = original.encFileKey,
            thumbRef = thumb?.id, thumbKey = thumb?.encFileKey,
            mediumRef = medium?.id, mediumKey = medium?.encFileKey,
            motionRef = motion?.id, motionKey = motion?.encFileKey,
            metaRef = meta.id, metaKey = meta.encFileKey,
            faceCropRefs = faceRefs,
            sig = sig,
            lat = exifDbl("lat") ?: lat, lng = exifDbl("lon") ?: lng,
            width = d.width, height = d.height, duration = d.duration,
            taken_at = exifStr("taken_at") ?: createdIso,
            camera = exifStr("camera"),
            hasFaces = d.faces.size,
            created = createdIso,
            content_id = d.content_id,
            name = name,
            mime = mime,          // web needs this for video playback + original download
            size = bytes.size.toLong(),
        )
        return Outcome.Ok(entry)
    }

    private inline fun <T> Outcome<T>.okOr(onErr: (Outcome<Nothing>) -> Nothing): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> onErr(this)
        }
}
