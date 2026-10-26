package com.combustible12.medreminder

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GlobalStarTest {
    @Test fun starsFollowMedicineAcrossGroupsReopenAndReadd() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "global-star-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, MedDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
        var db = open()
        try {
            var repo = Repository(db)
            val card = repo.dao.insertCard(NoteCard())
            val g1 = repo.dao.insertGroup(NoteGroup(cardId = card, name = "A"))
            val g2 = repo.dao.insertGroup(NoteGroup(cardId = card, name = "B"))
            val a = repo.saveItem(g1, null, null, "甲药", "10mg", "1片", null)
            val m = repo.dao.item(a)!!.medicineId
            val b = repo.saveItem(g2, null, m, "甲药", "10mg", "2片", null)
            val different = repo.saveItem(g2, null, null, "甲药", "20mg", "1片", null)
            repo.toggleItemStar(a)
            assertTrue(repo.dao.medicine(m)!!.starred)
            assertTrue(repo.dao.item(b)!!.starred)
            assertFalse(repo.dao.item(different)!!.starred)
            db.close(); db = open(); repo = Repository(db)
            assertTrue(repo.dao.medicine(m)!!.starred)
            repo.dao.deleteItem(a); repo.dao.deleteItem(b)
            val c = repo.saveItem(g1, null, m, "甲药", "10mg", "3片", null)
            assertTrue(repo.dao.item(c)!!.starred)
            val d = repo.saveItem(g2, null, m, "甲药", "10mg", "4片", null)
            repo.toggleItemStar(d)
            assertFalse(repo.dao.medicine(m)!!.starred)
            assertFalse(repo.dao.item(c)!!.starred)
            assertEquals("3片", repo.dao.item(c)!!.dose)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun upgradeKeepsAnyExistingStarAndSynchronizesCopies() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "global-star-migration.db"
        context.deleteDatabase(name)
        val schema = org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("legacy-v4.json").bufferedReader().readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO cards VALUES(1,'',0,0)")
            old.execSQL("INSERT INTO groups VALUES(1,1,'A',0)")
            old.execSQL("INSERT INTO medicines VALUES(1,'甲药','10mg',NULL,'','identity','')")
            old.execSQL("INSERT INTO items VALUES(1,1,1,'1片',0,1)")
            old.execSQL("INSERT INTO items VALUES(2,1,1,'2片',1,0)")
            old.version = 4
        }
        val db = Room.databaseBuilder(context, MedDatabase::class.java, name).addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
        try {
            assertTrue(db.dao().medicine(1)!!.starred)
            assertTrue(db.dao().item(2)!!.starred)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
