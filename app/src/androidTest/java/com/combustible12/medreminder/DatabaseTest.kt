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
    @Test fun reuseIndependentDosesHistoryDeletionAndReopen() = runBlocking {
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
            val image = java.io.File(context.filesDir, "acceptance-image").apply { writeBytes(byteArrayOf(1, 2, 3)) }
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
            image.delete()
        } finally { db.close(); context.deleteDatabase(filename) }
    }
}
