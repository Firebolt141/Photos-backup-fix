package com.firebolt141.ubertrag.util

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

data class ExifFixResult(
    val fixed: Int = 0,
    val alreadyHasDate: Int = 0,
    val skipped: Int = 0,      // format can't store a date (HEIC, RAW, video)
    val failed: Int = 0,
    val mismatched: Int = 0,   // has a date on a different day than its folder
    val corrected: Int = 0,    // mismatched files moved to their folder's day
    val notes: List<String> = emptyList(),  // folders skipped / loose files
)

data class FixByFilenameResult(
    val copied: Int        = 0,
    val exifWritten: Int   = 0,  // dates written (from the file name)
    val noDate: Int        = 0,  // no date anywhere → no-date/
    val alreadyExists: Int = 0,  // already in the output — skipped
    val unsupported: Int   = 0,  // HEIC / video — sorted, date not written
    val failed: Int        = 0,  // → error/
    val keptExisting: Int  = 0,  // already had a date inside — sorted by it, kept
    val ignored: Int       = 0,  // not photos/videos — left alone
    val archives: Int      = 0,  // zip files that need unzipping first
    val stoppedEarly: String = "",
    val errorMsg: String   = "",
)

object ExifFixer {

    private const val TAG = "ExifFixer"

    // ── Drive mode: write folder dates into undated files, in place ──────────

    private class DatedFile(val file: DocumentFile, val dir: DocumentFile, val date: LocalDate)

    fun fixMissingExif(
        root: DocumentFile,
        context: Context,
        onProgress: (current: Int, total: Int, name: String) -> Unit,
        onLog: (String) -> Unit = {},
        fixMismatched: Boolean = false,
        isCancelled: () -> Boolean = { false },
    ): ExifFixResult {
        Log.d(TAG, "fixMissingExif root=${root.name} fixMismatched=$fixMismatched")
        val notes = mutableListOf<String>()
        val items = mutableListOf<DatedFile>()
        collectItems(root, items, notes)
        notes.take(10).forEach { onLog("  $it") }
        if (notes.size > 10) onLog("  … and ${notes.size - 10} more")
        onLog("Found ${items.size} photo(s) and video(s) in dated folders.")

        val zone = ZoneId.systemDefault()
        var fixed = 0; var already = 0; var skipped = 0; var failed = 0; var mismatched = 0; var corrected = 0
        for ((idx, item) in items.withIndex()) {
            if (isCancelled()) break
            val name = item.file.name ?: continue
            onProgress(idx + 1, items.size, name)
            val existing = DateExtractor.embedded(context, item.file.uri, name)
            var target: LocalDateTime? = null
            if (existing != null) {
                already++
                val days = kotlin.math.abs(existing.wall.toLocalDate().toEpochDay() - item.date.toEpochDay())
                if (days <= 1) continue
                mismatched++
                if (!fixMismatched || !PhotoLogic.isWritable(name)) {
                    onLog("≠  $name has ${existing.wall.toLocalDate()}, folder says ${item.date} (left unchanged)")
                    continue
                }
                target = item.date.atTime(existing.wall.toLocalTime())     // keep the time of day
            }
            if (!PhotoLogic.isWritable(name)) {
                skipped++
                continue
            }
            val date = if (target != null) PhotoDate(target, existing?.offset, "folder") else {
                val fn = PhotoLogic.dateFromFilename(name, zone)
                if (fn != null && fn.wall.toLocalDate() == item.date) fn
                else PhotoDate(item.date.atTime(12, 0), ZoneOffset.UTC, "folder")
            }
            try {
                writeExifDate(context, item.file, item.dir, date)
                if (target != null) { corrected++; onLog("✓  $name corrected to ${date.wall.toLocalDate()}") }
                else { fixed++; onLog("✓  $name → ${date.exif}") }
            } catch (e: Exception) {
                failed++
                Log.e(TAG, "failed: $name", e)
                onLog("✗  $name: ${e.message}")
            }
        }
        val r = ExifFixResult(fixed, already, skipped, failed, mismatched, corrected, notes)
        Log.d(TAG, "fixMissingExif done: $r")
        return r
    }

