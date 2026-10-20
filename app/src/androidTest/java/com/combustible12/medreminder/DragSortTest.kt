package com.combustible12.medreminder

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DragSortTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun dragMedicineAndDividerAutoSavesAndClickStillEdits() {
        val app = compose.activity.application as MedApplication
        val repo = app.repository
        var group = 0L; var first = 0L; var second = 0L; var divider = 0L
        runBlocking {
            app.database.clearAllTables()
            val card = repo.dao.insertCard(NoteCard())
            group = repo.saveGroup(NoteGroup(cardId = card, name = "拖动测试"), Reminder(0))
            first = repo.saveItem(group, null, null, "药品甲", "10mg", "1片", null)
            divider = repo.saveDivider(NoteDivider(groupId = group, text = "间隔1小时"))
            second = repo.saveItem(group, null, null, "药品乙", "20mg", "2片", null)
        }
        compose.waitUntil(10000) { compose.onAllNodesWithText("药品乙").fetchSemanticsNodes().isNotEmpty() }
        fun drag(from: String, to: String) {
            val target = compose.onNodeWithText(to).fetchSemanticsNode().boundsInRoot.center
            val start = compose.onNodeWithText(from).fetchSemanticsNode().boundsInRoot.center
            compose.onNodeWithText(from).performTouchInput {
                down(center); advanceEventTime(700)
                moveTo(center + Offset(0f, target.y - start.y), 700)
                up()
            }
            compose.waitForIdle()
        }
        drag("药品甲", "药品乙")
        compose.waitUntil(10000) { runBlocking { repo.notebook.first().groupEntries(group).map { it.key } == listOf(-divider, second, first) } }
        drag("间隔1小时", "药品甲")
        compose.waitUntil(10000) { runBlocking { repo.notebook.first().groupEntries(group).map { it.key } == listOf(second, first, -divider) } }
        compose.onNodeWithText("药品甲").performClick()
        compose.onNodeWithText("编辑项目").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        runBlocking { assertEquals("1片", repo.dao.item(first)!!.dose); assertEquals("2片", repo.dao.item(second)!!.dose); app.database.clearAllTables() }
    }
}
