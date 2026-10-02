package com.firebolt141.ubertrag.util

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.ZoneId

data class TakeoutOptions(
    /** Keep a date that is already inside the file (camera date); only add missing GPS/caption. */
    val skipIfHasExif: Boolean = true,
    /** false = "Organize by date": ignore JSON sidecars, use embedded/filename dates. */
    val useSidecars:   Boolean = true,
    /** Rename copies to 2024-03-15_14-30-22.jpg. */
    val renameToDate:  Boolean = false,
)

data class TakeoutResult(
    val total:        Int    = 0,
    val fixed:        Int    = 0,  // date (+GPS) written from a JSON sidecar
    val fromFilename: Int    = 0,  // date written from the file name
    val keptExisting: Int    = 0,  // already had a date inside — copied, date kept
    val noDate:       Int    = 0,  // no date anywhere — copied to no-date/
    val alreadyThere: Int    = 0,  // same file already in the output (earlier run / duplicate)
    val unsupported:  Int    = 0,  // HEIC / RAW / video with a new date — sorted, date not written
    val errors:       Int    = 0,  // copied to error/ when possible
    val ignored:      Int    = 0,  // not photos/videos (PDF, TXT…) — left alone
    val archives:     Int    = 0,  // .zip/.tgz files found in the source (need unzipping first)
    val stoppedEarly: String = "", // non-blank when the run stopped (drive full / removed)
    val errorMsg:     String = "", // non-blank when processing could not start
) {
    /** Back-compat name used by older UI code. */
    val skippedExisting: Int get() = keptExisting
}

/**
 * Copies photos/videos from a source folder into year/month/day folders,
 * writing dates (and GPS/caption from Takeout sidecars) into the copies.
 * Same rules as the Windows tool: every file ends up somewhere — a dated
 * folder, no-date/<original sub-folders>/, or error/<original sub-folders>/.
 */
object TakeoutProcessor {

    private const val TAG = "TakeoutProcessor"

    // Back-compat delegates (tests and older callers)
    fun jsonCandidateNames(fileName: String) = PhotoLogic.jsonCandidateNames(fileName)
    fun parseMeta(jsonText: String) = PhotoLogic.parseMeta(jsonText)
    fun tsFromFilename(name: String) = PhotoLogic.tsFromFilename(name)

    private enum class Outcome { FIXED, FROM_FILENAME, KEPT, NO_DATE, ALREADY, UNSUPPORTED, ERROR }

    private class Item(val file: DocumentFile, val parent: DocumentFile, val relDirs: List<String>)

