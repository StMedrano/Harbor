package dev.stmedrano.harbor.parent.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FamilyCacheRow::class, PendingChildRow::class], version = 1, exportSchema = true)
abstract class ParentDatabase : RoomDatabase() {
    abstract fun familyCache(): FamilyCacheDao
    companion object {
        fun open(context: Context, name: String = "harbor-parent-cache") =
            Room.databaseBuilder(context.applicationContext, ParentDatabase::class.java, name).build()
    }
}
