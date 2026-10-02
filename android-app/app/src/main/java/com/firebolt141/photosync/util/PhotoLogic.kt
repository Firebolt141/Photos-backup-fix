package com.firebolt141.ubertrag.util

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Pure Kotlin — no Android imports — so everything here runs in plain JVM
// unit tests. Mirrors the rules of the Windows tool (core.py) so both tools
// sort the same photo into the same folder.

/** Metadata from a Google Takeout JSON sidecar. */
data class TakeoutMeta(
    val timestampSec:    Long?        = null,
    val latitude:        Double?      = null,
    val longitude:       Double?      = null,
    val altitude:        Double?      = null,
    val description:     String       = "",
    /** "photoTakenTime" or "creationTime" (the upload date — a weak fallback). */
    val timestampSource: String       = "",
    val people:          List<String> = emptyList(),
    val favorited:       Boolean      = false,
)

/**
 * A wall-clock date/time plus the UTC offset it was taken in (null = unknown).
 * [source] is "sidecar", "filename", "embedded" or "folder".
 */
data class PhotoDate(val wall: LocalDateTime, val offset: ZoneOffset?, val source: String) {
    val exif: String get() = wall.format(EXIF_FMT)
    val offsetString: String get() = (offset ?: ZoneOffset.UTC).id.let { if (it == "Z") "+00:00" else it }

    /** Epoch milliseconds with the wall clock encoded as UTC — what folder logic uses. */
    val wallMs: Long get() = wall.toInstant(ZoneOffset.UTC).toEpochMilli()

    companion object {
        val EXIF_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

        fun fromEpochSec(sec: Long, source: String): PhotoDate =
            PhotoDate(LocalDateTime.ofEpochSecond(sec, 0, ZoneOffset.UTC), ZoneOffset.UTC, source)
    }
}

object PhotoLogic {

    const val YEAR_MIN = 2000
    const val YEAR_MAX = 2040
    const val NO_DATE_DIR = "no-date"
    const val ERROR_DIR = "error"
    const val TMP_PREFIX = ".~ubertrag~"

