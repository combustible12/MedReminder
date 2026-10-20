package com.combustible12.medreminder

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PhotosTest {
    @Test fun oldDatabaseMigratesAndKeepsCoverThenStoresMultiplePhotos() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "photo-migration.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = org.json.JSONObject(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("schema-1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO medicines (id,name,specification,image,defaultDose,identity) VALUES (1,'旧药品','20mg','/old-cover','2片','old')")
            old.version = 1
        }
        var db = Room.databaseBuilder(context, MedDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            var med = db.dao().medicine(1)!!
            assertEquals(listOf("/old-cover"), med.photos())
            assertEquals("20mg", med.specification)
            val photos = listOf("/old-cover", "/second", "/third")
            Repository(db).updateMedicine(med.copy(imagesJson = encodePhotos(photos)))
            db.close()
            db = Room.databaseBuilder(context, MedDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
            med = db.dao().medicine(1)!!
            assertEquals(photos, med.photos())
            Repository(db).updateMedicine(med.copy(image = "/second", imagesJson = encodePhotos(photos.drop(1))))
            assertEquals(photos.drop(1), db.dao().medicine(1)!!.photos())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun imageCleanupRetainsEveryReferencedPhoto() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MedDatabase::class.java).build()
        val directory = java.io.File(context.filesDir, "medicine-images").apply { mkdirs() }
        val first = java.io.File(directory, "first-test").apply { writeText("first") }
        val second = java.io.File(directory, "second-test").apply { writeText("second") }
        try {
            val id = db.dao().insertMedicine(Medicine(name = "多图", image = first.path, imagesJson = encodePhotos(listOf(first.path, second.path))))
            ImageStore(context).removeIfUnused(second.path, db)
            assertTrue(second.exists())
            db.dao().deleteMedicine(id)
            ImageStore(context).removeIfUnused(first.path, db)
            ImageStore(context).removeIfUnused(second.path, db)
            assertFalse(first.exists()); assertFalse(second.exists())
        } finally { db.close(); first.delete(); second.delete() }
    }
}
