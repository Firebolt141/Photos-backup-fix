package com.firebolt141.ubertrag.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.firebolt141.ubertrag.data.AppDatabase
import com.firebolt141.ubertrag.data.CopyStatus
import com.firebolt141.ubertrag.data.Prefs
import com.firebolt141.ubertrag.data.QueueItem
import com.firebolt141.ubertrag.util.CopyOutcome
import com.firebolt141.ubertrag.util.DateExtractor
import com.firebolt141.ubertrag.util.ExifFixResult
import com.firebolt141.ubertrag.util.ExifFixer
import com.firebolt141.ubertrag.util.FixByFilenameResult
import com.firebolt141.ubertrag.util.PhotoLogic
import com.firebolt141.ubertrag.util.RenameResult
import com.firebolt141.ubertrag.util.SafTree
import com.firebolt141.ubertrag.util.StorageHelper
import com.firebolt141.ubertrag.util.TakeoutOptions
import com.firebolt141.ubertrag.util.TakeoutProcessor
import com.firebolt141.ubertrag.util.TakeoutResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.ZoneId

data class CopySummary(
    val copied: Int = 0,
    val skipped: Int = 0,          // already on the drive
    val failed: Int = 0,
    val gone: Int = 0,             // deleted from the phone since the scan
    val noDate: Int = 0,           // copied to no-date/
    val bytes: Long = 0,
    val stoppedEarly: String = "", // drive full / unplugged / stopped by the user
    val problem: String = "",      // could not start at all
) {
    val total: Int get() = copied + skipped + failed + gone
}

class SyncRepository(private val context: Context) {

    private companion object { const val TAG = "SyncRepository" }

    private val db    = AppDatabase.get(context)
    private val dao   = db.queueDao()
    private val prefs = Prefs(context)

    val queueFlow        = dao.observeAll()
    val pendingCountFlow = dao.observePendingCount()
    val copiedCountFlow  = dao.observeCopiedCount()

    // ── scan ─────────────────────────────────────────────────────────────