    // ── Filename / "Organize by date" mode ────────────────────────────────────

    /**
     * Copies every photo/video under [sourceRoot] into [outputRoot]/year/month/day
     * using the date inside the file or in its name (no JSON sidecars). Files with
     * no date go to no-date/<original sub-folders>/, failures to error/.
     */
    fun fixByFilename(
        sourceRoot: DocumentFile,
        outputRoot: DocumentFile,
        context:    Context,
        onLog:      (String) -> Unit,
        onProgress: (current: Int, total: Int, name: String) -> Unit,
        keepExistingDates: Boolean = true,
        renameToDate: Boolean = false,
        isCancelled: () -> Boolean = { false },
    ): FixByFilenameResult {
        val r = TakeoutProcessor.process(
            sourceRoot, outputRoot, context,
            TakeoutOptions(skipIfHasExif = keepExistingDates, useSidecars = false, renameToDate = renameToDate),
            onProgress, onLog, isCancelled,
        )
        if (r.errorMsg.isNotBlank()) onLog("✗ ${r.errorMsg}")
        if (r.stoppedEarly.isNotBlank()) onLog("Stopped: ${r.stoppedEarly}")
        return FixByFilenameResult(
            copied        = r.fixed + r.fromFilename + r.keptExisting + r.noDate + r.unsupported,
            exifWritten   = r.fixed + r.fromFilename,
            noDate        = r.noDate,
            alreadyExists = r.alreadyThere,
            unsupported   = r.unsupported,
            failed        = r.errors,
            keptExisting  = r.keptExisting,
            ignored       = r.ignored,
            archives      = r.archives,
            stoppedEarly  = r.stoppedEarly,
            errorMsg      = r.errorMsg,
        )
    }

    // ── Folder walking ────────────────────────────────────────────────────────

    private val OWN_DIRS = setOf("no-date", "error", "_duplicates", "_photofix", "@eadir", ".thumbnails", "lost.dir")

    private fun collectItems(root: DocumentFile, out: MutableList<DatedFile>, notes: MutableList<String>) {
        // A year folder may be selected directly (e.g. when the drive root can't be granted).
        val rootYear = PhotoLogic.parseYearFolder(root.name ?: "")
        if (rootYear != null) {
            collectYear(root, rootYear, out, notes)
            return
        }
        for (d in safeList(root, notes)) {
            if (!d.isDirectory) continue
            val n = d.name ?: continue
            val y = PhotoLogic.parseYearFolder(n)
            if (y != null) collectYear(d, y, out, notes)
            else if (n.lowercase() !in OWN_DIRS && !n.startsWith(".")) notes += "skipped folder that is not a year: $n"
        }
    }

    private fun collectYear(yearDir: DocumentFile, year: Int, out: MutableList<DatedFile>, notes: MutableList<String>) {
        for (mdir in safeList(yearDir, notes)) {
            val mName = mdir.name ?: continue
            if (!mdir.isDirectory) continue
            val month = PhotoLogic.parseMonthFolder(mName)
            if (month == null) {
                if (mName.lowercase() !in OWN_DIRS && !mName.startsWith(".")) notes += "skipped folder that is not a month: ${yearDir.name}/$mName"
                continue
            }
            val loose = safeList(mdir, notes).count { it.isFile && PhotoLogic.isMedia(it.name ?: "") }
            if (loose > 0) notes += "$loose file(s) directly in ${yearDir.name}/$mName have no day folder; left as they are"
            for (ddir in safeList(mdir, notes)) {
                if (!ddir.isDirectory) continue
                val dName = ddir.name ?: continue
                val day = PhotoLogic.parseDayFolder(dName)
                if (day == null) { notes += "skipped folder that is not a day: ${yearDir.name}/$mName/$dName"; continue }
                val date = try { LocalDate.of(year, month, day) } catch (_: Exception) {
                    notes += "not a real date: ${yearDir.name}/$mName/$dName"
                    continue
                }
                collectMedia(ddir, date, out, notes)
            }
        }
    }

