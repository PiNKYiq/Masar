package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DeliveryStopEntity::class, CustomerRecordEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
  abstract fun deliveryStopDao(): DeliveryStopDao
  abstract fun customerRecordDao(): CustomerRecordDao

  companion object {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE delivery_stops ADD COLUMN categoryId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE delivery_stops ADD COLUMN categoryName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE delivery_stops ADD COLUMN categoryColorHex TEXT")
        db.execSQL("UPDATE delivery_stops SET categoryId = status WHERE categoryId = ''")
      }
    }

    fun getInstance(context: Context): AppDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          AppDatabase::class.java,
          "masar_delivery.db"
        )
          .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
          .addMigrations(MIGRATION_3_4)
          .fallbackToDestructiveMigration()
          .build()
        INSTANCE = instance
        instance
      }
    }
  }
}
