package com.combustible12.medreminder

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SwipeActionsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun swipeStarMoveAndDeleteStayInsideGroupAndRetainMedicineHistory() {
        val app = compose.activity.application as MedApplication
        val repo = app.repository
        var group = 0L; var other = 0L; var a = 0L; var b = 0L; var divider = 0L
        runBlocking {
            app.database.clearAllTables()
            val card = repo.dao.insertCard(NoteCard(name = "测试"))
            group = repo.saveGroup(NoteGroup(cardId = card, name = "餐后"), Reminder(0))
            other = repo.saveGroup(NoteGroup(cardId = card, name = "另一类"), Reminder(0))
            a = repo.saveItem(group, null, null, "药品甲", "10mg", "1片", null)
            divider = repo.saveDivider(NoteDivider(groupId = group))
            b = repo.saveItem(group, null, null, "药品乙", "20mg", "2片", null)
            val med = repo.dao.item(a)!!.medicineId
            repo.saveItem(other, null, med, "药品甲", "10mg", "3片", null)
        }
        fun swipe(name: String) {
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("药品条目：$name").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription("药品条目：$name").onFirst().performTouchInput { swipeLeft() }
        }
        swipe("药品乙")
        compose.onNodeWithContentDescription("标星").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { runBlocking { repo.dao.item(b)!!.starred } }
        compose.onNodeWithText("药品乙 ⭐").assertIsDisplayed()
        runBlocking { assertEquals(listOf(a, -divider, b), repo.notebook.first().groupEntries(group).map { it.key }) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("药品乙 ⭐").assertIsDisplayed()
        swipe("药品乙")
        compose.onNodeWithContentDescription("置顶").performClick()
        compose.waitUntil(10000) { runBlocking { repo.notebook.first().groupEntries(group).map { it.key } == listOf(b, a, -divider) } }
        swipe("药品乙")
        compose.onNodeWithContentDescription("置底").performClick()
        compose.waitUntil(10000) { runBlocking { repo.notebook.first().groupEntries(group).map { it.key } == listOf(a, -divider, b) } }
        swipe("药品乙")
        compose.onNodeWithContentDescription("取消标星").performClick()
        compose.waitUntil(10000) { runBlocking { !repo.dao.item(b)!!.starred } }
        swipe("药品乙")
        compose.onNodeWithContentDescription("删除").performClick()
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.waitUntil(10000) { runBlocking { repo.dao.item(b) == null } }
        runBlocking {
            assertEquals(listOf(a, -divider), repo.notebook.first().groupEntries(group).map { it.key })
            assertEquals(1, repo.dao.groupItems(other).size)
            assertEquals(2, repo.dao.medicines().first().size)
            assertEquals(2, repo.dao.periods().first().size)
            app.database.clearAllTables()
        }
    }
}
