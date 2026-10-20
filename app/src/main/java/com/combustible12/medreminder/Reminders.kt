package com.combustible12.medreminder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

fun nextReminder(hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
    require(hour in 0..23 && minute in 0..59)
    val today = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
    return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(now.zone)
}

class ReminderScheduler(private val context: Context, private val db: MedDatabase) {
    companion object { const val CHANNEL = "daily-medication"; val mutex = Mutex() }
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "用药提醒", NotificationManager.IMPORTANCE_HIGH).apply { description = "按大类每日提醒"; lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
    }
    fun permissionProblem(): String? {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "尚未允许通知，提醒无法显示"
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "系统通知已关闭，提醒无法显示"
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return "用药提醒通知频道已关闭"
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) return "尚未允许精确闹钟，提醒未生效"
        return null
    }
    private fun pending(id: Long, revision: Long? = null): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setData(android.net.Uri.parse("medreminder://group/$id")).putExtra("groupId", id)
        if (revision != null) intent.putExtra("revision", revision)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or if (revision != null) PendingIntent.FLAG_UPDATE_CURRENT else 0)
    }
    fun cancel(id: Long) {
        alarms.cancel(pending(id))
        NotificationManagerCompat.from(context).cancel("group-$id", 1)
    }
    suspend fun scheduleAll(): Map<Long, String> = mutex.withLock {
        val failures = mutableMapOf<Long, String>()
        db.dao().allReminders().forEach { r -> scheduleUnlocked(r)?.let { failures[r.groupId] = it } }
        failures
    }
    suspend fun schedule(id: Long): String? = mutex.withLock {
        db.dao().reminder(id)?.let { scheduleUnlocked(it) } ?: run { cancel(id); null }
    }
    private fun scheduleUnlocked(r: Reminder): String? {
        alarms.cancel(pending(r.groupId))
        if (!r.enabled || r.hour == null || r.minute == null) return null
        permissionProblem()?.let { return it }
        return try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextReminder(r.hour, r.minute).toInstant().toEpochMilli(), pending(r.groupId, r.revision))
            null
        } catch (_: SecurityException) { "系统未允许精确闹钟，提醒未生效" }
          catch (_: RuntimeException) { "系统未能安排提醒，请重新开启提醒" }
    }
    suspend fun deliver(id: Long, revision: Long) = mutex.withLock {
        val dao = db.dao()
        val r = dao.reminder(id) ?: return@withLock
        val group = dao.group(id) ?: return@withLock
        if (!r.enabled || r.time() == null) return@withLock
        // An old broadcast queued before an edit must never deliver the old schedule.
        if (r.revision != revision) return@withLock
        val lines = dao.groupItems(id).mapNotNull { item -> dao.medicine(item.medicineId)?.let { med -> "${med.name}  ${item.dose}".trim() } }
        if (permissionProblem() == null && lines.isNotEmpty()) {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).putExtra("groupId", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val title = listOf(group.name, r.time()).filterNotNull().filter { it.isNotBlank() }.joinToString("｜")
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_med).setContentTitle(title)
                .setContentText(lines.joinToString("；"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER).build()
            try { NotificationManagerCompat.from(context).notify("group-$id", 1, notification) } catch (_: SecurityException) { /* The UI reports revoked permissions on resume. */ }
        }
        scheduleUnlocked(r)
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("groupId", -1)
        if (id < 0) return
        val pending = goAsync()
        val app = context.applicationContext as MedApplication
        app.scope.launch { try { app.scheduler.deliver(id, intent.getLongExtra("revision", -1)) } finally { pending.finish() } }
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as MedApplication
        app.scope.launch { try { app.scheduler.scheduleAll() } finally { pending.finish() } }
    }
}
