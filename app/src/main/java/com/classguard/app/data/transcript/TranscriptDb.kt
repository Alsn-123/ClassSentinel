package com.classguard.app.data.transcript

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

/** 一次开启转写的监听会话。 */
@Entity(tableName = "transcript_sessions")
data class TranscriptSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
)

/** 一句转写文本；触发提醒的句子带关键词标记。 */
@Entity(tableName = "transcript_segments")
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeMillis: Long,
    val text: String,
    val isTrigger: Boolean = false,
    val keyword: String? = null,
)

@Dao
interface TranscriptDao {

    @Insert
    suspend fun insertSession(session: TranscriptSessionEntity): Long

    @Insert
    suspend fun insertSegment(segment: TranscriptSegmentEntity)

    @Query("UPDATE transcript_sessions SET endTime = :endTime WHERE id = :sessionId")
    suspend fun endSession(sessionId: Long, endTime: Long)

    @Query("SELECT * FROM transcript_sessions ORDER BY startTime DESC")
    fun sessions(): Flow<List<TranscriptSessionEntity>>

    @Query("SELECT * FROM transcript_sessions WHERE id = :sessionId")
    suspend fun session(sessionId: Long): TranscriptSessionEntity?

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY timeMillis ASC, id ASC")
    suspend fun segments(sessionId: Long): List<TranscriptSegmentEntity>

    @Query("DELETE FROM transcript_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("DELETE FROM transcript_segments WHERE sessionId = :sessionId")
    suspend fun deleteSegmentsOf(sessionId: Long)

    @Query("DELETE FROM transcript_sessions")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM transcript_segments")
    suspend fun deleteAllSegments()
}

@Database(
    entities = [TranscriptSessionEntity::class, TranscriptSegmentEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ClassSentinelDb : RoomDatabase() {

    abstract fun transcriptDao(): TranscriptDao

    companion object {
        @Volatile
        private var instance: ClassSentinelDb? = null

        fun get(context: Context): ClassSentinelDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClassSentinelDb::class.java,
                    "class_sentinel.db",
                ).build().also { instance = it }
            }
    }
}

/** 转写仓储：服务与复盘界面共用。 */
class TranscriptRepository(private val db: ClassSentinelDb) {

    private val dao = db.transcriptDao()

    suspend fun startSession(startTime: Long): Long = dao.insertSession(TranscriptSessionEntity(startTime = startTime))

    suspend fun addSegment(sessionId: Long, timeMillis: Long, text: String, isTrigger: Boolean, keyword: String?) =
        dao.insertSegment(
            TranscriptSegmentEntity(
                sessionId = sessionId,
                timeMillis = timeMillis,
                text = text,
                isTrigger = isTrigger,
                keyword = keyword,
            )
        )

    suspend fun endSession(sessionId: Long, endTime: Long) = dao.endSession(sessionId, endTime)

    fun sessions(): Flow<List<TranscriptSessionEntity>> = dao.sessions()

    suspend fun session(sessionId: Long): TranscriptSessionEntity? = dao.session(sessionId)

    suspend fun segments(sessionId: Long): List<TranscriptSegmentEntity> = dao.segments(sessionId)

    suspend fun deleteSession(sessionId: Long) {
        dao.deleteSegmentsOf(sessionId)
        dao.deleteSession(sessionId)
    }

    suspend fun deleteAll() {
        dao.deleteAllSegments()
        dao.deleteAllSessions()
    }

    companion object {
        @Volatile
        private var instance: TranscriptRepository? = null

        fun get(context: Context): TranscriptRepository =
            instance ?: synchronized(this) {
                instance ?: TranscriptRepository(ClassSentinelDb.get(context)).also { instance = it }
            }
    }
}
