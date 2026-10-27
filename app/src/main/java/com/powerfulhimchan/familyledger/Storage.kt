package com.powerfulhimchan.familyledger

import androidx.room.*

@Entity(tableName="connections")
data class ConnectionEntity(@PrimaryKey val key: String,val payload: String,val name: String)
@Entity(tableName="snapshots")
data class SnapshotEntity(@PrimaryKey val key: String,val payload: String,val updated: Long)
@Dao interface LedgerDao {
    @Query("SELECT * FROM connections ORDER BY name") suspend fun connections(): List<ConnectionEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putConnection(value: ConnectionEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putSnapshot(value: SnapshotEntity)
    @Query("SELECT * FROM snapshots WHERE `key`=:key") suspend fun snapshot(key: String): SnapshotEntity?
    @Query("DELETE FROM connections WHERE `key`=:key") suspend fun removeConnection(key: String)
    @Query("DELETE FROM snapshots WHERE `key`=:key") suspend fun removeSnapshot(key: String)
    @Query("DELETE FROM snapshots") suspend fun clearSnapshots()
    @Query("DELETE FROM connections") suspend fun clearConnections()
}
@Database(entities=[ConnectionEntity::class,SnapshotEntity::class],version=1,exportSchema=false)
abstract class LedgerDatabase: RoomDatabase() { abstract fun dao(): LedgerDao }
