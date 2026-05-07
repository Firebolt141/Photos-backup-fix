package com.firebolt141.ubertrag.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.util.Log
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.firebolt141.ubertrag.data.AppDatabase
import com.firebolt141.ubertrag.data.CopyStatus
import com.firebolt141.ubertrag.data.Prefs
import com.firebolt141.ubertrag.data.QueueItem
import com.firebolt141.ubertrag.util.DateExtractor
import com.firebolt141.ubertrag.util.ExifFixResult
import com.firebolt141.ubertrag.util.ExifFixer
import com.firebolt141.ubertrag.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

data class CopySummary(val copied: Int, val skipped: Int, val failed: Int)

class SyncRepository(private val context: Context) {

    private val db    = AppDatabase.get(context)
    private val dao   = db.queueDao()
    private val prefs = Prefs(context)

    val queueFlow        = dao.observeAll()
    val pendingCountFlow = dao.observePendingCount()
    val copiedCountFlow  = dao.observeCopiedCount()

    // ── scan ─────────────────────────────────────────────────────────────

    suspend fun scanMedia(): Int = withContext(Dispatchers.IO) {
        val existingIds = dao.getAllMediaIds().toHashSet()
        val newItems    = mutableListOf<QueueItem>()

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

                    val mimeType  = cursor.getString(mimeCol) ?: continue
                    val path      = cursor.getString(dataCol) ?: continue
                    val dateAdded = cursor.getLong(addedCol) * 1000L
                    val dateTaken = cursor.getLong(takenCol).takeIf { it > 0 }
                        ?: refineDateTaken(mediaId, mimeType)

                    newItems += QueueItem(
                        mediaId      = mediaId,
                        displayName  = cursor.getString(nameCol) ?: path.substringAfterLast('/'),
                        mimeType     = mimeType,
                        absolutePath = path,
                        dateAdded    = dateAdded,
                        dateTaken    = dateTaken,
                    )
                }
            }
        }

        if (newItems.isNotEmpty()) dao.insertAll(newItems)
        newItems.size
    }

    private fun refineDateTaken(mediaId: Long, mimeType: String): Long? {
        return if (mimeType.startsWith("image/")) {
            val uri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
            try {
                context.contentResolver.openInputStream(uri)
                    ?.use { DateExtractor.fromImageStream(it) }
            } catch (_: Exception) { null }
        } else {
            val uri = ContentUris.withAppendedId(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
            DateExtractor.fromVideoUri(context, uri)
        }
    }

    // ── copy ─────────────────────────────────────────────────────────────

    suspend fun copyPending(
        fromMs: Long = 0L,
        toMs: Long = 0L,
        onProgress: suspend (done: Int, total: Int, currentName: String, speedMBps: Double) -> Unit,
    ): CopySummary = withContext(Dispatchers.IO) {
        val driveUriStr = prefs.driveUri.first() ?: return@withContext CopySummary(0, 0, 0)
        if (!StorageHelper.isDriveMounted(context, driveUriStr)) return@withContext CopySummary(0, 0, 0)

        val root = DocumentFile.fromTreeUri(context, Uri.parse(driveUriStr))
            ?: return@withContext CopySummary(0, 0, 0)

        val effectiveTo = if (toMs > 0) toMs else Long.MAX_VALUE
        val pending = dao.getPending().let { all ->
            if (fromMs == 0L && effectiveTo == Long.MAX_VALUE) all
            else all.filter { item ->
                val ts = item.dateTaken ?: item.dateAdded
                ts in fromMs..effectiveTo
            }
        }

        val startMs    = System.currentTimeMillis()
        var totalBytes = 0L
        var done       = 0
        var copied     = 0
        var skipped    = 0
        var failed     = 0

        for (item in pending) {
            onProgress(done, pending.size, item.displayName, calcSpeed(totalBytes, startMs))
            try {
                val bytes = copyItem(item, root)
                if (bytes != null) {
                    totalBytes += bytes
                    dao.updateStatus(item.id, CopyStatus.COPIED)
                    copied++
                } else {
                    dao.updateStatus(item.id, CopyStatus.SKIPPED)
                    skipped++
                }
            } catch (e: Exception) {
                dao.updateStatusAndError(item.id, CopyStatus.FAILED, e.message)
                failed++
            }
            done++
        }
        onProgress(done, pending.size, "", calcSpeed(totalBytes, startMs))
        CopySummary(copied, skipped, failed)
    }

    // Returns bytes written, or null if the file already exists at the destination (skip).
    private fun copyItem(item: QueueItem, root: DocumentFile): Long? {
        val destDir = StorageHelper.resolveDestDir(root, item.dateTaken)
            ?: throw IOException("Failed to create destination directory")

        if (destDir.findFile(item.displayName) != null) return null   // already there → skip

        val mimeType = item.mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val destFile = destDir.createFile(mimeType, item.displayName)
            ?: throw IOException("Cannot create file: ${item.displayName}")

        val srcUri = if (item.mimeType.startsWith("image/")) {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.mediaId)
        } else {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.mediaId)
        }

        return try {
            context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                context.contentResolver.openInputStream(srcUri)?.use { inp ->
                    inp.copyTo(out)
                } ?: throw IOException("Cannot open source: ${item.displayName}")
            } ?: throw IOException("Cannot open destination: ${item.displayName}")
        } catch (e: Exception) {
            destFile.delete()
            throw e
        }
    }

    private fun calcSpeed(bytes: Long, startMs: Long): Double {
        val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
        return if (elapsed > 0.5) bytes / 1_048_576.0 / elapsed else 0.0
    }

    suspend fun retryFailed()  = withContext(Dispatchers.IO) { dao.resetFailed() }
    suspend fun clearCopied()  = withContext(Dispatchers.IO) { dao.clearCopied() }

    suspend fun renameLegacyFolders(onProgress: (String) -> Unit): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            val driveUriStr = prefs.driveUri.first() ?: return@withContext 0 to 0
            if (!StorageHelper.isDriveMounted(context, driveUriStr)) return@withContext 0 to 0
            val root = DocumentFile.fromTreeUri(context, Uri.parse(driveUriStr))
                ?: return@withContext 0 to 0
            StorageHelper.renameLegacyFolders(root, onProgress)
        }

    suspend fun fixMissingExif(
        onProgress: (current: Int, total: Int, name: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val driveUriStr = prefs.driveUri.first()
            ?: return@withContext ExifFixResult()
        if (!StorageHelper.isDriveMounted(context, driveUriStr))
            return@withContext ExifFixResult()
        val root = DocumentFile.fromTreeUri(context, Uri.parse(driveUriStr))
            ?: return@withContext ExifFixResult()
        ExifFixer.fixMissingExif(root, context, onProgress)
    }

    suspend fun processTakeout(
        sourceUri:  String,
        outputUri:  String,
        options:    com.firebolt141.ubertrag.util.TakeoutOptions,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        Log.d("SyncRepository", "processTakeout start — source=$sourceUri output=$outputUri")

        val sourceRoot = DocumentFile.fromTreeUri(context, Uri.parse(sourceUri))
        if (sourceRoot == null) {
            Log.e("SyncRepository", "processTakeout: cannot open source folder — uri=$sourceUri")
            return@withContext com.firebolt141.ubertrag.util.TakeoutResult(
                errorMsg = "Cannot open the selected source folder. Try selecting it again."
            )
        }

        val outputRoot = DocumentFile.fromTreeUri(context, Uri.parse(outputUri))
        if (outputRoot == null) {
            Log.e("SyncRepository", "processTakeout: cannot open output folder — uri=$outputUri")
            return@withContext com.firebolt141.ubertrag.util.TakeoutResult(
                errorMsg = "Cannot open the selected output folder. Try selecting it again."
            )
        }

        Log.d("SyncRepository", "processTakeout: source=${sourceRoot.name} output=${outputRoot.name}")
        com.firebolt141.ubertrag.util.TakeoutProcessor.process(
            sourceRoot, outputRoot, context, options, onProgress
        )
    }
}
