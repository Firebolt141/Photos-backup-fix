package com.firebolt141.photosync.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object DateExtractor {
    private val EXIF_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun fromImageStream(stream: InputStream): Long? = try {
        val exif = ExifInterface(stream)
        (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
            ?.takeIf { it.isNotBlank() && !it.startsWith("0000") }
            ?.let { parseExifDate(it) }
    } catch (_: Exception) { null }

    fun fromVideoUri(context: Context, uri: Uri): Long? = try {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.let { parseMediaDate(it) }
        }
    } catch (_: Exception) { null }

    private fun parseExifDate(s: String): Long? = try {
        LocalDateTime.parse(s.trim(), EXIF_FMT)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    } catch (_: DateTimeParseException) { null }

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
            LocalDateTime.of(year, month, day, hour, min, sec)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }
    } catch (_: Exception) { null }
}
