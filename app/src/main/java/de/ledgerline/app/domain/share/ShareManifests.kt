package de.ledgerline.app.domain.share

import de.ledgerline.app.domain.model.NamedFolder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure builders for the **sealed share manifest** JSON, byte-shaped to the web client
 * (`resources/js/components/{files,gallery}.js` `_buildShareManifest`). The manifest is sealed
 * under the share key and only ever `JSON.parse`d by the recipient viewer, so key ORDER is not
 * interop-critical — but the key NAMES must match web exactly (the web recipient reads them).
 *
 * These take already-wrapped per-blob keys (the crypto happens in the repository, which holds
 * the Vault Key) so the JSON assembly stays trivially unit-testable.
 */
object ShareManifests {

    /** One file entry in a file/folder share manifest: `{name,mime,size,path,ref,key}`. */
    data class FileEntryIn(
        val name: String,
        val mime: String,
        val size: Long,
        val path: String,
        val ref: String,
        /** The per-file key re-wrapped under the share key, as a `{"c","n"}` JSON string. */
        val key: String,
    )

    /** `{ kind, name, files:[{name,mime,size,path,ref,key}] }`. */
    fun fileManifest(kind: String, name: String, files: List<FileEntryIn>): String {
        val entries = files.map { f ->
            JsonObject(
                linkedMapOf(
                    "name" to JsonPrimitive(f.name),
                    "mime" to JsonPrimitive(f.mime.ifEmpty { "application/octet-stream" }),
                    "size" to JsonPrimitive(f.size),
                    "path" to JsonPrimitive(f.path),
                    "ref" to JsonPrimitive(f.ref),
                    "key" to JsonPrimitive(f.key),
                ),
            )
        }
        return JsonObject(
            linkedMapOf(
                "kind" to JsonPrimitive(kind),
                "name" to JsonPrimitive(name),
                "files" to JsonArray(entries),
            ),
        ).toString()
    }

    /**
     * The set of folder ids in the subtree rooted at [rootId] (inclusive), mirroring web
     * `subtree()`: repeatedly add any folder whose `parent` is already in the set.
     */
    fun subtree(rootId: String, folders: List<NamedFolder>): Set<String> {
        val set = linkedSetOf(rootId)
        var grew = true
        while (grew) {
            grew = false
            for (f in folders) {
                if (f.parent != null && set.contains(f.parent) && !set.contains(f.id)) {
                    set.add(f.id); grew = true
                }
            }
        }
        return set
    }

    /**
     * A file's folder path relative to the shared folder [rootId] (web `_relPath`): walk up from
     * the file's folder collecting names until the root (exclusive) or a broken chain; `/`-joined.
     */
    fun relPath(fileFolder: String?, rootId: String, byId: Map<String, NamedFolder>): String {
        val parts = ArrayDeque<String>()
        var cur = fileFolder
        while (cur != null && cur != rootId && byId.containsKey(cur)) {
            val folder = byId.getValue(cur)
            parts.addFirst(folder.name)
            cur = folder.parent
        }
        return parts.joinToString("/")
    }
}