    /**
     * Adds photos/videos from the phone's gallery that aren't in the queue yet.
     * Dates are stored as wall-clock time ("taken at 23:30 local") encoded as
     * UTC ms, the same convention as the drive folders and EXIF.
     */
    suspend fun scanMedia(): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "scanMedia start")
        if (!prefs.wallDatesMigrated.first()) {
            // Before 1.1 the queue stored UTC instants, which put late-evening
            // photos in the next day's folder. Re-read unfinished items.
            dao.deleteUnfinished()
            prefs.setWallDatesMigrated()
        }
        val existingIds = dao.getAllMediaIds().toHashSet()
        val newItems    = mutableListOf<QueueItem>()
        val zone        = ZoneId.systemDefault()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_TAKEN,
        )

        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).forEach { collection ->
            val isVideo = collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol    = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol  = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol  = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dataCol  = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    if (mediaId in existingIds) continue

                    val path      = cursor.getString(dataCol) ?: ""
                    val name      = cursor.getString(nameCol)?.takeIf { it.isNotBlank() }
                        ?: path.substringAfterLast('/').takeIf { it.isNotBlank() }
                        ?: "media_$mediaId"
                    val mimeType  = cursor.getString(mimeCol)?.takeIf { it.isNotBlank() }
                        ?: if (isVideo) "video/*" else "image/*"
                    val dateAdded = cursor.getLong(addedCol) * 1000L
                    val taken     = cursor.getLong(takenCol).takeIf { it > 0 }
                        ?.let { PhotoLogic.wallMs(it, zone) }
                    val dateTaken = taken
                        ?: refineDateTaken(mediaId, isVideo, zone)
                        ?: PhotoLogic.dateFromFilename(name, zone)?.wallMs

                    newItems += QueueItem(
                        mediaId      = mediaId,
                        displayName  = name,
                        mimeType     = mimeType,
                        absolutePath = path,
                        dateAdded    = dateAdded,
                        dateTaken    = dateTaken,
                    )
                }
            }
        }

        if (newItems.isNotEmpty()) dao.insertAll(newItems)
        Log.d(TAG, "scanMedia done — found ${newItems.size} new items")
        newItems.size
    }

    private fun refineDateTaken(mediaId: Long, isVideo: Boolean, zone: ZoneId): Long? =
        if (!isVideo) {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
            try {
                context.contentResolver.openInputStream(uri)?.use { DateExtractor.fromImageStream(it) }
            } catch (_: Exception) { null }
        } else {
            // Video dates are UTC instants: show them in the phone's time zone.
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
            DateExtractor.fromVideoUri(context, uri)?.let { PhotoLogic.wallMs(it, zone) }
        }

    // ── copy ─────────────────────────────────────────────────────────────

    /**
     * Copies queued items to the drive: <drive>/2024/March/March_15/name.
     * Items without any date go to <drive>/no-date/. A file already there
     * with the same size is not copied again; a different file with the same
     * name gets an _1 suffix. Stops early when the drive is full or unplugged.
     */
    suspend fun copyPending(
        fromMs: Long = 0L,
        toMs: Long = 0L,
        isCancelled: () -> Boolean = { false },
        onProgress: suspend (done: Int, total: Int, currentName: String, speedMBps: Double) -> Unit,
    ): CopySummary = withContext(Dispatchers.IO) {
        Log.d(TAG, "copyPending start")
        val driveUriStr = prefs.driveUri.first()
            ?: return@withContext CopySummary(problem = "No drive selected")
        if (!StorageHelper.isDriveMounted(context, driveUriStr))
            return@withContext CopySummary(problem = "The drive isn't connected (or can't be written to)")
        val root = DocumentFile.fromTreeUri(context, driveUriStr.toUri())
            ?: return@withContext CopySummary(problem = "Cannot open the drive folder — select it again")
        if (!prefs.wallDatesMigrated.first()) scanMedia()

        // Picker dates are midnight UTC of the chosen days; queue dates are wall-clock in UTC ms.
        val effectiveTo = if (toMs > 0) toMs + 86_400_000L - 1 else Long.MAX_VALUE
        val zone = ZoneId.systemDefault()
        val pending = dao.getPending().filter { item ->
            fromMs == 0L && effectiveTo == Long.MAX_VALUE ||
                (item.dateTaken ?: PhotoLogic.wallMs(item.dateAdded, zone)) in fromMs..effectiveTo
        }

        val tree       = SafTree(context)
        val startMs    = System.currentTimeMillis()
        var totalBytes = 0L
        var done = 0; var copied = 0; var skipped = 0; var failed = 0; var gone = 0; var noDate = 0
        var stopped = ""

        Log.d(TAG, "copyPending: ${pending.size} items to copy")
        for (item in pending) {
            if (isCancelled()) { stopped = "Stopped by you"; break }
            onProgress(done, pending.size, item.displayName, calcSpeed(totalBytes, startMs))
            val srcUri = sourceUri(item)
            val size = sourceSize(srcUri)
            if (size == null) {
                dao.updateStatusAndError(item.id, CopyStatus.SKIPPED, "No longer on the phone")
                gone++; done++
                continue
            }
            val parts = item.dateTaken?.let { PhotoLogic.dateFolders(it) } ?: listOf(PhotoLogic.NO_DATE_DIR)
            val destDir = tree.dirPath(root, parts)
            val outcome = if (destDir == null) {
                CopyOutcome.Failed(
                    if (StorageHelper.isDriveMounted(context, driveUriStr)) "Cannot create folder ${parts.joinToString("/")}"
                    else "File or folder not found (was the drive disconnected?)"
                )
            } else try {
                tree.copyInto(srcUri, size, destDir, item.displayName)
            } catch (e: Exception) {
                CopyOutcome.Failed(tree.friendly(e))
            }
            when (outcome) {
                is CopyOutcome.Copied -> {
                    totalBytes += outcome.bytes
                    dao.updateStatusAndError(item.id, CopyStatus.COPIED, null)
                    copied++
                    if (item.dateTaken == null) noDate++
                }
                is CopyOutcome.AlreadyThere -> {
                    dao.updateStatusAndError(item.id, CopyStatus.SKIPPED, null)
                    skipped++
                }
                is CopyOutcome.Failed -> {
                    dao.updateStatusAndError(item.id, CopyStatus.FAILED, outcome.message)
                    Log.w(TAG, "  failed: ${item.displayName} — ${outcome.message}")
                    failed++
                    if (tree.isFatal(outcome.message)) { stopped = outcome.message; done++; break }
                }
            }
            done++
        }
        onProgress(done, pending.size, "", calcSpeed(totalBytes, startMs))
        Log.d(TAG, "copyPending done — copied=$copied skipped=$skipped failed=$failed gone=$gone stopped=$stopped")
        CopySummary(copied, skipped, failed, gone, noDate, totalBytes, stopped)
    }

    private fun sourceUri(item: QueueItem): Uri =
        if (item.mimeType.startsWith("video/"))
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.mediaId)
        else
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.mediaId)

    /** Size in bytes, -1 if unknown, or null when the item no longer exists. */
    private fun sourceSize(uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else if (c.isNull(0)) -1L else c.getLong(0)
        } ?: -1L
    } catch (_: FileNotFoundException) {
        null
    } catch (_: Exception) {
        -1L
    }

    private fun calcSpeed(bytes: Long, startMs: Long): Double {
        val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
        return if (elapsed > 0.5) bytes / 1_048_576.0 / elapsed else 0.0
    }

    suspend fun retryFailed()  = withContext(Dispatchers.IO) { dao.resetFailed() }
    suspend fun clearCopied()  = withContext(Dispatchers.IO) { dao.clearCopied() }
    /** Marks everything as pending again, e.g. to fill a new drive. Files already there are skipped. */
    suspend fun requeueAll()   = withContext(Dispatchers.IO) { dao.resetAll() }

    // ── drive utilities ──────────────────────────────────────────────────

    private suspend fun driveRoot(): DocumentFile? {
        val driveUriStr = prefs.driveUri.first() ?: return null
        if (!StorageHelper.isDriveMounted(context, driveUriStr)) return null
        return DocumentFile.fromTreeUri(context, driveUriStr.toUri())
    }

    suspend fun renameLegacyFolders(dryRun: Boolean, onProgress: (String) -> Unit): RenameResult? =
        withContext(Dispatchers.IO) {
            val root = driveRoot() ?: return@withContext null
            StorageHelper.renameLegacyFolders(context, root, dryRun, onProgress)
        }

    suspend fun fixMissingExif(
        fixMismatched: Boolean,
        onProgress: (current: Int, total: Int, name: String) -> Unit,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): ExifFixResult? = withContext(Dispatchers.IO) {
        val root = driveRoot() ?: return@withContext null
        ExifFixer.fixMissingExif(root, context, onProgress, onLog, fixMismatched, isCancelled)
    }

    suspend fun fixByFilename(
        sourceUri:  String,
        outputUri:  String,
        keepExistingDates: Boolean,
        renameToDate: Boolean,
        onLog:      (String) -> Unit,
        onProgress: (current: Int, total: Int, name: String) -> Unit,
        isCancelled: () -> Boolean,
    ): FixByFilenameResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "fixByFilename source=$sourceUri output=$outputUri")
        val src = treeOrNull(sourceUri)
            ?: return@withContext FixByFilenameResult(errorMsg = "Cannot open the source folder. Select it again.")
        val out = treeOrNull(outputUri)
            ?: return@withContext FixByFilenameResult(errorMsg = "Cannot open the output folder. Select it again.")
        ExifFixer.fixByFilename(src, out, context, onLog, onProgress, keepExistingDates, renameToDate, isCancelled)
    }

    suspend fun processTakeout(
        sourceUri:  String,
        outputUri:  String,
        options:    TakeoutOptions,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
        onLog:      (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): TakeoutResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "processTakeout start — source=$sourceUri output=$outputUri")
        val sourceRoot = treeOrNull(sourceUri)
            ?: return@withContext TakeoutResult(errorMsg = "Cannot open the selected source folder. Try selecting it again.")
        val outputRoot = treeOrNull(outputUri)
            ?: return@withContext TakeoutResult(errorMsg = "Cannot open the selected output folder. Try selecting it again.")
        TakeoutProcessor.process(sourceRoot, outputRoot, context, options, onProgress, onLog, isCancelled)
    }

    private fun treeOrNull(uri: String): DocumentFile? = try {
        DocumentFile.fromTreeUri(context, uri.toUri())?.takeIf { it.exists() }
    } catch (_: Exception) { null }
}
