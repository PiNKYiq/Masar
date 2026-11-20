package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryStopDao {
  @Query("SELECT * FROM delivery_stops ORDER BY createdAt DESC")
  fun getAllStopsFlow(): Flow<List<DeliveryStopEntity>>

  @Query("SELECT * FROM delivery_stops ORDER BY createdAt DESC")
  suspend fun getAllStops(): List<DeliveryStopEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(stop: DeliveryStopEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(stops: List<DeliveryStopEntity>)

  @Query("DELETE FROM delivery_stops WHERE id = :id")
  suspend fun deleteById(id: String)

  @Query("DELETE FROM delivery_stops WHERE isDeleted = 1")
  suspend fun emptyDeleted()

  @Query("DELETE FROM delivery_stops")
  suspend fun clearAll()
}
