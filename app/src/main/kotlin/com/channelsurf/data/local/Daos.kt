package com.channelsurf.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CatalogDao {
    @Query("SELECT * FROM channels ORDER BY number")
    abstract fun observeChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM media_assets")
    abstract fun observeAssets(): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM playlist_entries ORDER BY channelId, pool, position")
    abstract fun observePlaylistEntries(): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM channels ORDER BY number")
    abstract suspend fun channels(): List<ChannelEntity>

    @Query("SELECT * FROM media_assets")
    abstract suspend fun assets(): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE id = :id")
    abstract suspend fun asset(id: String): MediaAssetEntity?

    @Query("SELECT * FROM media_assets WHERE archiveIdentifier = :identifier")
    abstract suspend fun assetsForIdentifier(identifier: String): List<MediaAssetEntity>

    @Query("SELECT * FROM playlist_entries ORDER BY channelId, pool, position")
    abstract suspend fun playlistEntries(): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist_entries WHERE channelId = :channelId AND pool = :pool ORDER BY position")
    abstract suspend fun playlist(channelId: String, pool: String): List<PlaylistEntryEntity>

    @Query("SELECT COUNT(*) FROM channels")
    abstract suspend fun channelCount(): Int

    @Query("SELECT COUNT(*) FROM media_assets")
    abstract suspend fun assetCount(): Int

    @Query("SELECT DISTINCT archiveIdentifier FROM media_assets")
    abstract suspend fun identifiers(): List<String>

    @Upsert abstract suspend fun upsertChannels(channels: List<ChannelEntity>)
    @Upsert abstract suspend fun upsertAssets(assets: List<MediaAssetEntity>)
    @Upsert abstract suspend fun upsertAsset(asset: MediaAssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPlaylistEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE channelId = :channelId AND pool = :pool")
    abstract suspend fun clearPlaylist(channelId: String, pool: String)

    @Query("DELETE FROM playlist_entries WHERE assetId = :assetId")
    abstract suspend fun removeAssetFromAllPlaylists(assetId: String)

    @Query("DELETE FROM channels") abstract suspend fun clearChannels()
    @Query("DELETE FROM media_assets") abstract suspend fun clearAssets()
    @Query("DELETE FROM playlist_entries") abstract suspend fun clearPlaylists()
    @Query("DELETE FROM media_assets WHERE id = :id") abstract suspend fun deleteAsset(id: String)

    @Query("UPDATE media_assets SET healthStatus = :health, lastCheckedAtUtcMs = :at, lastStatusCode = :code WHERE id = :id")
    abstract suspend fun updateHealth(id: String, health: String, at: Long, code: Int?)

    @Query("UPDATE media_assets SET rightsStatus = 'BLOCKED', rightsNotes = :notes WHERE archiveIdentifier = :identifier")
    abstract suspend fun blockIdentifier(identifier: String, notes: String)

    /** Replace one channel's playlist atomically, keeping positions dense. */
    @Transaction
    open suspend fun replacePlaylist(channelId: String, pool: String, assetIds: List<String>) {
        clearPlaylist(channelId, pool)
        insertPlaylistEntries(assetIds.mapIndexed { i, id -> PlaylistEntryEntity(channelId, pool, i, id) })
    }

    @Transaction
    open suspend fun replaceCatalog(
        channels: List<ChannelEntity>,
        assets: List<MediaAssetEntity>,
        entries: List<PlaylistEntryEntity>,
    ) {
        clearPlaylists()
        clearChannels()
        clearAssets()
        upsertChannels(channels)
        upsertAssets(assets)
        insertPlaylistEntries(entries)
    }
}

@Dao
abstract class ScheduleDao {
    @Query("SELECT * FROM program_blocks WHERE channelId = :channelId AND startUtcMs < :toUtcMs AND startUtcMs + durationMs > :fromUtcMs ORDER BY startUtcMs")
    abstract suspend fun window(channelId: String, fromUtcMs: Long, toUtcMs: Long): List<ProgramBlockEntity>

    @Query("SELECT * FROM program_blocks WHERE channelId = :channelId AND startUtcMs <= :nowUtcMs AND startUtcMs + durationMs > :nowUtcMs LIMIT 1")
    abstract suspend fun current(channelId: String, nowUtcMs: Long): ProgramBlockEntity?

    @Query("DELETE FROM program_blocks WHERE startUtcMs + durationMs < :beforeUtcMs")
    abstract suspend fun pruneBefore(beforeUtcMs: Long)

    @Query("DELETE FROM program_blocks WHERE revision != :revision")
    abstract suspend fun deleteOtherRevisions(revision: String)

    @Upsert abstract suspend fun upsert(blocks: List<ProgramBlockEntity>)

    @Query("SELECT COUNT(*) FROM program_blocks") abstract suspend fun count(): Int

    @Transaction
    open suspend fun replaceWindow(revision: String, pruneBeforeUtcMs: Long, blocks: List<ProgramBlockEntity>) {
        deleteOtherRevisions(revision)
        pruneBefore(pruneBeforeUtcMs)
        blocks.chunked(500).forEach { upsert(it) }
    }
}

@Dao
abstract class AuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertRevision(r: ManifestRevisionEntity)

    @Query("SELECT * FROM manifest_revisions ORDER BY importedAtUtcMs DESC LIMIT :limit")
    abstract suspend fun recentRevisions(limit: Int): List<ManifestRevisionEntity>

    @Query("DELETE FROM manifest_revisions WHERE revision NOT IN (SELECT revision FROM manifest_revisions ORDER BY importedAtUtcMs DESC LIMIT :keep)")
    abstract suspend fun trimRevisions(keep: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTakedown(t: TakedownEntity)

    @Query("SELECT * FROM takedowns ORDER BY atUtcMs DESC")
    abstract suspend fun takedowns(): List<TakedownEntity>

    @Query("SELECT * FROM takedowns ORDER BY atUtcMs DESC")
    abstract fun observeTakedowns(): Flow<List<TakedownEntity>>

    @Query("DELETE FROM takedowns WHERE archiveIdentifier = :identifier")
    abstract suspend fun deleteTakedown(identifier: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertDismissed(d: DismissedEntity)

    @Query("SELECT archiveIdentifier FROM dismissed_items")
    abstract suspend fun dismissedIdentifiers(): List<String>
}

@Dao
abstract class ArchiveCacheDao {
    @Query("SELECT * FROM archive_cache WHERE url = :url")
    abstract suspend fun get(url: String): ArchiveCacheEntity?

    @Upsert abstract suspend fun put(e: ArchiveCacheEntity)

    @Query("DELETE FROM archive_cache WHERE fetchedAtUtcMs < :beforeUtcMs")
    abstract suspend fun pruneBefore(beforeUtcMs: Long)
}
