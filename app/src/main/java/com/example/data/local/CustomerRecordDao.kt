package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CustomerRecordDao {
  @Query("SELECT * FROM customers_database WHERE normalizedPhone = :phone LIMIT 1")
  suspend fun findByPhone(phone: String): CustomerRecordEntity?

  @Query("SELECT * FROM customers_database ORDER BY updatedAtTimestamp DESC")
  suspend fun getAll(): List<CustomerRecordEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(customer: CustomerRecordEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(customers: List<CustomerRecordEntity>)

  @Query("SELECT COUNT(*) FROM customers_database")
  suspend fun getCount(): Int

  @Query("DELETE FROM customers_database")
  suspend fun clearAll()
}
