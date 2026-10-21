package com.combustible12.medreminder

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DragListMembershipTest {
    @get:Rule val compose = createComposeRule()
    @Test fun removalInsertionAndExternalOrderingNeverRenderStaleKeys() {
        val rows = mutableStateOf(listOf(1L, 2L, 3L))
        compose.setContent { MaterialTheme {
            DragEntryList(rows.value, {}) { _, id ->
                Text("条目${rows.value.first { it == id }}", Modifier.fillMaxWidth().height(60.dp))
            }
        } }
        for (id in listOf(2L, 1L, 3L)) {
            compose.runOnIdle { rows.value = rows.value - id }
            compose.onNodeWithText("条目$id").assertDoesNotExist()
        }
        compose.runOnIdle { rows.value = listOf(7L, 8L) }
        compose.onNodeWithText("条目7").assertIsDisplayed()
        compose.runOnIdle { rows.value = listOf(8L, 7L) }
        val first = compose.onNodeWithText("条目8").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithText("条目7").fetchSemanticsNode().boundsInRoot
        assertTrue(first.top < last.top)
    }
    @Test fun deletionDuringActiveDragNeverSavesDeletedMembership() {
        val rows = mutableStateOf(listOf(1L, 2L, 3L))
        val saves = mutableListOf<List<Long>>()
        compose.setContent { MaterialTheme {
            DragEntryList(rows.value, { saves.add(it) }) { _, id ->
                Text("条目${rows.value.first { it == id }}", Modifier.fillMaxWidth().height(60.dp))
            }
        } }
        compose.onNodeWithText("条目1").performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(0f, 80f))
        }
        compose.runOnIdle { rows.value = listOf(2L, 3L) }
        compose.onNodeWithText("条目1").assertDoesNotExist()
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle { assertTrue(saves.all { 1L !in it }) }
        compose.onNodeWithText("条目2").assertIsDisplayed()
        compose.onNodeWithText("条目3").assertIsDisplayed()
    }
}
