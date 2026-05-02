package com.firebolt141.photosync.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.Calendar
import java.util.TimeZone

object StorageHelper {

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
        val year  = cal.get(Calendar.YEAR).toString()
        val month = "%02d".format(cal.get(Calendar.MONTH) + 1)
        val day   = "%02d".format(cal.get(Calendar.DAY_OF_MONTH))
        val yDir  = getOrCreateDir(root, year)   ?: return null
        val mDir  = getOrCreateDir(yDir, month)  ?: return null
        return getOrCreateDir(mDir, day)
    }
}
