package com.firebolt141.ubertrag.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.Calendar
import java.util.TimeZone

object StorageHelper {

    private const val TAG = "StorageHelper"

    private val MONTHS = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    fun isDriveMounted(context: Context, treeUriString: String?): Boolean {
        if (treeUriString.isNullOrBlank()) return false
        return try {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUriString))?.exists() == true
        } catch (_: Exception) { false }
    }

    fun takePersistablePermission(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }

    fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile? =
        parent.findFile(name) ?: parent.createDirectory(name)

    fun resolveDestDir(root: DocumentFile, dateTakenMs: Long?): DocumentFile? {
        if (dateTakenMs == null || dateTakenMs <= 0) {
            return getOrCreateDir(root, "no-date")
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = dateTakenMs
        }
        val year      = cal.get(Calendar.YEAR).toString()
        val monthName = MONTHS[cal.get(Calendar.MONTH)]
        val dayName   = "$monthName ${cal.get(Calendar.DAY_OF_MONTH)}"
        val yDir = getOrCreateDir(root, year)      ?: return null
        val mDir = getOrCreateDir(yDir, monthName) ?: return null
        return getOrCreateDir(mDir, dayName)
    }

    /**
     * Renames legacy numeric month/day folders on the drive to spelled-out names.
     * e.g. 2024/01/15 → 2024/January/January 15
     *
     * Day folders are renamed first (while the parent URI is still valid), then
     * the month folder is renamed.
     */
    fun renameLegacyFolders(root: DocumentFile, onProgress: (String) -> Unit): Pair<Int, Int> {
        Log.d(TAG, "renameLegacyFolders start — root=${root.name}")
        var renamed = 0
        var errors  = 0
        val topLevel = root.listFiles()
        Log.d(TAG, "  ${topLevel.size} top-level entries")
        for (yearDir in topLevel) {
            if (!yearDir.isDirectory) continue
            val yearName = yearDir.name?.takeIf { it.matches(Regex("\\d{4}")) } ?: continue
            Log.d(TAG, "  year=$yearName")
            for (monthDir in yearDir.listFiles()) {
                if (!monthDir.isDirectory) continue
                val monthIdx = monthDir.name?.toIntOrNull()?.takeIf { it in 1..12 } ?: continue
                val mName = MONTHS[monthIdx - 1]
                Log.d(TAG, "    month=${monthDir.name} → $mName")
                for (dayDir in monthDir.listFiles()) {
                    if (!dayDir.isDirectory) continue
                    val dayNum = dayDir.name?.toIntOrNull()?.takeIf { it in 1..31 } ?: continue
                    val newDayName = "$mName $dayNum"
                    Log.d(TAG, "      day=${dayDir.name} → $newDayName")
                    onProgress("$yearName/$mName/$newDayName")
                    if (dayDir.renameTo(newDayName)) renamed++ else { Log.w(TAG, "      rename FAILED: ${dayDir.name}"); errors++ }
                }
                onProgress("$yearName/$mName")
                if (monthDir.renameTo(mName)) renamed++ else { Log.w(TAG, "    rename FAILED: ${monthDir.name}"); errors++ }
            }
        }
        Log.d(TAG, "renameLegacyFolders done — renamed=$renamed errors=$errors")
        return renamed to errors
    }
}
