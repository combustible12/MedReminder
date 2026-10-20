package com.combustible12.medreminder

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.After
import org.junit.Rule
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun logScreenshot(file: java.io.File) {
        val chunks = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP).chunked(3000)
        chunks.forEachIndexed { index, chunk -> android.util.Log.i("MedReminderQA", "${file.name}:$index/${chunks.size}:$chunk"); Thread.sleep(5) }
        Thread.sleep(150)
    }
    @Before fun reset() = runBlocking { withContext(Dispatchers.IO) { (compose.activity.application as MedApplication).database.clearAllTables() } }
    @After fun cleanup() = reset()
    @Test fun createUnnamedCardAndOptionalTimeGroup() {
        compose.onNodeWithContentDescription("添加卡片").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("＋ 添加大类").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("＋ 添加大类").onFirst().performClick()
        compose.onNodeWithText("大类名称").performTextInput("自定义分组")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("＋ 添加项目").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("自定义分组").assertIsDisplayed()
        compose.onNodeWithText("08:00").assertDoesNotExist()
        compose.onAllNodesWithText("＋ 添加项目").onFirst().performClick()
        compose.onNodeWithText("药品或护理名称").performTextInput("测试护理")
        compose.onNodeWithText("每次用量").performTextInput("1次")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("添加项目").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("测试护理").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测试护理").assertIsDisplayed()
        val file = java.io.File(compose.activity.getExternalFilesDir("qa"), "home-optional-fields.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        logScreenshot(file)
        compose.onNodeWithText("1次").assertIsDisplayed()
        val doseCenter = compose.onNodeWithText("1次").fetchSemanticsNode().boundsInRoot.center.x
        val screenWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        org.junit.Assert.assertTrue("Dose stays in the middle even without a picture", doseCenter / screenWidth in .40f.. .70f)
        compose.onNodeWithText("20mg").assertDoesNotExist()
        compose.onNodeWithText("药品库").performClick()
        compose.onNodeWithText("测试护理").assertIsDisplayed()
        compose.onNodeWithText("搜索药品名称").performTextInput("不存在的药品")
        compose.onNodeWithText("暂无药品记录").assertIsDisplayed()
    }
    @Test fun referenceLayoutAndImageViewer() {
        val app = compose.activity.application as MedApplication
        runBlocking {
            val repo = app.repository
            val image = java.io.File(app.filesDir, "qa-picture.png")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(231, 201, 210))
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            for (i in 0..3) {
                val card = repo.dao.insertCard(NoteCard(color = i, position = i))
                val group = repo.saveGroup(NoteGroup(cardId = card, name = "自定义分组 ${i+1}"), if (i == 0) Reminder(0) else Reminder(0, 8+i, 0, false))
                repo.saveItem(group, null, null, "示例药品 ${i+1}", if (i == 0) "" else "20mg", "${i+1}片", image.absolutePath)
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("示例药品 1").fetchSemanticsNodes().isNotEmpty() }
        val file = java.io.File(compose.activity.getExternalFilesDir("qa"), "home-cards.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        logScreenshot(file)
        compose.onNodeWithContentDescription("查看示例药品 1图片").performClick()
        compose.onNodeWithContentDescription("药品大图").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭大图").performClick()
        compose.onNodeWithText("示例药品 1").assertIsDisplayed()
    }
}
