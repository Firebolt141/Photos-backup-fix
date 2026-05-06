package com.firebolt141.ubertrag.util

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ExifFixResult(
    val fixed: Int = 0,
    val alreadyHasDate: Int = 0,
    val skipped: Int = 0,   // unsupported format (HEIC, RAW, video)
    val failed: Int = 0,
)

object ExifFixer {

    private val MONTH_NAMES = mapOf(
        "January" to 1, "February" to 2, "March" to 3,
        "April" to 4, "May" to 5, "June" to 6,
        "July" to 7, "August" to 8, "September" to 9,
        "October" to 10, "November" to 11, "December" to 12
    )

    // ExifInterface 1.3.7 can write EXIF to JPEG, PNG, WebP.
    // HEIC/HEIF: read-only. RAW, video: not supported at all.
    private val WRITABLE_EXTS = setOf(".jpg", ".jpeg", ".png", ".webp")

    private val EXIF_DATE_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun fixMissingExif(
        root: DocumentFile,
        context: Context,
        onProgress: (current: Int, total: Int, name: String) -> Unit,
    ): ExifFixResult {
        val workItems = mutableListOf<Pair<DocumentFile, LocalDate>>()
        collectItems(root, workItems)

        var fixed = 0
        var alreadyHasDate = 0
        var skipped = 0
        var failed = 0

        workItems.forEachIndexed { idx, (file, date) ->
            onProgress(idx + 1, workItems.size, file.name ?: "")
            val ext = file.name?.substringAfterLast('.')
                ?.lowercase()?.let { ".$it" } ?: ""

            if (ext !in WRITABLE_EXTS) {
                skipped++
                return@forEachIndexed
            }

            try {
                if (hasExifDate(context, file)) {
                    alreadyHasDate++
                } else {
                    writeExifDate(context, file, date)
                    fixed++
                }
            } catch (_: Exception) {
                failed++
            }
        }

        return ExifFixResult(fixed, alreadyHasDate, skipped, failed)
    }

    private fun collectItems(root: DocumentFile, out: MutableList<Pair<DocumentFile, LocalDate>>) {
        for (yearDir in root.listFiles()) {
            if (!yearDir.isDirectory) continue
            val year = yearDir.name?.toIntOrNull()?.takeIf { it in 2000..2040 } ?: continue

            for (monthDir in yearDir.listFiles()) {
                if (!monthDir.isDirectory) continue
                // Handle both "January" (new) and "01" (legacy numeric) month folders
                val month = MONTH_NAMES[monthDir.name]
                    ?: monthDir.name?.toIntOrNull()?.takeIf { it in 1..12 }
                    ?: continue

                for (dayDir in monthDir.listFiles()) {
                    if (!dayDir.isDirectory) continue
                    val dayNum = parseDayFolderNum(dayDir.name ?: "") ?: continue
                    val date = try {
                        LocalDate.of(year, month, dayNum)
                    } catch (_: Exception) { continue }

                    for (file in dayDir.listFiles()) {
                        if (file.isFile) out.add(file to date)
                    }
                }
            }
        }
    }

    /**
     * Parses day folder names in multiple formats:
     *   "January 15"  → 15
     *   "15"          → 15
     *   "01"          → 1   (legacy numeric)
     */
    private fun parseDayFolderNum(name: String): Int? {
        if (' ' in name) return name.substringAfterLast(' ').toIntOrNull()
        return name.toIntOrNull()
    }

    private fun hasExifDate(context: Context, file: DocumentFile): Boolean {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val tag = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                !tag.isNullOrBlank() && !tag.startsWith("0000")
            } ?: false
        } catch (_: Exception) { false }
    }

    /**
     * Writes the folder-derived date to EXIF using the temp-file pattern:
     *   1. Copy file bytes to a temp file in app cache.
     *   2. Open ExifInterface on the temp file (supports in-place rewrite).
     *   3. saveAttributes().
     *   4. Stream the modified bytes back to the SAF URI with "wt" (truncate) mode.
     *
     * This avoids the EBADF / partial-write issues that can occur when using
     * ExifInterface(FileDescriptor) directly with some SAF providers.
     *
     * Time-of-day is set to noon UTC so the file sorts consistently within
     * its day folder; only the date portion is meaningful here.
     */
    private fun writeExifDate(context: Context, file: DocumentFile, date: LocalDate) {
        val dateStr = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 12, 0, 0)
            .format(EXIF_DATE_FMT)

        val tempFile = File(context.cacheDir, "exif_fix_${System.nanoTime()}.tmp")
        try {
            // 1. Copy source → temp
            context.contentResolver.openInputStream(file.uri)?.use { inp ->
                tempFile.outputStream().use { out -> inp.copyTo(out) }
            } ?: throw Exception("Cannot open source: ${file.name}")

            // 2. Write EXIF on temp file
            ExifInterface(tempFile.absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,  dateStr)
                setAttribute(ExifInterface.TAG_DATETIME,           dateStr)
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,  "+00:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME,           "+00:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, "+00:00")
                saveAttributes()
            }

            // 3. Write back to SAF ("wt" = write + truncate existing content)
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
                tempFile.inputStream().use { inp -> inp.copyTo(out) }
            } ?: throw Exception("Cannot open destination: ${file.name}")

        } finally {
            tempFile.delete()
        }
    }
}
