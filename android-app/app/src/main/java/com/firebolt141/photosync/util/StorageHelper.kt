package com.firebolt141.ubertrag.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/** Outcome of [StorageHelper.renameLegacyFolders]. */
data class RenameResult(
    val renamed: Int = 0,          // folders given their new name
    val merged: Int = 0,           // folders whose contents went into an existing folder
    val filesMoved: Int = 0,       // files moved while merging
    val identical: Int = 0,        // files left in place because the same file already exists
    val errors: Int = 0,
    val changes: List<String> = emptyList(),   // "2024/01 → 2024/January" lines (preview or done)
    val problems: List<String> = emptyList(),
)

object StorageHelper {

    private const val TAG = "StorageHelper"

    fun isDriveMounted(context: Context, treeUriString: String?): Boolean {
        if (treeUriString.isNullOrBlank()) return false
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return false
            root.exists() && root.canWrite()
        } catch (_: Exception) { false }
    }

    /** Keeps access to a picked folder across restarts. False if the system refused. */
    fun takePersistablePermission(context: Context, uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "takePersistableUriPermission failed: ${e.message}")
            false
        }
    }

    /** "Photos" for content://…/tree/primary:Backups/Photos; "" if unknown. */
    fun folderLabel(uriString: String?): String {
        if (uriString.isNullOrBlank()) return ""
        return try {
            val seg = Uri.parse(uriString).lastPathSegment ?: return ""
            val afterColon = seg.substringAfterLast(':')
            when {
                afterColon.isBlank() && seg.startsWith("primary") -> "Internal storage"
                afterColon.isBlank() -> "Drive (top level)"
                else -> afterColon.substringAfterLast('/')
            }
        } catch (_: Exception) { "" }
    }

    // ── Rename legacy folders ────────────────────────────────────────────────

    /**
     * Brings old month/day folders up to date:
     *   2024/01/15         → 2024/January/January_15
     *   2024/March/March 7 → 2024/March/March_07
     *   2024/3             → 2024/March
     * When the new name already exists, the contents are merged into it (a
     * file that is already there with the same size and bytes is left
     * where it was rather than duplicated).
     *
     * Day folders are handled first (while the month folder's URI is still
     * valid), then the month folder. [root] may be the drive or one year folder.
     * With [dryRun] nothing is changed and [RenameResult.changes] lists what would be.
     */
    fun renameLegacyFolders(
        context: Context,
        root: DocumentFile,
        dryRun: Boolean,
        onProgress: (String) -> Unit,
    ): RenameResult {
        Log.d(TAG, "renameLegacyFolders root=${root.name} dryRun=$dryRun")
        val acc = Acc()
        val rootYear = PhotoLogic.parseYearFolder(root.name ?: "")
        val years = if (rootYear != null) listOf(root) else
            list(root, acc).filter { it.isDirectory && PhotoLogic.parseYearFolder(it.name ?: "") != null }

        for (yearDir in years) {
            val yearName = yearDir.name ?: continue
            for (monthDir in list(yearDir, acc)) {
                if (!monthDir.isDirectory) continue
                val mName = monthDir.name ?: continue
                val month = PhotoLogic.parseMonthFolder(mName) ?: continue
                val wantMonth = PhotoLogic.monthName(month)

                // 1) day folders inside this month folder
                val days = list(monthDir, acc).filter { it.isDirectory }
                val byName = days.associateBy { (it.name ?: "").lowercase() }.toMutableMap()
                for (dayDir in days) {
                    val dName = dayDir.name ?: continue
                    val newName = PhotoLogic.legacyDayRename(month, dName) ?: continue
                    onProgress("$yearName/$wantMonth/$newName")
                    val target = byName[newName.lowercase()]?.takeIf { it.uri != dayDir.uri }
                    acc.changes += "$yearName/$mName/$dName → $yearName/$wantMonth/$newName" +
                        if (target != null) " (merge)" else ""
                    if (dryRun) continue
                    if (target != null) {
                        mergeInto(context, dayDir, monthDir, target, acc)
                    } else if (renameDir(dayDir, newName)) {
                        acc.renamed++
                        byName[newName.lowercase()] = dayDir
                    } else {
                        acc.fail("Could not rename $yearName/$mName/$dName")
                    }
                }

                // 2) the month folder itself
                if (mName == wantMonth) continue
                onProgress("$yearName/$wantMonth")
                val existing = list(yearDir, acc).firstOrNull {
                    it.isDirectory && it.uri != monthDir.uri && (it.name ?: "").equals(wantMonth, ignoreCase = true)
                }
                acc.changes += "$yearName/$mName → $yearName/$wantMonth" + if (existing != null) " (merge)" else ""
                if (dryRun) continue
                if (existing != null) mergeInto(context, monthDir, yearDir, existing, acc)
                else if (renameDir(monthDir, wantMonth)) acc.renamed++
                else acc.fail("Could not rename $yearName/$mName")
            }
        }
        val r = RenameResult(acc.renamed, acc.merged, acc.moved, acc.identical, acc.errors, acc.changes, acc.problems)
        Log.d(TAG, "renameLegacyFolders done: renamed=${r.renamed} merged=${r.merged} errors=${r.errors}")
        return r
    }

    private class Acc {
        var renamed = 0; var merged = 0; var moved = 0; var identical = 0; var errors = 0
        val changes = mutableListOf<String>()
        val problems = mutableListOf<String>()
        fun fail(msg: String) { errors++; if (problems.size < 50) problems += msg }
    }

    private fun list(dir: DocumentFile, acc: Acc): List<DocumentFile> = try {
        dir.listFiles().toList()
    } catch (e: Exception) {
        acc.fail("Could not open ${dir.name}: ${e.message}")
        emptyList()
    }

    private fun renameDir(dir: DocumentFile, newName: String): Boolean {
        val old = dir.name ?: return false
        return try {
            if (old.equals(newName, ignoreCase = true)) {
                // Case-only change: some file systems (FAT/exFAT) need a detour.
                val detour = "$newName.~tmp"
                dir.renameTo(detour) && dir.renameTo(newName)
            } else dir.renameTo(newName)
        } catch (e: Exception) {
            Log.w(TAG, "rename $old → $newName failed: ${e.message}")
            false
        }
    }

    /**
     * Moves everything in [src] (a child of [srcParent]) into [dest], merging
     * sub-folders with the same name, then removes [src] if it ended up empty.
     */
    private fun mergeInto(context: Context, src: DocumentFile, srcParent: DocumentFile, dest: DocumentFile, acc: Acc) {
        val destChildren = list(dest, acc).associateBy { (it.name ?: "").lowercase() }.toMutableMap()
        for (child in list(src, acc)) {
            val n = child.name ?: continue
            val clash = destChildren[n.lowercase()]
            if (child.isDirectory) {
                if (clash != null && clash.isDirectory) mergeInto(context, child, src, clash, acc)
                else if (clash == null && moveDoc(context, child, src, dest)) { acc.moved++ ; destChildren[n.lowercase()] = child }
                else acc.fail("Could not move folder ${src.name}/$n")
                continue
            }
            if (clash != null && sameContent(context, child, clash)) {
                acc.identical++                     // the same file is already there; leave this copy
                continue
            }
            if (clash == null && moveDoc(context, child, src, dest)) {
                acc.moved++
                destChildren[n.lowercase()] = child
                continue
            }
            // Name taken by a different file (or the provider can't move): copy under a free name, then delete.
            val free = PhotoLogic.uniqueName(n) { destChildren.containsKey(it.lowercase()) }
            val copy = try { dest.createFile("application/octet-stream", free) } catch (_: Exception) { null }
            if (copy == null) { acc.fail("Could not move ${src.name}/$n"); continue }
            val ok = try {
                val written = context.contentResolver.openInputStream(child.uri)?.use { inp ->
                    context.contentResolver.openOutputStream(copy.uri, "w")?.use { out -> inp.copyTo(out) }
                }
                written != null && written == child.length()
            } catch (_: Exception) { false }
            if (ok) {
                destChildren[(copy.name ?: free).lowercase()] = copy
                try { child.delete() } catch (_: Exception) { }
                acc.moved++
            } else {
                try { copy.delete() } catch (_: Exception) { }
                acc.fail("Could not move ${src.name}/$n")
            }
        }
        val leftover = try { src.listFiles().size } catch (_: Exception) { -1 }
        if (leftover == 0) {
            try { src.delete() } catch (_: Exception) { }
            acc.merged++
        } else if (leftover > 0) {
            // Identical duplicates stay behind; say so instead of silently keeping the old folder.
            acc.merged++
            if (acc.problems.size < 50) acc.problems += "${srcParent.name}/${src.name} kept $leftover file(s) that already exist in ${dest.name}"
        }
    }

    private fun moveDoc(context: Context, doc: DocumentFile, from: DocumentFile, to: DocumentFile): Boolean = try {
        DocumentsContract.moveDocument(context.contentResolver, doc.uri, from.uri, to.uri) != null
    } catch (e: Exception) {
        Log.d(TAG, "moveDocument unsupported/failed for ${doc.name}: ${e.message}")
        false
    }

    private fun sameContent(context: Context, a: DocumentFile, b: DocumentFile): Boolean {
        if (!b.isFile || a.length() != b.length()) return false
        return try {
            val ia = context.contentResolver.openInputStream(a.uri) ?: return false
            ia.use {
                val ib = context.contentResolver.openInputStream(b.uri) ?: return false
                ib.use { streamsEqual(ia, ib) }
            }
        } catch (_: Exception) { false }
    }

    private fun streamsEqual(ia: java.io.InputStream, ib: java.io.InputStream): Boolean {
        val ba = ByteArray(1 shl 16)
        val bb = ByteArray(1 shl 16)
        while (true) {
            val ra = ia.readNBytesCompat(ba)
            val rb = ib.readNBytesCompat(bb)
            if (ra != rb) return false
            if (ra == 0) return true
            for (i in 0 until ra) if (ba[i] != bb[i]) return false
        }
    }

    /** Fills [buf] as far as the stream allows (InputStream.readNBytes needs API 33). */
    private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }
}
