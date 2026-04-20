package com.naibaf7.openrower.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface RowingMachineDao {
    @Query("SELECT * FROM rowing_machines ORDER BY name ASC")
    fun getAll(): LiveData<List<RowingMachine>>

    @Query("SELECT * FROM rowing_machines WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): RowingMachine?

    @Insert
    suspend fun insert(machine: RowingMachine): Long

    @Update
    suspend fun update(machine: RowingMachine)

    @Delete
    suspend fun delete(machine: RowingMachine)
}
