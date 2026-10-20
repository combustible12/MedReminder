package com.combustible12.medreminder

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class ReminderTest {
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun dumpAlarms() { shell("dumpsys alarm").chunked(2800).forEach { android.util.Log.i("AlarmQA", it) } }
    @Test fun alarmFiresOnLockScreenAndUsesLatestDoses() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageName = context.packageName
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
        if (Build.VERSION.SDK_INT >= 31) shell("appops set $packageName SCHEDULE_EXACT_ALARM allow")
        // Normal use starts the app at least once. Launch before backgrounding so Android clears FLAG_STOPPED.
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val db = Room.databaseBuilder(context, MedDatabase::class.java, "alarm-test.db").build()
        val repo = Repository(db)
        val scheduler = ReminderScheduler(context, db)
        scheduler.createChannel()
        var groupId = -1L
        try {
            assertNull(scheduler.permissionProblem())
            val card = repo.dao.insertCard(NoteCard())
            val target = ZonedDateTime.now().plusMinutes(1)
            groupId = repo.saveGroup(NoteGroup(cardId = card, name = "提醒验收"), Reminder(0, target.hour, target.minute, true))
            repo.saveItem(groupId, null, null, "测试用药", "", "2片", null)
            // Receiver opens the application database; populate the same target there for a real AlarmManager broadcast.
            val app = context.applicationContext as MedApplication
            val real = app.repository
            val realCard = real.dao.insertCard(NoteCard())
            val realId = real.saveGroup(NoteGroup(cardId = realCard, name = "提醒验收"), Reminder(0, target.hour, target.minute, true))
            real.saveItem(realId, null, null, "通知测试药", "", "2片", null)
            try {
                assertNull(app.scheduler.schedule(realId))
                android.util.Log.i("AlarmQA", "target=$target now=${ZonedDateTime.now()} stopped=${context.packageManager.getApplicationInfo(packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_STOPPED}")
                shell("input keyevent 3")
                dumpAlarms()
                shell("input keyevent 223")
                val manager = context.getSystemService(NotificationManager::class.java)
                try { withTimeout(80000) { while (manager.activeNotifications.none { it.tag == "group-$realId" }) delay(500) } }
                catch (e: Exception) { dumpAlarms(); throw e }
                val notification = manager.activeNotifications.single { it.tag == "group-$realId" }.notification
                assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("通知测试药  2片"))
                assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("提醒验收"))
                app.scheduler.cancel(realId)
                real.dao.deleteGroup(realId)
                assertNull(real.dao.reminder(realId))
                assertTrue(manager.activeNotifications.none { it.tag == "group-$realId" })
            } finally { app.scheduler.cancel(realId); real.dao.deleteCard(realCard); shell("input keyevent 224"); shell("wm dismiss-keyguard") }
        } finally { if (groupId > 0) scheduler.cancel(groupId); db.close(); context.deleteDatabase("alarm-test.db"); activity.close() }
    }
    @Test fun missingNotificationPermissionReported() = runBlocking {
        if (Build.VERSION.SDK_INT < 33) return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as MedApplication
        shell("appops set ${context.packageName} POST_NOTIFICATION deny")
        try { assertNotNull(app.scheduler.permissionProblem()) }
        finally { shell("appops set ${context.packageName} POST_NOTIFICATION allow") }
    }
}
