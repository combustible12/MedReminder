package com.combustible12.medreminder

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SwipeRevealTest {
    @get:Rule val compose = createComposeRule()
    private fun shortSwipe(left: Boolean) {
        compose.onNodeWithContentDescription("药品条目：测试药品").performTouchInput {
            val delta = width * .12f
            swipe(center, center + Offset(if (left) -delta else delta, 0f), 160)
        }
        compose.waitForIdle()
    }
    @Test fun shortSwipeFullyExposesFourIconTargetsAndRightSwipeCloses() {
        val calls = mutableListOf<String>()
        var starred by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            Box(Modifier.width(340.dp)) {
                SwipeMedicineActions(1, starred, "测试药品", Color.White, {
                    calls.add(it); if (it == "star") starred = !starred
                }) { TextButton(onClick = { calls.add("edit") }, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("测试药品") } }
            }
        } }
        shortSwipe(true)
        listOf("置顶", "置底", "标星", "删除").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        shortSwipe(false)
        compose.onNodeWithContentDescription("置顶").assertDoesNotExist()
        for ((label, action) in listOf("置顶" to "top", "置底" to "bottom", "标星" to "star", "取消标星" to "star", "删除" to "delete")) {
            shortSwipe(true)
            // Send a real touch: semantic clicks could pass even if a partly revealed row covers the icon.
            compose.onNodeWithContentDescription(label).performTouchInput { click() }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(action, calls.last()); assertFalse(calls.contains("edit")) }
        }
    }
}
