package com.firebolt141.ubertrag.util

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ── Public data types ─────────────────────────────────────────────────────────

data class TakeoutMeta(
    val timestampSec: Long?   = null,
    val latitude:     Double? = null,
    val longitude:    Double? = null,
    val altitude:     Double? = null,
    val description:  String  = "",
)

data class TakeoutOptions(
    val skipIfHasExif: Boolean = true,
)

data class TakeoutResult(
    val total:           Int = 0,
    val fixed:           Int = 0,  // date+GPS written from JSON sidecar
    val fromFilename:    Int = 0,  // date written from filename only
    val noDate:          Int = 0,  // no JSON, no filename date — copied as-is
    val skippedExisting: Int = 0,  // already had EXIF date, not modified
    val unsupported:     Int = 0,  // HEIC / video / RAW — copied, no EXIF write
    val errors:          Int = 0,
)

// ── Processor ─────────────────────────────────────────────────────────────────

object TakeoutProcessor {

    private val IMAGE_EXTS = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif",
        "webp", "heic", "heif", "raw", "cr2", "nef", "arw",
        "dng", "orf", "rw2", "srw", "pef",
    )
    private val VIDEO_EXTS = setOf(
        "mp4", "mov", "avi", "m4v", "mkv", "wmv", "3gp",
        "mpg", "mpeg", "mts", "m2ts", "flv", "webm", "ts",
    )
    // ExifInterface 1.3.7+ supports writing to these formats.
    private val WRITABLE_EXTS = setOf("jpg", "jpeg", "png", "webp")
    private val UNSUPPORTED_EXTS = setOf(
        "heic", "heif", "raw", "cr2", "nef", "arw", "dng",
        "orf", "rw2", "srw", "pef",
    ) + VIDEO_EXTS

    private val EXIF_DATE_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    // ── Pure / testable helpers ───────────────────────────────────────────────

    /**
     * Returns all JSON sidecar filename candidates for [fileName].
     * Exact port of find_json() from core.py.
     */
    fun jsonCandidateNames(fileName: String): List<String> {
        val dotIdx = fileName.lastIndexOf('.')
        val stem = if (dotIdx >= 0) fileName.substring(0, dotIdx) else fileName
        val ext  = if (dotIdx >= 0) fileName.substring(dotIdx) else ""

        val out = mutableListOf(
            "$stem$ext.json",
            "$stem.json",
        )

        // Google truncates the JSON filename at 46 characters
        if (fileName.length > 46) out += "${fileName.take(46)}.json"

        // "photo(1).jpg" → base="photo", num="1"
        val numbered = Regex("""^(.+)\((\d+)\)$""").matchEntire(stem)
        if (numbered != null) {
            val base = numbered.groupValues[1]
            val num  = numbered.groupValues[2]
            out += "$base$ext($num).json"
            out += "$base($num).json"
        }

        if (stem.endsWith("-edited")) {
            val orig = stem.dropLast(7)
            out += "$orig$ext.json"
            out += "$orig.json"
        }

        out += "$stem$ext.supplemental-metadata.json"
        out += "$stem.supplemental-metadata.json"

        return out
    }

    /**
     * Parses a Takeout JSON sidecar string into [TakeoutMeta].
     * Pure function — no Android dependencies.
     */
    fun parseMeta(jsonText: String): TakeoutMeta {
        return try {
            val obj = JSONObject(jsonText)

            var ts: Long? = null
            for (key in listOf("photoTakenTime", "creationTime")) {
                val t = obj.optJSONObject(key) ?: continue
                val v = t.optString("timestamp").toLongOrNull() ?: continue
                if (v > 0) { ts = v; break }
            }

            var lat: Double? = null
            var lon: Double? = null
            var alt: Double? = null
            for (key in listOf("geoDataExif", "geoData")) {
                val geo = obj.optJSONObject(key) ?: continue
                val la = geo.optDouble("latitude",  0.0)
                val lo = geo.optDouble("longitude", 0.0)
                if (la != 0.0 || lo != 0.0) {
                    lat = la; lon = lo
                    alt = geo.optDouble("altitude", 0.0)
                    break
                }
            }

            TakeoutMeta(ts, lat, lon, alt, obj.optString("description", ""))
        } catch (_: Exception) { TakeoutMeta() }
    }

    /**
     * Extracts a Unix timestamp (seconds) from a filename containing an
     * embedded date such as IMG_20240315_143022.jpg.
     * Pure function — no Android dependencies.
     */
    fun tsFromFilename(name: String): Long? {
        val m = Regex("""(?<!\d)(\d{4})[_\-]?(\d{2})[_\-]?(\d{2})(?!\d)""").find(name)
            ?: return null
        val y  = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d  = m.groupValues[3].toIntOrNull() ?: return null
        if (y !in 2000..2040 || mo !in 1..12 || d !in 1..31) return null
        return try {
            java.time.LocalDateTime.of(y, mo, d, 12, 0, 0)
                .toInstant(ZoneOffset.UTC).epochSecond
        } catch (_: Exception) { null }
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    fun process(
        sourceRoot: DocumentFile,
        outputRoot: DocumentFile,
        context:    Context,
        options:    TakeoutOptions,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
    ): TakeoutResult {
        val items = mutableListOf<Pair<DocumentFile, DocumentFile>>() // (file, parentDir)
        collectMediaFiles(sourceRoot, items)

        var fixed = 0; var fromFilename = 0; var noDate = 0
        var skippedExisting = 0; var unsupported = 0; var errors = 0

        // Cache per-directory sibling maps to avoid re-listing the same folder
        val siblingMaps = HashMap<String, Map<String, DocumentFile>>()

        items.forEachIndexed { idx, (file, parentDir) ->
            onProgress(idx + 1, items.size, file.name ?: "")
            try {
                val sibMap = siblingMaps.getOrPut(parentDir.uri.toString()) {
                    buildSiblingMap(parentDir)
                }
                when (processOne(file, sibMap, outputRoot, context, options)) {
                    Outcome.FIXED       -> fixed++
                    Outcome.FROM_FILENAME -> fromFilename++
                    Outcome.NO_DATE     -> noDate++
                    Outcome.SKIPPED     -> skippedExisting++
                    Outcome.UNSUPPORTED -> unsupported++
                }
            } catch (_: Exception) { errors++ }
        }

        return TakeoutResult(items.size, fixed, fromFilename, noDate, skippedExisting, unsupported, errors)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private enum class Outcome { FIXED, FROM_FILENAME, NO_DATE, SKIPPED, UNSUPPORTED }

    private fun collectMediaFiles(
        dir: DocumentFile,
        out: MutableList<Pair<DocumentFile, DocumentFile>>,
    ) {
        for (child in dir.listFiles()) {
            when {
                child.isDirectory -> collectMediaFiles(child, out)
                child.isFile -> {
                    val ext = child.name?.substringAfterLast('.')?.lowercase() ?: continue
                    if (ext in IMAGE_EXTS || ext in VIDEO_EXTS) out.add(child to dir)
                }
            }
        }
    }

    private fun buildSiblingMap(dir: DocumentFile): Map<String, DocumentFile> =
        dir.listFiles().filter { it.isFile }
            .associateBy { it.name?.lowercase() ?: "" }

    private fun processOne(
        file:       DocumentFile,
        sibMap:     Map<String, DocumentFile>,
        outputRoot: DocumentFile,
        context:    Context,
        options:    TakeoutOptions,
    ): Outcome {
        val name = file.name ?: return Outcome.NO_DATE
        val ext  = name.substringAfterLast('.').lowercase()
        val isWritable = ext in WRITABLE_EXTS

        // Locate JSON sidecar and parse metadata
        val jsonFile = findJson(name, sibMap)
        val meta = if (jsonFile != null) {
            context.contentResolver.openInputStream(jsonFile.uri)
                ?.use { parseMeta(it.bufferedReader().readText()) }
                ?: TakeoutMeta()
        } else TakeoutMeta()

        val effectiveTsSec = meta.timestampSec ?: tsFromFilename(name)

        // Check existing EXIF — only possible for writable formats
        if (options.skipIfHasExif && isWritable && effectiveTsSec != null) {
            if (hasExifDate(context, file)) return Outcome.SKIPPED
        }

        // Determine destination (null = no date info at all)
        val destDir = StorageHelper.resolveDestDir(
            outputRoot, effectiveTsSec?.let { it * 1000L }
        ) ?: return Outcome.NO_DATE

        // Skip if file already copied (idempotent re-runs)
        if (destDir.findFile(name) != null) return Outcome.SKIPPED

        // Copy file to destination
        val mimeType = file.type ?: "application/octet-stream"
        val destFile = destDir.createFile(mimeType, name)
            ?: throw Exception("Cannot create: $name")

        try {
            context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                context.contentResolver.openInputStream(file.uri)
                    ?.use { inp -> inp.copyTo(out) }
                    ?: throw Exception("Cannot read source: $name")
            } ?: throw Exception("Cannot write dest: $name")
        } catch (e: Exception) {
            destFile.delete()
            throw e
        }

        // Write EXIF for supported formats with a known date
        if (!isWritable || effectiveTsSec == null) {
            return if (ext in UNSUPPORTED_EXTS) Outcome.UNSUPPORTED else Outcome.NO_DATE
        }

        writeExif(context, destFile, meta, effectiveTsSec)

        return if (meta.timestampSec != null) Outcome.FIXED else Outcome.FROM_FILENAME
    }

    private fun findJson(fileName: String, sibMap: Map<String, DocumentFile>): DocumentFile? {
        for (candidate in jsonCandidateNames(fileName)) {
            sibMap[candidate.lowercase()]?.let { return it }
        }
        return null
    }

    private fun hasExifDate(context: Context, file: DocumentFile): Boolean = try {
        context.contentResolver.openInputStream(file.uri)?.use {
            val exif = ExifInterface(it)
            val tag = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            !tag.isNullOrBlank() && !tag.startsWith("0000")
        } ?: false
    } catch (_: Exception) { false }

    /**
     * Writes date (and GPS + description if available) to [destFile] using
     * the proven temp-file pattern to avoid EBADF issues with SAF providers.
     */
    private fun writeExif(
        context: Context,
        destFile: DocumentFile,
        meta: TakeoutMeta,
        tsSec: Long,
    ) {
        val instant = Instant.ofEpochSecond(tsSec).atOffset(ZoneOffset.UTC)
        val dateStr = instant.format(EXIF_DATE_FMT)

        val tmp = File(context.cacheDir, "takeout_exif_${System.nanoTime()}.tmp")
        try {
            // 1. Copy to temp
            context.contentResolver.openInputStream(destFile.uri)?.use { inp ->
                tmp.outputStream().use { out -> inp.copyTo(out) }
            } ?: throw Exception("Cannot open for EXIF read")

            // 2. Write EXIF on temp file
            ExifInterface(tmp.absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,  dateStr)
                setAttribute(ExifInterface.TAG_DATETIME,           dateStr)
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,  "+00:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME,           "+00:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, "+00:00")

                if (meta.latitude != null && meta.longitude != null) {
                    setLatLong(meta.latitude, meta.longitude)
                    meta.altitude?.let { alt ->
                        val altAbs = kotlin.math.abs(alt).toLong()
                        setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "$altAbs/1")
                        setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF,
                            if (alt >= 0) "0" else "1")
                    }
                    val dateOnly = instant.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"))
                    val timeOnly = instant.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    setAttribute(ExifInterface.TAG_GPS_DATESTAMP, dateOnly)
                    setAttribute(ExifInterface.TAG_GPS_TIMESTAMP,  timeOnly)
                }

                if (meta.description.isNotBlank()) {
                    setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, meta.description)
                }

                saveAttributes()
            }

            // 3. Write back (wt = write + truncate)
            context.contentResolver.openOutputStream(destFile.uri, "wt")?.use { out ->
                tmp.inputStream().use { inp -> inp.copyTo(out) }
            } ?: throw Exception("Cannot write back EXIF")

        } finally {
            tmp.delete()
        }
    }
}
