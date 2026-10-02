package com.firebolt141.ubertrag.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset

object DateExtractor {

    /** EXIF date of an image as wall-clock time encoded as UTC ms (null if none). */
    fun fromImageStream(stream: InputStream): Long? = try {
        val exif = ExifInterface(stream)
        PhotoLogic.parseExifDateTime(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        )?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
    } catch (_: Exception) { null }

    /** Video creation date (UTC epoch ms, as stored by the camera), or null. */
    fun fromVideoUri(context: Context, uri: Uri): Long? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { parseMediaDate(it) }
        } catch (_: Exception) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) { }
        }
    }

    /**
     * The date already stored inside a photo or video, or null.
     * Images: DateTimeOriginal (+ OffsetTimeOriginal when present).
     * Videos: the QuickTime creation date, which is UTC.
     */
    fun embedded(context: Context, uri: Uri, name: String): PhotoDate? {
        if (PhotoLogic.isVideo(name)) {
            val ms = fromVideoUri(context, uri) ?: return null
            return PhotoDate.fromEpochSec(ms / 1000, "embedded")
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val exif = ExifInterface(s)
                val wall = PhotoLogic.parseExifDateTime(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ) ?: return@use null
                val off = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.let {
                    try { ZoneOffset.of(it.trim()) } catch (_: Exception) { null }
                }
                PhotoDate(wall, off, "embedded")
            }
        } catch (_: Exception) { null }
    }

    /** "20240315T143022.000Z" → epoch ms (UTC); null for zero/1904 dates. */
    private fun parseMediaDate(s: String): Long? = try {
        val cleaned = s.replace(Regex("\\.\\d+Z?$"), "").replace("T", "")
        if (cleaned.length < 8) null
        else {
            val year  = cleaned.substring(0, 4).toInt()
            val month = cleaned.substring(4, 6).toInt()
            val day   = cleaned.substring(6, 8).toInt()
            val hour  = if (cleaned.length >= 10) cleaned.substring(8, 10).toInt() else 0
            val min   = if (cleaned.length >= 12) cleaned.substring(10, 12).toInt() else 0
            val sec   = if (cleaned.length >= 14) cleaned.substring(12, 14).toInt() else 0
            if (year < 1971) null
            else LocalDateTime.of(year, month, day, hour, min, sec).toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    } catch (_: Exception) { null }
}
