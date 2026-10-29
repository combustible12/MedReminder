package com.combustible12.medreminder

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePlacementTest {
    @Test fun threeTimesCreatesMorningNoonEveningItemsInMealGroupsWithoutDuplicates() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MedApplication
        val repo = app.repository
        app.database.clearAllTables()
        try {
            val morning = repo.dao.insertCard(NoteCard(name = "早晨", position = 0))
            val noon = repo.dao.insertCard(NoteCard(name = "中午", position = 1))
            val evening = repo.dao.insertCard(NoteCard(name = "晚间", position = 2))
            val source = repo.dao.insertGroup(NoteGroup(cardId = morning, name = "临时分类"))

            repo.addMedicineSchedule(source, null, "测试药", "20mg", "1片", null, "", "3", "餐后", emptyList())
            var notebook = repo.notebook.first { it.items.size == 3 }
            assertEquals(setOf(morning, noon, evening), notebook.groups.filter { it.name == "餐后" }.map { it.cardId }.toSet())
            assertTrue(notebook.items.all { item -> notebook.groups.first { it.id == item.groupId }.name == "餐后" })

            val medicine = notebook.medicines.single { it.name == "测试药" }
            repo.addMedicineSchedule(source, medicine.id, medicine.name, medicine.specification, "2片", null, "", "3", "餐后", emptyList())
            notebook = repo.notebook.first { it.items.size == 3 && it.items.all { item -> item.dose == "2片" } }
            assertEquals(3, notebook.items.size)
            assertEquals("20mg · 1天3次 · 每次2片 · 餐后", notebook.medicines.single { it.id == medicine.id }.usageLine())
        } finally { app.database.clearAllTables() }
    }

    @Test fun oneOrTwoTimesUsesOnlyTheCardsChosenByUser() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MedApplication
        val repo = app.repository
        app.database.clearAllTables()
        try {
            val morning = repo.dao.insertCard(NoteCard(name = "上午", position = 0))
            repo.dao.insertCard(NoteCard(name = "下午", position = 1))
            val evening = repo.dao.insertCard(NoteCard(name = "晚上", position = 2))
            val source = repo.dao.insertGroup(NoteGroup(cardId = morning, name = "其他"))

            repo.addMedicineSchedule(source, null, "两次药", "", "1粒", null, "", "2", "餐前", listOf(morning, evening))
            val notebook = repo.notebook.first { it.items.size == 2 }
            assertEquals(setOf(morning, evening), notebook.items.map { item -> notebook.groups.first { it.id == item.groupId }.cardId }.toSet())
            assertTrue(notebook.items.all { item -> notebook.groups.first { it.id == item.groupId }.name == "餐前" })
            assertTrue(runCatching { repo.addMedicineSchedule(source, null, "选择不足", "", "1片", null, "", "2", "餐后", listOf(morning)) }.isFailure)
        } finally { app.database.clearAllTables() }
    }

    @Test fun libraryUsePlacesOneDoseInChosenExistingGroupWithoutCreatingDefaultGroup() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MedApplication
        val repo = app.repository
        app.database.clearAllTables()
        try {
            val card = repo.dao.insertCard(NoteCard(position = 0))
            val before = repo.dao.insertGroup(NoteGroup(cardId = card, name = "餐前护肠胃", position = 0))
            val after = repo.dao.insertGroup(NoteGroup(cardId = card, name = "餐后", position = 1))
            val medicine = repo.dao.insertMedicine(Medicine(name = "B族维生素片", defaultDose = "1片", dailyFrequency = "1"))

            repo.useLibraryMedicine(medicine, listOf(after))

            val notebook = repo.notebook.first { it.items.size == 1 }
            assertEquals(after, notebook.items.single().groupId)
            assertTrue(notebook.groups.none { it.name == "药品" })
            assertTrue(notebook.items.none { it.groupId == before })
        } finally { app.database.clearAllTables() }
    }

    @Test fun libraryUseRequiresExactExistingGroupsAndDoesNotFallbackToNewGroup() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MedApplication
        val repo = app.repository
        app.database.clearAllTables()
        try {
            val card = repo.dao.insertCard(NoteCard(position = 0))
            val before = repo.dao.insertGroup(NoteGroup(cardId = card, name = "餐前", position = 0))
            val medicine = repo.dao.insertMedicine(Medicine(name = "两次药", defaultDose = "1片", dailyFrequency = "2"))

            assertTrue(runCatching { repo.useLibraryMedicine(medicine, listOf(before)) }.isFailure)
            val notebook = repo.notebook.first()
            assertTrue(notebook.items.isEmpty())
            assertTrue(notebook.groups.none { it.name == "药品" })
            assertTrue(notebook.periods.none { it.medicineId == medicine && it.active })
        } finally { app.database.clearAllTables() }
    }

    @Test fun libraryThreeDoseWithoutMealTimingDoesNotCreateDefaultGroups() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MedApplication
        val repo = app.repository
        app.database.clearAllTables()
        try {
            repo.dao.insertCard(NoteCard(name = "早晨", position = 0))
            repo.dao.insertCard(NoteCard(name = "中午", position = 1))
            repo.dao.insertCard(NoteCard(name = "晚间", position = 2))
            val medicine = repo.dao.insertMedicine(Medicine(name = "三次药", defaultDose = "1片", dailyFrequency = "3"))

            assertTrue(runCatching { repo.useLibraryMedicine(medicine, emptyList()) }.isFailure)
            val notebook = repo.notebook.first()
            assertTrue(notebook.items.isEmpty())
            assertTrue(notebook.groups.none { it.name == "药品" })
        } finally { app.database.clearAllTables() }
    }
}
