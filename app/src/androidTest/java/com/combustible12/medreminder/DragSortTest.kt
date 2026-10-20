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
    @Test fun dialogAllowsRepeatedDragsAndOnlyPersistsOnSave() {
        val app = compose.activity.application as MedApplication
        val repo = app.repository
        var group = 0L; var a = 0L; var b = 0L; var c = 0L
        runBlocking {
            app.database.clearAllTables()
            val card = repo.dao.insertCard(NoteCard())
            group = repo.saveGroup(NoteGroup(cardId = card, name = "连续排序"), Reminder(0))
            a = repo.saveItem(group, null, null, "甲", "", "1片", null)
            b = repo.saveItem(group, null, null, "乙", "", "1片", null)
            c = repo.saveItem(group, null, null, "丙", "", "1片", null)
        }
        compose.waitUntil(10000) { compose.onAllNodesWithText("排序").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("排序").performClick()
        fun drag(from: String, to: String) {
            val start = compose.onAllNodesWithText(from).onLast().fetchSemanticsNode().boundsInRoot.center
            val end = compose.onAllNodesWithText(to).onLast().fetchSemanticsNode().boundsInRoot.center
            compose.onAllNodesWithText(from).onLast().performTouchInput {
                down(center); advanceEventTime(700)
                moveTo(center + Offset(0f, end.y - start.y), 700); up()
            }
            compose.waitForIdle()
            compose.onNodeWithText("药品与分隔条排序").assertIsDisplayed()
            runBlocking { assertEquals(listOf(a, b, c), repo.notebook.first().groupEntries(group).map { it.key }) }
        }
        drag("甲", "丙")
        drag("乙", "甲")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { runBlocking { repo.notebook.first().groupEntries(group).map { it.key } == listOf(c, a, b) } }
        compose.onNodeWithText("排序").performClick()
        compose.onNodeWithText("取消").performClick()
        runBlocking { assertEquals(listOf(c, a, b), repo.notebook.first().groupEntries(group).map { it.key }); app.database.clearAllTables() }
    }
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
