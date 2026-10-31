package com.combustible12.medreminder

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    @Test fun addingEarlierHistoryDoesNotChangeCurrentNoteUse() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "earlier-period-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, MedDatabase::class.java, filename).build()
        try {
            val repo = Repository(db) { java.time.LocalDate.parse("2026-10-26") }
            val card = repo.dao.insertCard(NoteCard(name = "早"))
            val group = repo.saveGroup(NoteGroup(cardId = card, name = "餐后"), Reminder(0))
            val item = repo.saveItem(group, null, null, "测试药", "", "1片", null)
            val medicineId = requireNotNull(repo.dao.item(item)).medicineId

            repo.savePeriod(UsePeriod(medicineId = medicineId, start = "2026-09-01", end = "2026-09-10", active = false))

            val periods = repo.dao.medicinePeriods(medicineId)
            assertEquals(2, periods.size)
            assertEquals(1, periods.count { it.active })
            assertEquals("2026-10-26", periods.single { it.active }.start)
            assertEquals("2026-09-10", periods.single { !it.active }.end)
            Unit
        } finally {
            db.close()
            context.deleteDatabase(filename)
        }
    }

    @Test fun noteChangesAutomaticallyKeepSeparateUsePeriods() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "periods-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, MedDatabase::class.java, filename).build()
        var day = java.time.LocalDate.parse("2026-10-26")
        try {
            val repo = Repository(db) { day }
            val morning = repo.dao.insertCard(NoteCard(name = "早"))
            val evening = repo.dao.insertCard(NoteCard(name = "晚"))
            val morningGroup = repo.saveGroup(NoteGroup(cardId = morning, name = "餐后"), Reminder(0))
            val eveningGroup = repo.saveGroup(NoteGroup(cardId = evening, name = "餐后"), Reminder(0))

            val firstItem = repo.saveItem(morningGroup, null, null, "测试药", "", "1片", null)
            val medicineId = requireNotNull(repo.dao.item(firstItem)).medicineId
            assertEquals(listOf("2026-10-26"), repo.dao.medicinePeriods(medicineId).map { it.start })

            val secondItem = repo.saveItem(eveningGroup, null, medicineId, "测试药", "", "1片", null)
            assertEquals(1, repo.dao.medicinePeriods(medicineId).size)
            day = java.time.LocalDate.parse("2026-10-30")
            val affectedGroups = repo.removeNoteItem(firstItem)
            assertEquals(setOf(morningGroup, eveningGroup), affectedGroups.toSet())
            assertNull(repo.dao.item(firstItem))
            assertNull(repo.dao.item(secondItem))
            val firstPeriod = repo.dao.medicinePeriods(medicineId).single()
            assertEquals("2026-10-26", firstPeriod.start)
            assertEquals("2026-10-30", firstPeriod.end)
            assertFalse(firstPeriod.active)

            day = java.time.LocalDate.parse("2026-11-02")
            repo.saveItem(morningGroup, null, medicineId, "测试药", "", "1片", null)
            val periods = repo.dao.medicinePeriods(medicineId)
            assertEquals(2, periods.size)
            assertEquals("2026-11-02", periods.first().start)
            assertTrue(periods.first().active)
            assertEquals(firstPeriod, periods.last())

            day = java.time.LocalDate.parse("2026-11-05")
            repo.removeNoteCard(morning)
            assertEquals("2026-11-05", repo.dao.medicinePeriods(medicineId).first().end)
            Unit
        } finally {
            db.close()
            context.deleteDatabase(filename)
        }
    }

    @Test fun reuseIndependentDosesHistoryDeletionAndReopen() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "acceptance-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, MedDatabase::class.java, filename).build()
        try {
            var repo = Repository(db)
            repo.saveCard(NoteCard())
            repo.saveCard(NoteCard(name = "自定义", color = 2))
            val cards = repo.dao.cards().first()
            assertEquals("", cards.first().name)
            repo.reorderCards(cards.reversed().map { it.id })
            assertEquals(cards.last().id, repo.dao.cards().first().first().id)
            val g1 = repo.saveGroup(NoteGroup(cardId = cards[0].id, name = "A"), Reminder(0))
            val g2 = repo.saveGroup(NoteGroup(cardId = cards[1].id, name = "B"), Reminder(0, 9, 30, true))
            val g3 = repo.saveGroup(NoteGroup(cardId = cards[0].id, name = "C"), Reminder(0))
            repo.reorderGroups(listOf(g3, g1))
            val source = java.io.File(context.cacheDir, "acceptance-source.png")
            val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val image = java.io.File(ImageStore(context).import(android.net.Uri.fromFile(source)))
            source.delete()
            val i1 = repo.saveItem(g1, null, null, "药品 A", "", "1片", image.absolutePath)
            val med = repo.dao.medicines().first().single()
            val i2 = repo.saveItem(g2, null, med.id, med.name, "", "2片", med.image)
            val i3 = repo.saveItem(g1, null, null, "药品 A", "", "3片", null)
            assertEquals(1, repo.dao.medicines().first().size)
            repo.reorderItems(listOf(i3, i1))
            assertEquals(i3, repo.dao.groupItems(g1).first().id)
            repo.saveItem(g1, i1, med.id, med.name, "", "4片", med.image)
            assertEquals("2片", repo.dao.item(i2)!!.dose)
            val initial = repo.dao.medicinePeriods(med.id).single()
            repo.savePeriod(initial.copy(start = "2026-09-01", end = "2026-09-15", active = false))
            var reopeningRejected = false
            try { repo.savePeriod(initial.copy(active = true)) } catch (_: IllegalArgumentException) { reopeningRejected = true }
            assertTrue("Reusing medicine must not erase a stopped period", reopeningRejected)
            assertEquals("2026-09-15", repo.dao.medicinePeriods(med.id).single().end)
            repo.savePeriod(UsePeriod(medicineId = med.id, start = "2026-10-10"))
            assertEquals(2, repo.dao.medicinePeriods(med.id).size)
            assertEquals("2026-09-15", repo.dao.medicinePeriods(med.id).last().end)
            db.close()
            db = Room.databaseBuilder(context, MedDatabase::class.java, filename).build()
            repo = Repository(db)
            assertEquals(2, repo.dao.cards().first().size)
            assertEquals(3, repo.dao.groups().first().size)
            assertEquals(3, repo.dao.items().first().size)
            assertEquals("4片", repo.dao.item(i1)!!.dose)
            assertTrue(java.io.File(repo.dao.medicine(med.id)!!.image!!).isFile)
            assertEquals("09:30", repo.dao.reminder(g2)!!.time())
            repo.dao.deleteGroup(g1)
            assertNull(repo.dao.reminder(g1)); assertNotNull(repo.dao.medicine(med.id))
            assertTrue(image.isFile)
            repo.dao.deleteCard(cards[1].id)
            assertNull(repo.dao.reminder(g2)); assertEquals(1, repo.dao.medicines().first().size)
            assertEquals(2, repo.dao.medicinePeriods(med.id).size)
            repo.dao.deleteMedicine(med.id)
            assertTrue(repo.dao.items().first().isEmpty()); assertTrue(repo.dao.periods().first().isEmpty())
            ImageStore(context).removeIfUnused(image.absolutePath, db)
            assertFalse(image.exists())
            Unit
        } finally { db.close(); context.deleteDatabase(filename) }
    }
    @Test fun repositoryOwnsArchiveDividerAndPeriodDeletion() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "repository-delete-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, MedDatabase::class.java, filename).build()
        try {
            val repo = Repository(db)
            val card = repo.dao.insertCard(NoteCard(name = "早"))
            val group = repo.saveGroup(NoteGroup(cardId = card, name = "餐前"), Reminder(0))
            val divider = repo.saveDivider(NoteDivider(groupId = group, text = "30分钟"))
            val item = repo.saveItem(group, null, null, "删除测试药", "10mg", "1片", "/saved/image")
            val medicineId = requireNotNull(repo.dao.item(item)).medicineId
            val oldPeriod = repo.dao.insertPeriod(UsePeriod(medicineId = medicineId, start = "2026-01-01", end = "2026-01-02", active = false))

            repo.deleteDivider(divider)
            assertNull(repo.dao.divider(divider))
            repo.deletePeriod(oldPeriod)
            assertTrue(repo.dao.medicinePeriods(medicineId).none { it.id == oldPeriod })

            val deleted = repo.deleteMedicineArchive(medicineId)
            assertEquals(listOf("/saved/image"), deleted.images)
            assertEquals(listOf(group), deleted.affectedGroupIds)
            assertNull(repo.dao.medicine(medicineId))
            assertTrue(repo.dao.itemsForMedicine(medicineId).isEmpty())
            Unit
        } finally {
            db.close()
            context.deleteDatabase(filename)
        }
    }

}
