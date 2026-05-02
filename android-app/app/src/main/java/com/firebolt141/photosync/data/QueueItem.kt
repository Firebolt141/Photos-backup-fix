package com.firebolt141.photosync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CopyStatus { PENDING, COPIED, FAILED, SKIPPED }

@Entity(tableName = "queue")
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val displayName: String,
    val mimeType: String,
    val absolutePath: String,
    val dateTaken: Long?,
    val dateAdded: Long,
    val status: CopyStatus = CopyStatus.PENDING,
    val errorMsg: String? = null,
)
