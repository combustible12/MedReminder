package com.combustible12.medreminder

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
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
        compose.waitUntil(5000) { compose.onAllNodesWithText("测试护理").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测试护理").assertIsDisplayed()
        compose.onNodeWithText("1次").assertIsDisplayed()
        compose.onNodeWithText("20mg").assertDoesNotExist()
        compose.onNodeWithText("药品库").performClick()
        compose.onNodeWithText("测试护理").assertIsDisplayed()
        compose.onNodeWithText("搜索药品名称").performTextInput("不存在的药品")
        compose.onNodeWithText("暂无药品记录").assertIsDisplayed()
    }
}
