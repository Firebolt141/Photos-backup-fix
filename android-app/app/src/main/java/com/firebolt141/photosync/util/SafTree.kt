package com.firebolt141.ubertrag.util

import com.firebolt141.ubertrag.R
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/** Result of copying one file into a SAF folder. */
sealed class CopyOutcome {
    data class Copied(val file: DocumentFile, val name: String, val bytes: Long) : CopyOutcome()
    /** The same file (same size and matching content sample) is already there under this name or an _N variant. */
    data class AlreadyThere(val name: String) : CopyOutcome()
    data class Failed(val message: String) : CopyOutcome()
}

/**
 * Folder operations on a Storage Access Framework tree, with each folder's
 * listing cached. `DocumentFile.findFile()` lists the whole folder on every
 * call, which gets very slow for day folders with hundreds of photos.
 *
 * Not thread-safe: use one instance per job.
 */
class SafTree(private val context: Context) {

    private companion object {
        const val TAG = "SafTree"
        /** Generic type so the provider never appends or changes the extension. */
        const val MIME = "application/octet-stream"
    }

    private val listings = HashMap<String, MutableMap<String, DocumentFile>>()

    /** Resources in the app's chosen language (fixed for this job, so [isFatal] can compare). */
    private val res = context.loc()

    /** Lower-case name → child for [dir]. Leftover temp files from an interrupted copy are removed. */
    fun children(dir: DocumentFile): MutableMap<String, DocumentFile> =
        listings.getOrPut(dir.uri.toString()) {
            val map = HashMap<String, DocumentFile>()
            for (f in dir.listFiles()) {
                val n = f.name ?: continue
                if (n.startsWith(PhotoLogic.TMP_PREFIX)) {
                    try { f.delete() } catch (_: Exception) { }
                    continue
                }
                map[n.lowercase()] = f
            }
            map
        }

    fun child(dir: DocumentFile, name: String): DocumentFile? = children(dir)[name.lowercase()]

    /** Existing sub-folder [name] of [parent], or a newly created one (null if that fails). */
    fun dir(parent: DocumentFile, name: String): DocumentFile? {
        child(parent, name)?.let { if (it.isDirectory) return it }
        val created = try { parent.createDirectory(name) } catch (e: Exception) {
            Log.w(TAG, "createDirectory($name) failed: ${e.message}")
            null
        } ?: return null
        children(parent)[(created.name ?: name).lowercase()] = created
        return created
    }

    /** Walks/creates root/parts[0]/parts[1]/… */
    fun dirPath(root: DocumentFile, parts: List<String>): DocumentFile? {
        var cur: DocumentFile = root
        for (p in parts) {
            if (p.isBlank()) continue
            cur = dir(cur, p) ?: return null
        }
        return cur
    }