    /** Media in a day folder and any sub-folders below it (bursts, edits…). */
    private fun collectMedia(dir: DocumentFile, date: LocalDate, out: MutableList<DatedFile>, notes: MutableList<String>) {
        for (f in safeList(dir, notes)) {
            val n = f.name ?: continue
            if (f.isDirectory) {
                if (n.lowercase() !in OWN_DIRS && !n.startsWith(".")) collectMedia(f, date, out, notes)
            } else if (PhotoLogic.isMedia(n)) {
                out += DatedFile(f, dir, date)
            }
        }
    }

    private fun safeList(dir: DocumentFile, notes: MutableList<String>): List<DocumentFile> = try {
        dir.listFiles().toList()
    } catch (e: Exception) {
        notes += "could not open ${dir.name}: ${e.message}"
        emptyList()
    }

    /**
     * Writes [date] into [file] (which lives in [dir]) using the temp-file pattern:
     * copy SAF file → app cache, ExifInterface(path).saveAttributes(), then put
     * the result back. ExifInterface(FileDescriptor) fails with EBADF on some
     * SAF providers.
     *
     * Putting it back: the new bytes go to a hidden temp file next to the
     * original first; only once that is complete is the original replaced, so
     * an unplugged drive never leaves a half-written photo. Providers that
     * can't rename fall back to overwriting in place ("wt").
     */
    private fun writeExifDate(context: Context, file: DocumentFile, dir: DocumentFile, date: PhotoDate) {
        val name = file.name ?: throw java.io.IOException("File has no name")
        val tempFile = File(context.cacheDir, "exif_fix_${System.nanoTime()}.tmp")
        try {
            context.contentResolver.openInputStream(file.uri)?.use { inp ->
                tempFile.outputStream().use { out -> inp.copyTo(out) }
            } ?: throw java.io.IOException("Cannot open $name")

            val exif = ExifInterface(tempFile.absolutePath)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,  date.exif)
            exif.setAttribute(ExifInterface.TAG_DATETIME,           date.exif)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date.exif)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,  date.offsetString)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME,           date.offsetString)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, date.offsetString)
            exif.saveAttributes()

            if (replaceViaTemp(context, tempFile, file, dir, name)) return
            val written = context.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
                tempFile.inputStream().use { inp -> inp.copyTo(out) }
            } ?: throw java.io.IOException("Cannot write $name")
            if (written != tempFile.length()) throw java.io.IOException("Write was incomplete — check the drive")
        } finally {
            tempFile.delete()
        }
    }

    /** True when [file] was replaced by [data]; false when this provider can't do it that way. */
    private fun replaceViaTemp(context: Context, data: File, file: DocumentFile, dir: DocumentFile, name: String): Boolean {
        val side = try { dir.createFile("application/octet-stream", PhotoLogic.TMP_PREFIX + name) } catch (_: Exception) { null }
            ?: return false
        var originalDeleted = false
        try {
            val written = context.contentResolver.openOutputStream(side.uri, "w")?.use { out ->
                data.inputStream().use { it.copyTo(out) }
            } ?: -1L
            if (written != data.length()) {
                side.delete()
                if (written >= 0) throw java.io.IOException("Write was incomplete — check the drive")
                return false
            }
            if (!file.delete()) { side.delete(); return false }
            originalDeleted = true
            if (side.renameTo(name)) return true
            // Original is gone and the temp can't be renamed: write the data under the real name.
            val dest = dir.createFile("application/octet-stream", name)
                ?: throw java.io.IOException("Could not restore $name — its data is in ${side.name}")
            context.contentResolver.openOutputStream(dest.uri, "w")?.use { out ->
                data.inputStream().use { it.copyTo(out) }
            } ?: throw java.io.IOException("Could not restore $name — its data is in ${side.name}")
            side.delete()
            return true
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            if (originalDeleted) throw java.io.IOException("Could not finish writing $name — its data is in ${side.name}", e)
            try { side.delete() } catch (_: Exception) { }
            return false
        }
    }
}
