package com.smartapes.openrower.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WorkoutEntity::class,
        SplitEntity::class,
        RowingMachine::class,
        CustomWorkoutEntity::class,
        WorkoutSegmentEntity::class
    ],
    version = 5
)
abstract class WorkoutDatabase : RoomDatabase() {

    abstract fun workoutDao(): WorkoutDao
    abstract fun rowingMachineDao(): RowingMachineDao
    abstract fun customWorkoutDao(): CustomWorkoutDao

    companion object {
        @Volatile private var INSTANCE: WorkoutDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS rowing_machines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        distFactor REAL NOT NULL,
                        dragFactor REAL NOT NULL,
                        flywheelInertia REAL NOT NULL
                    )"""
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS custom_workouts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdMs INTEGER NOT NULL,
                        modifiedMs INTEGER NOT NULL,
                        mediaFolderPath TEXT,
                        recoveryMusicPath TEXT
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS workout_segments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        workoutId INTEGER NOT NULL,
                        segmentIndex INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        durationSec REAL,
                        distanceMeters REAL,
                        paceStepsJson TEXT NOT NULL DEFAULT '[]',
                        musicSongPath TEXT,
                        musicPlaylistPath TEXT,
                        musicStartMs INTEGER NOT NULL DEFAULT 0,
                        musicEndMs INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (workoutId) REFERENCES custom_workouts(id) ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workout_segments_workoutId ON workout_segments(workoutId)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rowing_machines ADD COLUMN pulsesPerRevolution INTEGER NOT NULL DEFAULT 3")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing machines keep the safe default: hall sensor on the 3.3 V supply
                db.execSQL("ALTER TABLE rowing_machines ADD COLUMN sensorMode TEXT NOT NULL DEFAULT 'HALL3V3'")
            }
        }

        fun getInstance(context: Context): WorkoutDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WorkoutDatabase::class.java,
                    "openrower_workouts.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { INSTANCE = it }
            }
    }
}