    fun process(
        sourceRoot: DocumentFile,
        outputRoot: DocumentFile,
        context:    Context,
        options:    TakeoutOptions,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
        onLog:      (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): TakeoutResult {
        Log.d(TAG, "process() source=${sourceRoot.uri} output=${outputRoot.uri} $options")
        val outputId = docId(outputRoot)
        if (outputId == docId(sourceRoot)) {
            return TakeoutResult(errorMsg = "The source and output must be different folders.")
        }
        val items = mutableListOf<Item>()
        val counts = IntArray(2)                       // [ignored, archives]
        onLog("Looking through ${sourceRoot.name ?: "the folder"}…")
        collect(sourceRoot, emptyList(), items, counts, outputId)
        if (counts[1] > 0) onLog("⚠ ${counts[1]} zip/archive file(s) found: unzip them first (Files app → tap the zip → Extract), then run again.")
        if (counts[0] > 0) onLog("${counts[0]} file(s) that aren't photos or videos are left alone.")
        onLog("Found ${items.size} photo(s) and video(s).")
        if (items.isEmpty()) {
            return TakeoutResult(ignored = counts[0], archives = counts[1])
        }

        val tree = SafTree(context)
        val zone = ZoneId.systemDefault()
        val sidecarMaps = HashMap<String, Map<String, DocumentFile>>()
        val n = IntArray(Outcome.values().size)
        var stopped = ""

        items.forEachIndexed { idx, item ->
            if (stopped.isNotEmpty() || isCancelled()) return@forEachIndexed
            val name = item.file.name ?: return@forEachIndexed
            onProgress(idx + 1, items.size, name)
            val outcome = try {
                processOne(item, name, tree, sidecarMaps, outputRoot, context, options, zone, onLog)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing $name", e)
                fail(item, name, tree, outputRoot, tree.friendly(e), onLog)
            }
            n[outcome.first.ordinal]++
            if (outcome.second.isNotEmpty() && tree.isFatal(outcome.second)) {
                stopped = outcome.second
                onLog("✗ Stopping: ${outcome.second}. Files done so far are safe; run again to continue.")
            }
        }
        if (isCancelled() && stopped.isEmpty()) stopped = "Stopped by you"
        val r = TakeoutResult(
            total        = items.size,
            fixed        = n[Outcome.FIXED.ordinal],
            fromFilename = n[Outcome.FROM_FILENAME.ordinal],
            keptExisting = n[Outcome.KEPT.ordinal],
            noDate       = n[Outcome.NO_DATE.ordinal],
            alreadyThere = n[Outcome.ALREADY.ordinal],
            unsupported  = n[Outcome.UNSUPPORTED.ordinal],
            errors       = n[Outcome.ERROR.ordinal],
            ignored      = counts[0],
            archives     = counts[1],
            stoppedEarly = stopped,
        )
        Log.d(TAG, "process() done: $r")
        return r
    }

    /**
     * The same folder reached through two different picks has different
     * URIs (…/tree/A/document/A/B vs …/tree/A/B/document/A/B); compare the
     * document id instead.
     */
    private fun docId(f: DocumentFile): String = try {
        android.provider.DocumentsContract.getDocumentId(f.uri)
    } catch (_: Exception) { f.uri.toString() }

    // ── Scan ──────────────────────────────────────────────────────────────────

    private val SKIP_DIRS = setOf(
        "@eadir", ".thumbnails", ".trash", ".trashes", "\$recycle.bin", "system volume information",
        "_photofix", "_duplicates", "lost.dir",
    )

    private fun collect(
        dir: DocumentFile, rel: List<String>, out: MutableList<Item>, counts: IntArray, outputId: String,
    ) {
        val children = try { dir.listFiles() } catch (e: Exception) {
            Log.w(TAG, "Cannot list ${dir.name}: ${e.message}")
            return
        }
        for (child in children) {
            val n = child.name ?: continue
            when {
                child.isDirectory -> {
                    // Skip our own output when it was picked inside the source folder.
                    if (n.lowercase() in SKIP_DIRS || docId(child) == outputId) continue
                    collect(child, rel + n, out, counts, outputId)
                }
                PhotoLogic.isMedia(n) -> out.add(Item(child, dir, rel))
                PhotoLogic.isArchive(n) -> counts[1]++
                !PhotoLogic.isJunk(n) && !n.lowercase().endsWith(".json") && !n.startsWith(".") -> counts[0]++
            }
        }
    }

    // ── One file ──────────────────────────────────────────────────────────────

    private fun processOne(
        item: Item,
        name: String,
        tree: SafTree,
        sidecarMaps: HashMap<String, Map<String, DocumentFile>>,
        outputRoot: DocumentFile,
        context: Context,
        options: TakeoutOptions,
        zone: ZoneId,
        onLog: (String) -> Unit,
    ): Pair<Outcome, String> {
        val size = item.file.length()
        if (size == 0L) return fail(item, name, tree, outputRoot, "The file is empty (0 bytes), probably a broken copy", onLog)

        // Sidecar metadata
        var meta = TakeoutMeta()
        if (options.useSidecars) {
            val sibs = sidecarMaps.getOrPut(item.parent.uri.toString()) {
                (try { item.parent.listFiles() } catch (_: Exception) { emptyArray() })
                    .filter { it.isFile && PhotoLogic.isSidecarName(it.name ?: "") }
                    .associateBy { (it.name ?: "").lowercase() }
            }
            PhotoLogic.findSidecar(name, sibs.keys)?.let { key ->
                sibs[key]?.let { json ->
                    // A broken .json must not send the photo to error/: just ignore it.
                    try {
                        context.contentResolver.openInputStream(json.uri)?.use {
                            meta = PhotoLogic.parseMeta(it.bufferedReader().readText())
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "unreadable sidecar ${json.name}: ${e.message}")
                    }
                }
            }
        }

        // Date priority: photoTakenTime → filename → creationTime (upload date)
        var date: PhotoDate? = null
        if (meta.timestampSec != null && meta.timestampSource == "photoTakenTime") {
            date = PhotoDate.fromEpochSec(meta.timestampSec!!, "sidecar")
        }
        if (date == null) date = PhotoLogic.dateFromFilename(name, zone)
        if (date == null && meta.timestampSec != null) date = PhotoDate.fromEpochSec(meta.timestampSec!!, "sidecar")

        val embedded = if (options.skipIfHasExif || date == null) DateExtractor.embedded(context, item.file.uri, name) else null
        val keepEmbedded = embedded != null && options.skipIfHasExif
        val place: PhotoDate? = when {
            keepEmbedded -> embedded
            date != null -> date
            else         -> embedded
        }

        // Destination: dated folder, or no-date/<original sub-folders>
        val parts = if (place != null) PhotoLogic.dateFolders(place.wallMs)
                    else listOf(PhotoLogic.NO_DATE_DIR) + item.relDirs
        val destDir = tree.dirPath(outputRoot, parts)
            ?: return fail(item, name, tree, outputRoot, "Cannot create folder ${parts.joinToString("/")}", onLog)
        val outName = if (options.renameToDate && place != null) PhotoLogic.dateFileName(place, name) else name

        val writable = PhotoLogic.isWritable(name)
        val dateToWrite = when {
            place == null || keepEmbedded || place.source == "embedded" -> null
            embedded != null && embedded.wall == place.wall -> null
            else -> place
        }
        // Metadata is written into a private copy *before* the file goes to the
        // output, so an interrupted write never damages a finished copy, and a
        // re-run produces the same bytes (same size → recognised as already there).
        var exifError = ""
        val prepared: File? = if (writable && place != null) try {
            prepareExif(context, item.file.uri, meta, dateToWrite)
        } catch (e: Exception) {
            Log.w(TAG, "EXIF write failed for $name: ${e.message}")
            exifError = e.message ?: e.javaClass.simpleName
            null
        } else null

        val outcome = try {
            val srcUri = prepared?.let { android.net.Uri.fromFile(it) } ?: item.file.uri
            val srcSize = prepared?.length() ?: size
            tree.copyInto(srcUri, srcSize, destDir, outName)
        } finally {
            prepared?.delete()
        }
        val copied = when (outcome) {
            is CopyOutcome.AlreadyThere -> {
                onLog("=  $name already in ${parts.joinToString("/")}")
                return Outcome.ALREADY to ""
            }
            is CopyOutcome.Failed -> return fail(item, name, tree, outputRoot, outcome.message, onLog)
            is CopyOutcome.Copied -> outcome
        }
        val where = parts.joinToString("/") + "/" + copied.name

        return when {
            place == null -> { onLog("⚠  $name: no date found → $where"); Outcome.NO_DATE to "" }
            keepEmbedded || place.source == "embedded" -> { onLog("✓  $name → $where (kept its own date)"); Outcome.KEPT to "" }
            !writable -> { onLog("→  $name → $where (this format can't store a date)"); Outcome.UNSUPPORTED to "" }
            exifError.isNotEmpty() -> { onLog("→  $name → $where (date not written: $exifError)"); Outcome.UNSUPPORTED to "" }
            place.source == "sidecar" -> { onLog("✓  $name → $where"); Outcome.FIXED to "" }
            else -> { onLog("✓  $name → $where (date from name)"); Outcome.FROM_FILENAME to "" }
        }
    }

    /** Last resort: copy the file to error/<original sub-folders>/ so nothing is lost. */
    private fun fail(
        item: Item, name: String, tree: SafTree, outputRoot: DocumentFile, msg: String, onLog: (String) -> Unit,
    ): Pair<Outcome, String> {
        if (!tree.isFatal(msg)) {
            val dir = tree.dirPath(outputRoot, listOf(PhotoLogic.ERROR_DIR) + item.relDirs)
            if (dir != null) {
                val r = tree.copyInto(item.file.uri, item.file.length(), dir, name)
                if (r is CopyOutcome.Copied || r is CopyOutcome.AlreadyThere) {
                    onLog("✗  $name: $msg (copied to error/)")
                    return Outcome.ERROR to msg
                }
            }
        }
        onLog("✗  $name: $msg")
        return Outcome.ERROR to msg
    }

    /**
     * Copies [source] into the app cache and writes the date (when [date] is
     * non-null) plus any missing GPS/caption into that copy. Returns the
     * temp file, or null when there is nothing to change (the caller then
     * copies the original as is). The caller deletes the returned file.
     * (Path-based ExifInterface: the FileDescriptor variant fails with EBADF
     * on some SAF providers.)
     */
    private fun prepareExif(context: Context, source: android.net.Uri, meta: TakeoutMeta, date: PhotoDate?): File? {
        val wantsGps = meta.latitude != null && meta.longitude != null
        if (date == null && !wantsGps && meta.description.isBlank()) return null
        val tmp = File(context.cacheDir, "takeout_exif_${System.nanoTime()}.tmp")
        var keep = false
        try {
            context.contentResolver.openInputStream(source)?.use { inp ->
                tmp.outputStream().use { out -> inp.copyTo(out) }
            } ?: throw java.io.IOException("Cannot read the file")

            val exif = ExifInterface(tmp.absolutePath)
            var changed = false
            if (date != null) {
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,  date.exif)
                exif.setAttribute(ExifInterface.TAG_DATETIME,           date.exif)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date.exif)
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,  date.offsetString)
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME,           date.offsetString)
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, date.offsetString)
                changed = true
            }
            if (wantsGps && exif.latLong == null) {
                exif.setLatLong(meta.latitude!!, meta.longitude!!)
                meta.altitude?.let { alt ->
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${kotlin.math.abs(alt).toLong()}/1")
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (alt >= 0) "0" else "1")
                }
                changed = true
            }
            if (meta.description.isNotBlank() && exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).isNullOrBlank()) {
                exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, meta.description)
                changed = true
            }
            if (!changed) return null
            exif.saveAttributes()
            keep = true
            return tmp
        } finally {
            if (!keep) tmp.delete()
        }
    }
}