    /**
     * Copies [source] (of [sourceSize] bytes, -1 if unknown) into [destDir] as
     * [desiredName]. A same-name file of the same size counts as already
     * there; a different file with that name gets an _1, _2 … suffix.
     * The data is written to a temporary name first and renamed at the end,
     * so an interrupted copy never looks like a finished one.
     */
    fun copyInto(source: Uri, sourceSize: Long, destDir: DocumentFile, desiredName: String): CopyOutcome {
        val names = children(destDir)
        // Same content already present under this name or one of its _N variants?
        var name = desiredName
        if (names.containsKey(name.lowercase())) {
            val dot = desiredName.lastIndexOf('.')
            val stem = if (dot > 0) desiredName.substring(0, dot) else desiredName
            val ext = if (dot > 0) desiredName.substring(dot) else ""
            var i = 0
            while (true) {
                val cand = if (i == 0) desiredName else "${stem}_$i$ext"
                val existing = names[cand.lowercase()]
                if (existing == null) { name = cand; break }
                if (sourceSize >= 0 && existing.length() == sourceSize && sameSample(source, existing.uri, sourceSize)) {
                    return CopyOutcome.AlreadyThere(cand)
                }
                i++
                if (i > 99_999) return CopyOutcome.Failed(res.getString(R.string.err_no_free_name, desiredName))
            }
        }

        val tmpName = PhotoLogic.TMP_PREFIX + name
        val tmp = try { destDir.createFile(MIME, tmpName) } catch (e: Exception) { null }
            ?: return copyDirect(source, sourceSize, destDir, name)
        val written = try {
            streamCopy(source, tmp.uri)
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) { }
            return CopyOutcome.Failed(friendly(e))
        }
        if (sourceSize >= 0 && written != sourceSize) {
            try { tmp.delete() } catch (_: Exception) { }
            return CopyOutcome.Failed(res.getString(R.string.err_incomplete, written, sourceSize))
        }
        val renamed = try { tmp.renameTo(name) } catch (_: Exception) { false }
        if (!renamed) {
            // Some USB providers don't support rename: fall back to a direct copy.
            try { tmp.delete() } catch (_: Exception) { }
            return copyDirect(source, sourceSize, destDir, name)
        }
        val finalName = tmp.name ?: name
        names[finalName.lowercase()] = tmp
        return CopyOutcome.Copied(tmp, finalName, written)
    }

    private fun copyDirect(source: Uri, sourceSize: Long, destDir: DocumentFile, name: String): CopyOutcome {
        val dest = try { destDir.createFile(MIME, name) } catch (e: Exception) { null }
            ?: return CopyOutcome.Failed(res.getString(R.string.err_cannot_create, name, destDir.name ?: res.getString(R.string.err_the_folder)))
        return try {
            val written = streamCopy(source, dest.uri)
            if (sourceSize >= 0 && written != sourceSize) {
                dest.delete()
                CopyOutcome.Failed(res.getString(R.string.err_incomplete, written, sourceSize))
            } else {
                val finalName = dest.name ?: name
                children(destDir)[finalName.lowercase()] = dest
                CopyOutcome.Copied(dest, finalName, written)
            }
        } catch (e: Exception) {
            try { dest.delete() } catch (_: Exception) { }
            CopyOutcome.Failed(friendly(e))
        }
    }

    /**
     * Same size is not proof of the same photo (two cameras can both make
     * IMG_0001.JPG of identical length), so also compare the first and last
     * 64 KB. Cheap even for big videos, and only done on a name+size match.
     */
    private fun sameSample(a: Uri, b: Uri, size: Long): Boolean = try {
        val chunk = 64 * 1024
        val head = minOf(size, chunk.toLong()).toInt()
        val tailStart = maxOf(head.toLong(), size - chunk)
        readSample(a, head, tailStart, size).contentEquals(readSample(b, head, tailStart, size))
    } catch (e: Exception) {
        Log.w(TAG, "content check failed: ${e.message}")
        false   // can't tell: keep both (the copy gets an _N name)
    }

    private fun readSample(uri: Uri, head: Int, tailStart: Long, size: Long): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot read")
        input.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(head.coerceAtLeast(1))
            var got = 0
            while (got < head) { val r = s.read(buf, got, head - got); if (r < 0) break; got += r }
            out.write(buf, 0, got)
            var pos = got.toLong()
            while (pos < tailStart) { val k = s.skip(tailStart - pos); if (k <= 0) { if (s.read() < 0) break; pos++ } else pos += k }
            val rest = s.readBytes()
            out.write(rest, 0, rest.size)
            return out.toByteArray()
        }
    }

    private fun streamCopy(from: Uri, to: Uri): Long {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(from) ?: throw java.io.IOException(res.getString(R.string.err_cannot_read_source))
        try {
            val output = resolver.openOutputStream(to, "w") ?: throw java.io.IOException(res.getString(R.string.err_cannot_write_drive))
            try {
                return input.copyTo(output, 1 shl 16)
            } finally {
                output.close()
            }
        } finally {
            input.close()
        }
    }

    /** Plain-English messages for the usual failures. */
    fun friendly(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("ENOSPC", true) || msg.contains("No space", true) -> res.getString(R.string.err_drive_full)
            msg.contains("EFBIG", true) || msg.contains("too large", true) -> res.getString(R.string.err_too_large)
            msg.contains("EACCES", true) || msg.contains("Permission", true) -> res.getString(R.string.err_permission)
            msg.contains("ENOENT", true) || msg.contains("No such file", true) -> res.getString(R.string.err_not_found)
            msg.contains("EIO", true) -> res.getString(R.string.err_io)
            else -> msg
        }
    }

    /** True when the error means every remaining file would fail too. */
    fun isFatal(message: String): Boolean =
        message == res.getString(R.string.err_drive_full) || message == res.getString(R.string.err_io) ||
        message == res.getString(R.string.err_not_found)
}
