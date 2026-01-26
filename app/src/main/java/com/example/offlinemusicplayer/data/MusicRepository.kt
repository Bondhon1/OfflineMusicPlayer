package com.example.offlinemusicplayer.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.offlinemusicplayer.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val contentUri: String,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L
) {
    fun toDomain(): Song = Song(
        id = id,
        title = title,
        artist = artist,
        durationMs = durationMs,
        contentUri = Uri.parse(contentUri),
        playCount = playCount,
        lastPlayedAt = lastPlayedAt
    )
}

@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val songId: Long,
    val timestamp: Long
)

@Dao
interface MusicDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAllSongs(): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongs(songs: List<SongEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSong(song: SongEntity)

    @Query("UPDATE songs SET playCount = :playCount, lastPlayedAt = :lastPlayedAt WHERE id = :id")
    suspend fun updatePlayStats(id: Long, playCount: Int, lastPlayedAt: Long)

    @Insert
    suspend fun insertHistory(entry: PlaybackHistoryEntity)
}

@Database(entities = [SongEntity::class, PlaybackHistoryEntity::class], version = 1, exportSchema = false)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun getInstance(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music-db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class MusicRepository(context: Context) {
    private val musicDao: MusicDao = MusicDatabase.getInstance(context).musicDao()

    suspend fun scanDeviceSongs(): List<Song> = withContext(Dispatchers.IO) {
        val existingSongs = musicDao.getAllSongs().associateBy { it.id }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        val selection = MediaStore.Audio.Media.IS_MUSIC + "!= 0"
        val sortOrder = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"

        val scannedEntities = mutableListOf<SongEntity>()
        val scannedSongs = mutableListOf<Song>()

        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn).orEmpty()
                val artist = cursor.getString(artistColumn).orEmpty()
                val duration = cursor.getLong(durationColumn)
                val contentUri = Uri.withAppendedPath(uri, id.toString())
                val previous = existingSongs[id]
                val entity = SongEntity(
                    id = id,
                    title = title,
                    artist = artist,
                    durationMs = duration,
                    contentUri = contentUri.toString(),
                    playCount = previous?.playCount ?: 0,
                    lastPlayedAt = previous?.lastPlayedAt ?: 0L
                )
                scannedEntities.add(entity)
                scannedSongs.add(entity.toDomain())
            }
        }

        if (scannedEntities.isNotEmpty()) {
            musicDao.upsertSongs(scannedEntities)
        }

        scannedSongs
    }

    suspend fun loadCachedSongs(): List<Song> = withContext(Dispatchers.IO) {
        musicDao.getAllSongs().map { it.toDomain() }
    }

    suspend fun updatePlayStats(songId: Long, playCount: Int, lastPlayedAt: Long) = withContext(Dispatchers.IO) {
        musicDao.updatePlayStats(songId, playCount, lastPlayedAt)
    }

    suspend fun insertHistory(songId: Long, timestamp: Long) = withContext(Dispatchers.IO) {
        musicDao.insertHistory(PlaybackHistoryEntity(songId = songId, timestamp = timestamp))
    }
}