    val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    val IMAGE_EXTS = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "gif", "bmp", "tiff", "tif", "webp", "heic", "heif",
        "avif", "dng", "raw", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf",
        "rw2", "rwl", "srw", "pef", "3fr", "iiq", "x3f", "erf", "kdc", "dcr", "mrw",
    )
    val VIDEO_EXTS = setOf(
        "mp4", "mov", "qt", "avi", "m4v", "mkv", "wmv", "asf", "3gp", "3g2", "mpg", "mpeg",
        "mts", "m2ts", "m2t", "vob", "mod", "tod", "flv", "f4v", "webm", "ogv", "ts", "divx",
    )
    /** ExifInterface 1.3.7 can write these. HEIC/RAW/video are read-only. */
    val WRITABLE_EXTS = setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp")

    private val JUNK_PREFIXES = listOf("._", TMP_PREFIX, ".trashed-", ".pending-")

    fun ext(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isMedia(name: String): Boolean {
        val e = ext(name)
        return (e in IMAGE_EXTS || e in VIDEO_EXTS) && !isJunk(name)
    }

    fun isVideo(name: String): Boolean = ext(name) in VIDEO_EXTS

    fun isWritable(name: String): Boolean = ext(name) in WRITABLE_EXTS

    /** macOS AppleDouble files (._x.jpg), Android trash, our own temp files. */
    fun isJunk(name: String): Boolean = JUNK_PREFIXES.any { name.startsWith(it) }

    // ── Dates in filenames ────────────────────────────────────────────────────

    private val DATE_IN_NAME = Regex(
        """(?<!\d)(\d{4})[_\-.]?(\d{2})[_\-.]?(\d{2})""" +
        """(?:[_\-T. ]?(\d{2})[_\-.:]?(\d{2})[_\-.:]?(\d{2})(?:[._\-]?\d{1,3})?)?(?!\d)"""
    )
    private val EPOCH_NAME = Regex("""^(\d{10}|\d{13})$""")

    private fun validYmd(y: Int, m: Int, d: Int): Boolean {
        if (y !in YEAR_MIN..YEAR_MAX || m !in 1..12 || d !in 1..31) return false
        return try { LocalDate.of(y, m, d); true } catch (_: Exception) { false }
    }

    /**
     * Date (and time, when present) from a filename.
     *   IMG_20240315_143022.jpg → 2024-03-15 14:30:22 in [zone] (phones name files in local time)
     *   VID-20240315-WA0001.mp4 → 2024-03-15 12:00 UTC (date only → noon UTC, like the Windows tool)
     *   1710513022123.jpg       → Unix epoch milliseconds
     */
    fun dateFromFilename(name: String, zone: ZoneId = ZoneOffset.UTC): PhotoDate? {
        for (m in DATE_IN_NAME.findAll(name)) {
            val (ys, ms, ds) = m.destructured
            val y = ys.toInt(); val mo = ms.toInt(); val d = ds.toInt()
            if (!validYmd(y, mo, d)) continue
            val hh = m.groupValues[4]; val mi = m.groupValues[5]; val ss = m.groupValues[6]
            if (hh.isNotEmpty()) {
                val h = hh.toInt(); val mn = mi.toInt(); val s = ss.toInt()
                if (h < 24 && mn < 60 && s < 60) {
                    val wall = LocalDateTime.of(y, mo, d, h, mn, s)
                    return PhotoDate(wall, zone.rules.getOffset(wall), "filename")
                }
            }
            return PhotoDate(LocalDateTime.of(y, mo, d, 12, 0, 0), ZoneOffset.UTC, "filename")
        }
        val stem = name.substringBeforeLast('.')
        val em = EPOCH_NAME.matchEntire(stem) ?: return null
        val v = em.groupValues[1].toLong()
        val sec = if (em.groupValues[1].length == 13) v / 1000 else v
        val pd = PhotoDate.fromEpochSec(sec, "filename")
        return if (pd.wall.year in YEAR_MIN..YEAR_MAX) pd else null
    }

    /** Back-compat: Unix seconds of the filename's date at 12:00 UTC. */
    fun tsFromFilename(name: String): Long? {
        val pd = dateFromFilename(name) ?: return null
        return pd.wall.toLocalDate().atTime(12, 0).toInstant(ZoneOffset.UTC).epochSecond
    }

    /** "2024:03:15 14:30:22" (EXIF) → LocalDateTime; null for blank/zero/invalid dates. */
    fun parseExifDateTime(s: String?): LocalDateTime? {
        val t = s?.trim() ?: return null
        if (t.length < 19 || t.startsWith("0000")) return null
        return try {
            val dt = LocalDateTime.parse(t.substring(0, 19), PhotoDate.EXIF_FMT)
            if (dt.year < 1971) null else dt
        } catch (_: Exception) { null }
    }

    /** Epoch ms → the wall-clock time in [zone], re-encoded as UTC ms (see PhotoDate.wallMs). */
    fun wallMs(epochMs: Long, zone: ZoneId): Long {
        val local = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), zone)
        return local.toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    // ── Folder layout (2024/March/March_15) ───────────────────────────────────

    fun monthName(month: Int): String = MONTHS[month - 1]

    fun dayFolderName(month: Int, day: Int): String = "${monthName(month)}_${day.toString().padStart(2, '0')}"

    /** ["2024", "March", "March_15"] for a wall-clock date encoded as UTC ms. */
    fun dateFolders(wallMs: Long): List<String> {
        val dt = LocalDateTime.ofEpochSecond(Math.floorDiv(wallMs, 1000L), 0, ZoneOffset.UTC)
        return listOf(dt.year.toString(), monthName(dt.monthValue), dayFolderName(dt.monthValue, dt.dayOfMonth))
    }

    fun parseYearFolder(name: String): Int? =
        name.trim().takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in YEAR_MIN..YEAR_MAX }

    fun parseMonthFolder(name: String): Int? {
        val n = name.trim()
        MONTHS.indexOfFirst { it.equals(n, ignoreCase = true) }.takeIf { it >= 0 }?.let { return it + 1 }
        return n.toIntOrNull()?.takeIf { it in 1..12 && n.all(Char::isDigit) }
    }

    /** "January_07" → 7, "January 7" → 7, "15" → 15. */
    fun parseDayFolder(name: String): Int? {
        val n = name.trim()
        val tail = when {
            '_' in n -> n.substringAfterLast('_')
            ' ' in n -> n.substringAfterLast(' ')
            else     -> n
        }
        return tail.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it in 1..31 }
    }

    /**
     * New name for a legacy day folder inside month [month], or null if it is
     * already right or isn't a day folder. A day folder whose month prefix
     * disagrees with its parent (February_07 inside January/) is left alone.
     */
    fun legacyDayRename(month: Int, name: String): String? {
        val day = parseDayFolder(name) ?: return null
        val prefix = name.trim().split('_', ' ').takeIf { it.size > 1 }?.first()
        if (prefix != null && parseMonthFolder(prefix).let { it != null && it != month }) return null
        val want = dayFolderName(month, day)
        return if (name == want) null else want
    }

    /** New name for a legacy month folder ("01" → "January"), or null. */
    fun legacyMonthRename(name: String): String? {
        val m = parseMonthFolder(name) ?: return null
        return monthName(m).takeIf { it != name }
    }

    // ── Names ─────────────────────────────────────────────────────────────────

    /** IMG.jpg, IMG_1.jpg, IMG_2.jpg … — first name for which [taken] is false. */
    fun uniqueName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..99_999) {
            val cand = "${stem}_$i$ext"
            if (!taken(cand)) return cand
        }
        return "${stem}_${System.nanoTime()}$ext"
    }

    /** Name for "rename files to their date": 2024-03-15_14-30-22.jpg */
    fun dateFileName(pd: PhotoDate, originalName: String): String =
        pd.wall.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + "." + ext(originalName)

    // ── Google Takeout sidecars ───────────────────────────────────────────────

    private val NUMBERED = Regex("""^(.*)\((\d+)\)$""")
    private const val SUPPLEMENTAL = "supplemental-metadata"
    private const val TRUNCATE_AT = 46
    private val EDITED_SUFFIXES = listOf(
        "-edited", "-bearbeitet", "-modifié", "-modificato", "-editado", "-bewerkt",
        "-redigeret", "-redigert", "-redigerad", "-muokattu", "-edytowane", "-upravené",
        "-编辑", "-編集済み", "-편집됨",
    )
    private val NON_SIDECARS = setOf(
        "metadata.json", "print-subscriptions.json", "shared_album_comments.json",
        "user-generated-memory-titles.json",
    )

    private fun split(fileName: String): Pair<String, String> {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) to fileName.substring(dot) else fileName to ""
    }

    /** Exact sidecar names to try, in priority order (same as core.py). */
    fun jsonCandidateNames(fileName: String): List<String> {
        val (stem, ext) = split(fileName)
        val out = mutableListOf("$stem$ext.json", "$stem.json")
        if (fileName.length > TRUNCATE_AT) out += "${fileName.take(TRUNCATE_AT)}.json"
        NUMBERED.matchEntire(stem)?.let { m ->
            val base = m.groupValues[1]; val num = m.groupValues[2]
            out += "$base$ext($num).json"
            out += "$base($num).json"
            out += "$base$ext.$SUPPLEMENTAL($num).json"
        }
        val low = stem.lowercase()
        EDITED_SUFFIXES.firstOrNull { low.endsWith(it) }?.let { suf ->
            val orig = stem.dropLast(suf.length)
            out += "$orig$ext.json"
            out += "$orig.json"
            out += "$orig$ext.$SUPPLEMENTAL.json"
        }
        out += "$stem$ext.$SUPPLEMENTAL.json"
        out += "$stem.$SUPPLEMENTAL.json"
        return out
    }

    fun isSidecarName(name: String): Boolean =
        name.lowercase().let { it.endsWith(".json") && it !in NON_SIDECARS }

    /**
     * The sidecar for [fileName] among [siblings] (lower-case names in the same
     * folder), or null. Handles exact names, truncated/abbreviated
     * "supplemental-metadata" names, (N) numbering and Live Photo videos
     * (IMG_1.MP4 → IMG_1.HEIC.json). Returns the matching lower-case name.
     */
    fun findSidecar(fileName: String, siblings: Set<String>): String? {
        for (c in jsonCandidateNames(fileName)) {
            val k = c.lowercase()
            if (k in siblings && k !in NON_SIDECARS) return k
        }
        val (stem, ext) = split(fileName)
        val m = NUMBERED.matchEntire(stem)
        val base = m?.groupValues?.get(1) ?: stem
        val num = m?.groupValues?.get(2)
        val target = "$base$ext.$SUPPLEMENTAL".lowercase()
        val minLen = base.length + ext.length + 1
        for (j in siblings.sorted()) {
            if (!j.endsWith(".json") || j in NON_SIDECARS) continue
            var jstem = j.dropLast(5)
            val jm = NUMBERED.matchEntire(jstem)
            val jnum = jm?.groupValues?.get(2)
            if (jm != null) jstem = jm.groupValues[1]
            if (jnum != num || jstem.length < minOf(minLen, TRUNCATE_AT)) continue
            if (target.startsWith(jstem) && (jstem.length >= minLen || jstem.length >= TRUNCATE_AT)) return j
        }
        if (ext.removePrefix(".").lowercase() in VIDEO_EXTS) {
            val prefix = "$stem.".lowercase()
            for (j in siblings.sorted()) {
                if (!j.startsWith(prefix) || !j.endsWith(".json")) continue
                val other = j.removePrefix(prefix).substringBefore('.')
                if (other in IMAGE_EXTS) return j
            }
        }
        return null
    }

    /** Parses a Takeout JSON sidecar. Never throws. */
    fun parseMeta(jsonText: String): TakeoutMeta = try {
        val obj = JSONObject(jsonText)
        var ts: Long? = null
        var source = ""
        for (key in listOf("photoTakenTime", "creationTime")) {
            val t = obj.optJSONObject(key) ?: continue
            val v = t.optString("timestamp").toLongOrNull() ?: continue
            if (v in 1 until 4_102_444_800L) { ts = v; source = key; break }   // before 2100
        }
        var lat: Double? = null
        var lon: Double? = null
        var alt: Double? = null
        for (key in listOf("geoDataExif", "geoData")) {
            val geo = obj.optJSONObject(key) ?: continue
            val la = geo.optDouble("latitude", 0.0)
            val lo = geo.optDouble("longitude", 0.0)
            if ((la != 0.0 || lo != 0.0) && kotlin.math.abs(la) <= 90 && kotlin.math.abs(lo) <= 180) {
                lat = la; lon = lo
                alt = geo.optDouble("altitude", 0.0).takeIf { !it.isNaN() }
                break
            }
        }
        val people = mutableListOf<String>()
        obj.optJSONArray("people")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (name.isNotEmpty()) people += name
            }
        }
        TakeoutMeta(
            timestampSec    = ts,
            latitude        = lat,
            longitude       = lon,
            altitude        = alt,
            description     = obj.optString("description", "").trim(),
            timestampSource = source,
            people          = people,
            favorited       = obj.optBoolean("favorited", false),
        )
    } catch (_: Exception) { TakeoutMeta() }

    // ── Archives ──────────────────────────────────────────────────────────────

    fun isArchive(name: String): Boolean {
        val n = name.lowercase()
        return !n.startsWith("._") && (n.endsWith(".zip") || n.endsWith(".tgz") || n.endsWith(".tar.gz") || n.endsWith(".tar"))
    }
}
