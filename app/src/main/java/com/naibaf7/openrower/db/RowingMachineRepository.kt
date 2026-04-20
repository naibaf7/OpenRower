package com.naibaf7.openrower.db

import android.app.Application
import androidx.lifecycle.LiveData

class RowingMachineRepository(application: Application) {
    private val dao = WorkoutDatabase.getInstance(application).rowingMachineDao()

    val allMachines: LiveData<List<RowingMachine>> = dao.getAll()

    suspend fun insert(machine: RowingMachine): Long = dao.insert(machine)
    suspend fun update(machine: RowingMachine) = dao.update(machine)
    suspend fun delete(machine: RowingMachine) = dao.delete(machine)
    suspend fun getById(id: Int): RowingMachine? = dao.getById(id)
}
