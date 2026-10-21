package com.combustible12.medreminder

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DividerTest {
    @Test fun migrateExistingNotesReorderEditReopenAndDelete() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "divider-migration.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("schema-2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO cards (id,name,color,position) VALUES (1,'原卡片',0,0)")
            old.execSQL("INSERT INTO `groups` (id,cardId,name,position) VALUES (1,1,'餐后',0)")
            old.execSQL("INSERT INTO medicines (id,name,specification,image,defaultDose,identity,imagesJson) VALUES (1,'原药品','20mg',NULL,'2片','old','')")
            old.execSQL("INSERT INTO items (id,groupId,medicineId,dose,position) VALUES (1,1,1,'2片',0)")
            old.version = 2
        }
        var db = Room.databaseBuilder(context, MedDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
        try {
            var repo = Repository(db)
            assertEquals("2片", repo.dao.item(1)!!.dose)
            assertEquals("20mg", repo.dao.medicine(1)!!.specification)
            val divider = repo.saveDivider(NoteDivider(groupId = 1))
            val second = repo.saveItem(1, null, null, "第二种药", "", "1片", null)
            assertEquals(2, repo.dao.medicines().first().size)
            assertEquals(1, repo.dao.divider(divider)!!.position)
            assertEquals(2, repo.dao.item(second)!!.position)
            repo.reorderEntries(1, listOf(second, -divider, 1))
            repo.saveDivider(repo.dao.divider(divider)!!.copy(text = "间隔1小时"))
            assertTrue(runCatching { repo.reorderEntries(1, listOf(second, second, 1)) }.isFailure)
            db.close()
            db = Room.databaseBuilder(context, MedDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
            repo = Repository(db)
            val state = repo.notebook.first()
            assertEquals(listOf(second, -divider, 1L), state.groupEntries(1).map { it.key })
            assertEquals("间隔1小时", repo.dao.divider(divider)!!.text)
            assertEquals("2片", repo.dao.item(1)!!.dose)
            repo.dao.deleteDivider(divider)
            assertEquals(2, repo.dao.groupItems(1).size)
            assertEquals(2, repo.dao.medicines().first().size)
            repo.saveDivider(NoteDivider(groupId = 1))
            repo.dao.deleteGroup(1)
            assertTrue(repo.dao.dividers().first().isEmpty())
            assertEquals(2, repo.dao.medicines().first().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
