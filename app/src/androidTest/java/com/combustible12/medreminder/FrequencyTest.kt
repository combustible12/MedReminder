package com.combustible12.medreminder

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FrequencyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun editSyncReopenLibraryLayoutAndClearOptionalFrequency() {
        val app = compose.activity.application as MedApplication
        val repo = app.repository
        var medId = 0L
        runBlocking {
            app.database.clearAllTables()
            val c = repo.dao.insertCard(NoteCard())
            val g = repo.dao.insertGroup(NoteGroup(cardId = c, name = "餐后"))
            val a = repo.saveItem(g, null, null, "测试药品长名称", "10mg", "1片", null)
            medId = repo.dao.item(a)!!.medicineId
            repo.saveItem(g, null, null, "未填次数药品", "", "2片", null)
            repo.toggleItemStar(a)
        }
        compose.waitUntil(10000) { compose.onAllNodesWithText("测试药品长名称 ⭐").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测试药品长名称 ⭐").performClick()
        compose.onNodeWithText("每天次数（可不填）").performTextInput("2")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("1天2次").fetchSemanticsNodes().isNotEmpty() }
        val dose = compose.onNodeWithText("1片", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val freq = compose.onNodeWithText("1天2次", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(freq.top >= dose.bottom)
        assertTrue(kotlin.math.abs(freq.center.x - dose.center.x) < 2f)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("1天2次").assertIsDisplayed()
        compose.onNodeWithText("药品库").performClick()
        compose.onNodeWithText("10mg · 1天2次 · 每次1片").assertIsDisplayed()
        val file = java.io.File(compose.activity.getExternalFilesDir("qa"), "library-frequency.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val chunks = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP).chunked(3000)
        chunks.forEachIndexed { index, chunk -> android.util.Log.i("MedReminderQA", "${file.name}:$index/${chunks.size}:$chunk"); Thread.sleep(5) }
        compose.onNodeWithText("测试药品长名称 ⭐").performClick()
        compose.onNodeWithText("编辑药品资料").performClick()
        compose.onNodeWithText("每天次数（可不填）").performTextReplacement("3")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { runBlocking { repo.dao.medicine(medId)!!.dailyFrequency == "3" } }
        compose.onNodeWithText("1天3次", substring = true).assertIsDisplayed()
        compose.onNodeWithText("编辑药品资料").performClick()
        compose.onNodeWithText("每天次数（可不填）").performTextClearance()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { runBlocking { repo.dao.medicine(medId)!!.dailyFrequency.isEmpty() } }
        compose.onNodeWithText("1天3次", substring = true).assertDoesNotExist()
        runBlocking {
            assertTrue(repo.dao.medicine(medId)!!.starred)
            app.database.clearAllTables()
        }
    }
}
