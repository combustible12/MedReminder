package com.combustible12.medreminder

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.After
import org.junit.Rule
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
    @Test fun updateEntryAndInstalledPackageGuard() {
        compose.onNodeWithText("更新").assertIsDisplayed().assertHasClickAction()
        val current = java.io.File(compose.activity.applicationInfo.sourceDir)
        org.junit.Assert.assertFalse("Same version must not install again", AppUpdater(compose.activity).validate(current))
        val invalid = java.io.File(compose.activity.cacheDir, "invalid.apk").apply { writeText("not an apk") }
        org.junit.Assert.assertTrue(runCatching { AppUpdater(compose.activity).validate(invalid) }.isFailure)
        invalid.delete()
    }
    @Test fun medicationDurationIsIndependentSearchableOneMedicinePerCard() {
        val repo = (compose.activity.application as MedApplication).repository
        runBlocking {
            val card = repo.dao.insertCard(NoteCard(name = "早"))
            val group = repo.saveGroup(NoteGroup(cardId = card, name = "餐后"), Reminder(0))
            repo.saveItem(group, null, null, "甲药", "", "1片", null)
            repo.saveItem(group, null, null, "乙药", "", "1粒", null)
        }
        compose.onNodeWithText("用药时长").performClick()
        compose.onNodeWithText("甲药").assertIsDisplayed()
        compose.onNodeWithText("乙药").assertIsDisplayed()
        compose.onNodeWithText("开始日期").assertDoesNotExist()
        compose.onNodeWithText("结束日期").assertDoesNotExist()
        compose.onNodeWithText("时长").assertDoesNotExist()
        compose.onNodeWithText("搜索药品名称").performTextInput("乙")
        compose.onNodeWithText("甲药").assertDoesNotExist()
        compose.onNodeWithText("乙药").assertIsDisplayed()
        compose.onAllNodesWithText("＋ 添加使用记录").assertCountEquals(1)
        compose.onNodeWithText("＋ 添加使用记录").performClick()
        compose.onNodeWithContentDescription("选择开始日期").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("选择结束日期").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("YYYY-MM-DD").assertDoesNotExist()
    }
    @Test fun addButtonFollowsCurrentModuleAndLibraryMedicineDoesNotEnterNotes() {
        compose.onNodeWithContentDescription("添加卡片").assertIsDisplayed()
        val noteUpdateX = compose.onNodeWithText("更新").fetchSemanticsNode().boundsInRoot.center.x
        val noteUpdateY = compose.onNodeWithText("更新").fetchSemanticsNode().boundsInRoot.center.y
        val noteTitleY = compose.onNodeWithText("用药便签").fetchSemanticsNode().boundsInRoot.center.y
        // Updates must be centered on the entire screen, not within the title's remaining space.
        val screenCenterX = compose.onRoot().fetchSemanticsNode().boundsInRoot.center.x
        org.junit.Assert.assertEquals(screenCenterX, noteUpdateX, 1f)
        org.junit.Assert.assertEquals(noteTitleY, noteUpdateY, 1f)
        compose.onNodeWithText("药品库").performClick()
        compose.onNodeWithText("用药便签").assertDoesNotExist()
        compose.onNodeWithText("更新").assertIsDisplayed().assertHasClickAction()
        org.junit.Assert.assertEquals(screenCenterX, compose.onNodeWithText("更新").fetchSemanticsNode().boundsInRoot.center.x, 1f)
        compose.onNodeWithContentDescription("添加药品").performClick()
        compose.onNodeWithText("药品名称").performTextInput("仅药品库药品")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("仅药品库药品").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("仅药品库药品").performClick()
        compose.onNodeWithText("编辑药品资料").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("用药时长").performClick()
        compose.onNodeWithText("更新").assertIsDisplayed().assertHasClickAction()
        org.junit.Assert.assertEquals(screenCenterX, compose.onNodeWithText("更新").fetchSemanticsNode().boundsInRoot.center.x, 1f)
        compose.onNodeWithText("仅药品库药品").assertIsDisplayed()
        compose.onNodeWithContentDescription("添加药品").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("药品名称").performTextInput("时长页药品")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("时长页药品").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("药品库").performClick()
        compose.onNodeWithText("仅药品库药品").assertIsDisplayed()
        compose.onNodeWithText("时长页药品").assertIsDisplayed()
        compose.onNodeWithText("便签").performClick()
        compose.onNodeWithText("仅药品库药品").assertDoesNotExist()
        compose.onNodeWithText("时长页药品").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加卡片").assertIsDisplayed()
    }
    @Test fun activeMedicinesSortFirstAndLibraryUsesOnlyGreenStatusDot() {
        val repo = (compose.activity.application as MedApplication).repository
        runBlocking {
            repo.addMedicine(Medicine(name = "A停用药"))
            val card = repo.dao.insertCard(NoteCard(name = "服药"))
            val group = repo.saveGroup(NoteGroup(cardId = card, name = "药品"), Reminder(0))
            repo.saveItem(group, null, null, "Z当前药", "20mg", "1片", null)
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Z当前药").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("药品库").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("A停用药").fetchSemanticsNodes().isNotEmpty() }
        org.junit.Assert.assertTrue(compose.onNodeWithText("Z当前药").fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText("A停用药").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithContentDescription("当前使用").assertIsDisplayed()
        // Current use is shown by a muted strip at the leading edge of the card, never by a dot.
        val statusBounds = compose.onNodeWithContentDescription("当前使用", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val nameBounds = compose.onNodeWithText("Z当前药", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Status strip is before the medicine name", statusBounds.right <= nameBounds.left)
        org.junit.Assert.assertTrue("Status strip spans the card height", statusBounds.height > nameBounds.height * 2f)
        val cardBounds = compose.onNodeWithContentDescription("药品条目：Z当前药").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals("Status strip starts at the card edge", cardBounds.left, statusBounds.left, 1f)
        // An 18dp outer corner must expose white at 4dp in both axes.
        // A second 12dp gray swipe background would paint this pixel gray again.
        val cornerInsetPx = (4f * compose.activity.resources.displayMetrics.density).toInt()
        val snapshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val topLeftX = (cardBounds.left + cornerInsetPx).toInt()
        val topLeftY = (cardBounds.top + cornerInsetPx).toInt()
        org.junit.Assert.assertEquals("No gray swipe background outside the 18dp clipped card", android.graphics.Color.WHITE, snapshot.getPixel(topLeftX, topLeftY))
        compose.onAllNodesWithText("当前使用").assertCountEquals(1)
        compose.onAllNodesWithText("已停用").assertCountEquals(1)
        compose.onNodeWithText("用药时长").performClick()
        org.junit.Assert.assertTrue(compose.onNodeWithText("Z当前药").fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText("A停用药").fetchSemanticsNode().boundsInRoot.top)
    }
    @Test fun librarySwipeStopUsesNoteSwipeAndPreservesMedicineHistory() {
        val repo = (compose.activity.application as MedApplication).repository
        val medicineId = runBlocking {
            val morning = repo.dao.insertCard(NoteCard(name = "早"))
            val groupMorning = repo.saveGroup(NoteGroup(cardId = morning, name = "餐后"), Reminder(0))
            val evening = repo.dao.insertCard(NoteCard(name = "晚"))
            val groupEvening = repo.saveGroup(NoteGroup(cardId = evening, name = "餐后"), Reminder(0))
            repo.saveItem(groupMorning, null, null, "联动停用测试药", "10mg", "1片", null)
            repo.saveItem(groupEvening, null, null, "联动停用测试药", "10mg", "1片", null)
            requireNotNull(repo.dao.findMedicine(identityOf("联动停用测试药", "10mg"))).id
        }
        compose.onNodeWithText("药品库").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("药品条目：联动停用测试药").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("药品条目：联动停用测试药").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("停用").assertIsDisplayed().performClick()
        compose.onNodeWithText("停用药品？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        runBlocking { org.junit.Assert.assertTrue(repo.dao.medicinePeriods(medicineId).any { it.active }) }
        compose.onNodeWithContentDescription("药品条目：联动停用测试药").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("停用").assertIsDisplayed().performClick()
        compose.onNodeWithText("停用").assertIsDisplayed().performClick()
        compose.waitUntil(5000) {
            runBlocking { repo.dao.medicinePeriods(medicineId).none { it.active } }
        }
        runBlocking {
            org.junit.Assert.assertNotNull(repo.dao.medicine(medicineId))
            org.junit.Assert.assertTrue(repo.dao.itemsForMedicine(medicineId).isEmpty())
            val history = repo.dao.medicinePeriods(medicineId)
            org.junit.Assert.assertTrue(history.isNotEmpty())
            org.junit.Assert.assertTrue(history.all { !it.active && it.end == java.time.LocalDate.now().toString() })
        }
        compose.onNodeWithText("联动停用测试药").assertIsDisplayed()
        compose.onNodeWithContentDescription("当前使用").assertDoesNotExist()
        compose.onNodeWithText("便签").performClick()
        compose.onNodeWithText("联动停用测试药").assertDoesNotExist()
    }
    @Test fun libraryInactiveSwipeUseChoosesTwoExistingGroupsAndStartsNewHistory() {
        val repo = (compose.activity.application as MedApplication).repository
        val medicineId = runBlocking {
            val card = repo.dao.insertCard(NoteCard())
            repo.dao.insertGroup(NoteGroup(cardId = card, name = "餐前"))
            repo.dao.insertGroup(NoteGroup(cardId = card, name = "餐后"))
            val id = repo.addMedicine(Medicine(name = "库内两次药", specification = "20mg", defaultDose = "1片", dailyFrequency = "2", mealTiming = "餐后"))
            repo.dao.insertPeriod(UsePeriod(medicineId = id, start = "2026-01-01", end = "2026-01-10", active = false))
            id
        }
        compose.onNodeWithText("药品库").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("药品条目：库内两次药").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("药品条目：库内两次药").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("使用").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("停用").assertDoesNotExist()
        compose.onNodeWithText("选择服用大类").assertIsDisplayed()
        compose.onNodeWithText("餐前").performClick()
        compose.onNodeWithText("餐后").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { runBlocking { repo.dao.medicinePeriods(medicineId).any { it.active } } }
        runBlocking {
            val periods = repo.dao.medicinePeriods(medicineId)
            org.junit.Assert.assertEquals(2, periods.size)
            org.junit.Assert.assertEquals("2026-01-10", periods.first { !it.active }.end)
            val groups = repo.dao.groups().first()
            val assigned = repo.dao.itemsForMedicine(medicineId).mapNotNull { entry -> groups.firstOrNull { it.id == entry.groupId } }
            org.junit.Assert.assertEquals(setOf("餐前", "餐后"), assigned.map { it.name }.toSet())
            org.junit.Assert.assertTrue(groups.none { it.name == "药品" })
        }
        compose.onNodeWithText("库内两次药").assertIsDisplayed()
        compose.onNodeWithContentDescription("当前使用").assertIsDisplayed()
        compose.onNodeWithContentDescription("药品条目：库内两次药").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("停用").assertIsDisplayed()
        compose.onNodeWithContentDescription("使用").assertDoesNotExist()
    }

    @Test fun libraryUseThreeTimesAutomaticallyAssignsMorningNoonEveningWithoutDuplicates() {
        val repo = (compose.activity.application as MedApplication).repository
        val medicineId = runBlocking {
            repo.dao.insertCard(NoteCard(name = "早晨"))
            repo.dao.insertCard(NoteCard(name = "中午"))
            repo.dao.insertCard(NoteCard(name = "晚间"))
            repo.addMedicine(Medicine(name = "库内三次药", dailyFrequency = "3", defaultDose = "2粒", mealTiming = "餐前"))
        }
        compose.onNodeWithText("药品库").performClick()
        compose.onNodeWithContentDescription("药品条目：库内三次药").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("使用").performClick()
        compose.waitUntil(5000) { runBlocking { repo.dao.itemsForMedicine(medicineId).size == 3 } }
        runBlocking {
            val assigned = repo.dao.itemsForMedicine(medicineId)
            org.junit.Assert.assertEquals(3, assigned.map { it.groupId }.distinct().size)
            org.junit.Assert.assertEquals(1, repo.dao.medicinePeriods(medicineId).size)
        }
        // Only a new active period should be added; no repeated entries on a single card.
        runBlocking {
            org.junit.Assert.assertEquals(1, repo.dao.medicinePeriods(medicineId).count { it.active })
            org.junit.Assert.assertTrue(repo.dao.itemsForMedicine(medicineId).all { it.dose == "2粒" })
        }
    }

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
    @Test fun editMedicineFieldsAndPhotosDirectlyInItemDialog() {
        val repo = (compose.activity.application as MedApplication).repository
        runBlocking {
            val card = repo.dao.insertCard(NoteCard())
            val group = repo.saveGroup(NoteGroup(cardId = card, name = "餐后"), Reminder(0))
            repo.saveItem(group, null, null, "原药品", "10mg", "1片", "/test-photo")
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("原药品").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("原药品").performClick()
        compose.onNodeWithText("药品或护理名称").assertIsDisplayed().performTextClearance()
        compose.onNodeWithText("药品或护理名称").performTextInput("取消草稿")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("原药品").assertIsDisplayed().performClick()
        compose.onNodeWithText("编辑药品名称、规格或图片").assertDoesNotExist()
        compose.onNodeWithText("药品或护理名称").performTextClearance()
        compose.onNodeWithText("药品或护理名称").performTextInput("新药品")
        compose.onNodeWithText("规格（可不填）").performTextClearance()
        compose.onNodeWithText("规格（可不填）").performTextInput("20mg")
        compose.onNodeWithText("每次用量").performTextClearance()
        compose.onNodeWithText("每次用量").performTextInput("3片")
        compose.onNodeWithText("删除第1张").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("编辑项目").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("新药品").assertIsDisplayed()
        compose.onNodeWithText("20mg").assertDoesNotExist()
        compose.onNodeWithText("3片").assertIsDisplayed()
        compose.onNodeWithText("新药品").performClick()
        compose.onNodeWithText("药品或护理名称").assertIsDisplayed()
        compose.onNodeWithText("继续添加图片").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        runBlocking {
            org.junit.Assert.assertEquals("新药品", repo.dao.medicines().first().single().name)
            org.junit.Assert.assertTrue(repo.dao.medicines().first().single().photos().isEmpty())
        }
    }
    @Test fun addEditAndReorderDivider() {
        compose.onNodeWithContentDescription("添加卡片").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("＋ 添加大类").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("＋ 添加大类").onFirst().performClick()
        compose.onNodeWithText("大类名称").performTextInput("分批")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("添加大类").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("＋ 分隔条").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("＋ 分隔条").performClick()
        compose.onNodeWithText("添加分隔条").assertIsDisplayed()
        compose.onNodeWithText("分隔文字").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("添加分隔条").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("1小时").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1小时").performClick()
        compose.onNodeWithText("分隔文字").performTextClearance()
        compose.onNodeWithText("分隔文字").performTextInput("间隔1小时")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("间隔1小时").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("排序").performClick()
        compose.onNodeWithText("分隔条：间隔1小时").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("间隔1小时").assertIsDisplayed()
    }
    @Test fun referenceLayoutAndImageViewer() {
        val app = compose.activity.application as MedApplication
        val launcher = app.packageManager.getApplicationIcon(app.packageName).mutate()
        org.junit.Assert.assertTrue(launcher is android.graphics.drawable.AdaptiveIconDrawable)
        val adaptive = launcher as android.graphics.drawable.AdaptiveIconDrawable
        val iconPreview = Bitmap.createBitmap(768, 256, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(iconPreview)
        canvas.drawColor(android.graphics.Color.LTGRAY)
        adaptive.setBounds(0, 0, 256, 256)
        for (shape in 0..2) {
            canvas.save()
            canvas.translate(shape * 256f, 0f)
            if (shape == 0) adaptive.draw(canvas) else {
                val mask = android.graphics.Path()
                if (shape == 1) mask.addCircle(128f, 128f, 128f, android.graphics.Path.Direction.CW)
                else mask.addRoundRect(0f, 0f, 256f, 256f, 58f, 58f, android.graphics.Path.Direction.CW)
                canvas.clipPath(mask)
                adaptive.background.draw(canvas)
                adaptive.foreground.draw(canvas)
            }
            canvas.restore()
        }
        val iconFile = java.io.File(compose.activity.getExternalFilesDir("qa"), "launcher-masks.png")
        iconFile.parentFile!!.mkdirs()
        iconFile.outputStream().use { iconPreview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        logScreenshot(iconFile)
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
        runBlocking {
            val med = app.repository.dao.medicines().first().first()
            val second = java.io.File(app.filesDir, "qa-second.png")
            java.io.File(med.image!!).copyTo(second, overwrite = true)
            app.repository.updateMedicine(med.copy(imagesJson = encodePhotos(listOf(med.image!!, second.path))))
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("查看示例药品 1图片").performClick()
        compose.onNodeWithContentDescription("药品大图").assertIsDisplayed()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("药品大图").performTouchInput {
            pinch(center - androidx.compose.ui.geometry.Offset(30f, 0f), center + androidx.compose.ui.geometry.Offset(30f, 0f), center - androidx.compose.ui.geometry.Offset(100f, 0f), center + androidx.compose.ui.geometry.Offset(100f, 0f))
        }
        compose.onNodeWithContentDescription("药品大图").assert(SemanticsMatcher("image is zoomed") {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) { "" } != "缩放100%"
        })
        compose.onNodeWithText("恢复原图").performClick()
        compose.onNodeWithContentDescription("药品大图").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "缩放100%"))
        compose.onNodeWithContentDescription("药品大图").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一张").performClick()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("关闭大图").performClick()
        compose.onNodeWithText("示例药品 1").assertIsDisplayed()
    }
}
