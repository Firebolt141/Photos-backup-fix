package com.firebolt141.ubertrag.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue ORDER BY COALESCE(dateTaken, dateAdded) DESC")
    fun observeAll(): Flow<List<QueueItem>>

    @Query("SELECT * FROM queue WHERE status IN ('PENDING', 'FAILED') ORDER BY COALESCE(dateTaken, dateAdded) ASC")
    suspend fun getPending(): List<QueueItem>

    @Query("SELECT COUNT(*) FROM queue WHERE status IN ('PENDING', 'FAILED')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM queue WHERE status = 'COPIED'")
    fun observeCopiedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM queue WHERE status = 'SKIPPED'")
    fun observeSkippedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM queue WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT mediaId FROM queue")
    suspend fun getAllMediaIds(): List<Long>

    @Query("SELECT * FROM queue WHERE mediaId = :mediaId LIMIT 1")
    suspend fun findByMediaId(mediaId: Long): QueueItem?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<QueueItem>)

    @Update
    suspend fun update(item: QueueItem)

    @Query("UPDATE queue SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: CopyStatus)

    @Query("UPDATE queue SET status = :status, errorMsg = :msg WHERE id = :id")
    suspend fun updateStatusAndError(id: Long, status: CopyStatus, msg: String?)

    @Query("UPDATE queue SET status = 'PENDING', errorMsg = NULL WHERE status = 'FAILED'")
    suspend fun resetFailed()

    @Query("DELETE FROM queue WHERE status = 'COPIED'")
    suspend fun clearCopied()
}
