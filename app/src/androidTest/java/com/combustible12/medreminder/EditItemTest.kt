package com.combustible12.medreminder

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditItemTest {
    @Test fun combinedEditPreservesSharedIdentityIndependentDosesAndRollback() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "direct-edit.db"
        context.deleteDatabase(filename)
        var db = Room.databaseBuilder(context, MedDatabase::class.java, filename).build()
        try {
            var repo = Repository(db)
            val card = repo.dao.insertCard(NoteCard())
            val group = repo.saveGroup(NoteGroup(cardId = card, name = "餐后"), Reminder(0))
            val first = repo.saveItem(group, null, null, "原药名", "10mg", "1片", "/first", encodePhotos(listOf("/first", "/second")))
            val med = repo.dao.item(first)!!.medicineId
            val second = repo.saveItem(group, null, med, "原药名", "10mg", "2片", null)
            val period = repo.dao.medicinePeriods(med).single()
            repo.saveItem(group, first, med, "新药名", "20mg", "3片", "/second", encodePhotos(listOf("/second")), editSelected = true)
            assertEquals(med, repo.dao.item(first)!!.medicineId)
            assertEquals(med, repo.dao.item(second)!!.medicineId)
            assertEquals("2片", repo.dao.item(second)!!.dose)
            assertEquals("3片", repo.dao.item(first)!!.dose)
            assertEquals(listOf("/second"), repo.dao.medicine(med)!!.photos())
            assertEquals(period, repo.dao.medicinePeriods(med).single())
            repo.saveItem(group, null, null, "已存在", "20mg", "1片", null)
            assertTrue(runCatching { repo.saveItem(group, first, med, "已存在", "20mg", "9片", null, editSelected = true) }.isFailure)
            assertEquals("新药名", repo.dao.medicine(med)!!.name)
            assertEquals("3片", repo.dao.item(first)!!.dose)
            db.close()
            db = Room.databaseBuilder(context, MedDatabase::class.java, filename).build()
            repo = Repository(db)
            assertEquals("新药名", repo.dao.medicine(med)!!.name)
            assertEquals("20mg", repo.dao.medicine(med)!!.specification)
            assertEquals("3片", repo.dao.item(first)!!.dose)
            assertEquals("2片", repo.dao.item(second)!!.dose)
            assertEquals(listOf("/second"), repo.dao.medicine(med)!!.photos())
            assertEquals(2, repo.dao.medicines().first().size)
        } finally { db.close(); context.deleteDatabase(filename) }
    }
}
