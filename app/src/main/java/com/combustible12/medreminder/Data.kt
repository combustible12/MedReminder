package com.combustible12.medreminder

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.LocalDate
import java.util.UUID

@Entity(tableName = "cards")
data class NoteCard(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String = "", val color: Int = 0, val position: Int = 0)
@Entity(tableName = "groups", foreignKeys = [ForeignKey(entity = NoteCard::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)], indices = [Index("cardId")])
data class NoteGroup(@PrimaryKey(autoGenerate = true) val id: Long = 0, val cardId: Long, val name: String, val position: Int = 0)
@Entity(tableName = "medicines", indices = [Index(value = ["identity"], unique = true)])
data class Medicine(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val specification: String = "", val image: String? = null, val defaultDose: String = "", val identity: String = identityOf(name, specification), @ColumnInfo(defaultValue = "''") val imagesJson: String = "", @ColumnInfo(defaultValue = "0") val starred: Boolean = false, @ColumnInfo(defaultValue = "''") val dailyFrequency: String = "")
@Entity(tableName = "items", foreignKeys = [ForeignKey(entity = NoteGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = Medicine::class, parentColumns = ["id"], childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE)], indices = [Index("groupId"), Index("medicineId")])
data class NoteItem(@PrimaryKey(autoGenerate = true) val id: Long = 0, val groupId: Long, val medicineId: Long, val dose: String, val position: Int = 0, @ColumnInfo(defaultValue = "0") val starred: Boolean = false)
@Entity(tableName = "dividers", foreignKeys = [ForeignKey(entity = NoteGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)], indices = [Index("groupId")])
data class NoteDivider(@PrimaryKey(autoGenerate = true) val id: Long = 0, val groupId: Long, val text: String = "1小时", val position: Int = 0)

@Entity(tableName = "periods", foreignKeys = [ForeignKey(entity = Medicine::class, parentColumns = ["id"], childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE)], indices = [Index("medicineId")])
data class UsePeriod(@PrimaryKey(autoGenerate = true) val id: Long = 0, val medicineId: Long, val start: String? = null, val end: String? = null, val active: Boolean = true)
@Entity(tableName = "reminders", foreignKeys = [ForeignKey(entity = NoteGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)])
data class Reminder(@PrimaryKey val groupId: Long, val hour: Int? = null, val minute: Int? = null, val enabled: Boolean = false, val revision: Long = System.currentTimeMillis()) {
    fun time(): String? = if (hour != null && minute != null) "%02d:%02d".format(hour, minute) else null
}

fun identityOf(name: String, spec: String): String = name.trim().lowercase() + "\u001f" + spec.trim().lowercase()
fun validateDates(start: String?, end: String?) {
    val a = start?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
    val b = end?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
    require(a == null || b == null || !b.isBefore(a)) { "停用日期不能早于开始日期" }
}

data class Notebook(val cards: List<NoteCard> = emptyList(), val groups: List<NoteGroup> = emptyList(), val items: List<NoteItem> = emptyList(), val medicines: List<Medicine> = emptyList(), val periods: List<UsePeriod> = emptyList(), val reminders: List<Reminder> = emptyList(), val dividers: List<NoteDivider> = emptyList())

data class NotebookEntry(val item: NoteItem? = null, val divider: NoteDivider? = null) {
    val key: Long get() = item?.id ?: -requireNotNull(divider).id
    val position: Int get() = item?.position ?: requireNotNull(divider).position
}
fun Notebook.groupEntries(groupId: Long): List<NotebookEntry> =
    (items.filter { it.groupId == groupId }.map { NotebookEntry(item = it) } +
        dividers.filter { it.groupId == groupId }.map { NotebookEntry(divider = it) })
        .sortedWith(compareBy<NotebookEntry> { it.position }.thenBy { it.key })

@Dao
interface MedDao {
    @Query("SELECT * FROM dividers ORDER BY position, id") fun dividers(): Flow<List<NoteDivider>>
    @Query("SELECT * FROM dividers WHERE id=:id") suspend fun divider(id: Long): NoteDivider?
    @Insert suspend fun insertDivider(value: NoteDivider): Long
    @Update suspend fun updateDivider(value: NoteDivider)
    @Query("DELETE FROM dividers WHERE id=:id") suspend fun deleteDivider(id: Long)

    @Query("SELECT * FROM cards ORDER BY position, id") fun cards(): Flow<List<NoteCard>>
    @Query("SELECT * FROM groups ORDER BY position, id") fun groups(): Flow<List<NoteGroup>>
    @Query("SELECT * FROM items ORDER BY position, id") fun items(): Flow<List<NoteItem>>
    @Query("SELECT * FROM medicines ORDER BY name, id") fun medicines(): Flow<List<Medicine>>
    @Query("SELECT * FROM periods ORDER BY id DESC") fun periods(): Flow<List<UsePeriod>>
    @Query("SELECT * FROM reminders") fun reminders(): Flow<List<Reminder>>
    @Query("SELECT * FROM cards WHERE id=:id") suspend fun card(id: Long): NoteCard?
    @Query("SELECT * FROM groups WHERE id=:id") suspend fun group(id: Long): NoteGroup?
    @Query("SELECT * FROM reminders WHERE groupId=:id") suspend fun reminder(id: Long): Reminder?
    @Query("SELECT * FROM reminders") suspend fun allReminders(): List<Reminder>
    @Query("SELECT * FROM medicines WHERE id=:id") suspend fun medicine(id: Long): Medicine?
    @Query("SELECT * FROM medicines WHERE identity=:identity LIMIT 1") suspend fun findMedicine(identity: String): Medicine?
    @Query("SELECT * FROM items WHERE id=:id") suspend fun item(id: Long): NoteItem?
    @Query("SELECT * FROM items WHERE groupId=:id ORDER BY position, id") suspend fun groupItems(id: Long): List<NoteItem>
    @Query("SELECT * FROM periods WHERE medicineId=:id ORDER BY id DESC") suspend fun medicinePeriods(id: Long): List<UsePeriod>
    @Insert suspend fun insertCard(value: NoteCard): Long
    @Insert suspend fun insertGroup(value: NoteGroup): Long
    @Insert suspend fun insertMedicine(value: Medicine): Long
    @Insert suspend fun insertItem(value: NoteItem): Long
    @Insert suspend fun insertPeriod(value: UsePeriod): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReminder(value: Reminder)
    @Update suspend fun updateCard(value: NoteCard)
    @Update suspend fun updateGroup(value: NoteGroup)
    @Update suspend fun updateMedicine(value: Medicine)
    @Update suspend fun updateItem(value: NoteItem)
    @Update suspend fun updatePeriod(value: UsePeriod)
    @Query("UPDATE items SET starred=:starred WHERE medicineId=:medicineId") suspend fun setItemStars(medicineId: Long, starred: Boolean)
    @Query("DELETE FROM cards WHERE id=:id") suspend fun deleteCard(id: Long)
    @Query("DELETE FROM groups WHERE id=:id") suspend fun deleteGroup(id: Long)
    @Query("DELETE FROM items WHERE id=:id") suspend fun deleteItem(id: Long)
    @Query("DELETE FROM medicines WHERE id=:id") suspend fun deleteMedicine(id: Long)
    @Query("DELETE FROM periods WHERE id=:id") suspend fun deletePeriod(id: Long)
    @Query("SELECT COALESCE(MAX(position), -1)+1 FROM cards") suspend fun nextCard(): Int
    @Query("SELECT COALESCE(MAX(position), -1)+1 FROM groups WHERE cardId=:cardId") suspend fun nextGroup(cardId: Long): Int
    @Query("SELECT COALESCE(MAX(position), -1)+1 FROM (SELECT position FROM items WHERE groupId=:groupId UNION ALL SELECT position FROM dividers WHERE groupId=:groupId)") suspend fun nextItem(groupId: Long): Int
}

@Database(entities = [NoteCard::class, NoteGroup::class, Medicine::class, NoteItem::class, UsePeriod::class, Reminder::class, NoteDivider::class], version = 6, exportSchema = true)
abstract class MedDatabase : RoomDatabase() {
    abstract fun dao(): MedDao
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE medicines ADD COLUMN imagesJson TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS dividers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, groupId INTEGER NOT NULL, text TEXT NOT NULL, position INTEGER NOT NULL, FOREIGN KEY(groupId) REFERENCES groups(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_dividers_groupId ON dividers(groupId)")
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE medicines ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE medicines SET starred = 1 WHERE id IN (SELECT medicineId FROM items WHERE starred = 1)")
        db.execSQL("UPDATE items SET starred = (SELECT starred FROM medicines WHERE medicines.id = items.medicineId)")
    }
}

val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE medicines ADD COLUMN dailyFrequency TEXT NOT NULL DEFAULT ''")
    }
}

fun normalizeDailyFrequency(value: String): String {
    if (value.isBlank()) return ""
    val count = value.trim().toIntOrNull()
    require(count != null && count > 0) { "每天次数请填写大于0的整数" }
    return count.toString()
}
fun Medicine.frequencyLabel(): String = if (dailyFrequency.isBlank()) "" else "1天${dailyFrequency}次"

class Repository(val db: MedDatabase) {
    val dao = db.dao()
    val notebook = combine(dao.cards(), dao.groups(), dao.items(), dao.medicines(), dao.periods()) { c, g, i, m, p -> Notebook(c, g, i, m, p) }.combine(dao.reminders()) { n, r -> n.copy(reminders = r) }.combine(dao.dividers()) { n, d -> n.copy(dividers = d) }
    suspend fun saveCard(value: NoteCard) { if (value.id == 0L) dao.insertCard(value.copy(position = dao.nextCard())) else dao.updateCard(value) }
    suspend fun saveGroup(value: NoteGroup, reminder: Reminder): Long = db.withTransaction {
        val id = if (value.id == 0L) dao.insertGroup(value.copy(position = dao.nextGroup(value.cardId))) else { dao.updateGroup(value); value.id }
        require(reminder.hour == null && reminder.minute == null || reminder.hour in 0..23 && reminder.minute in 0..59) { "提醒时间无效" }
        dao.putReminder(reminder.copy(groupId = id, enabled = reminder.enabled && reminder.hour != null && reminder.minute != null))
        id
    }
    suspend fun saveItem(groupId: Long, itemId: Long?, selectedId: Long?, name: String, spec: String, dose: String, image: String?, imagesJson: String = "", editSelected: Boolean = false, dailyFrequency: String? = null): Long = db.withTransaction {
        require(name.isNotBlank()) { "请填写名称" }
        val old = itemId?.let { dao.item(it) }
        if (itemId != null) requireNotNull(old) { "条目已被删除" }
        requireNotNull(dao.group(groupId)) { "大类已被删除" }
        val selected = selectedId?.let { dao.medicine(it) }
        if (selectedId != null) requireNotNull(selected) { "药品已被删除" }
        if (editSelected && selected != null) {
            updateMedicine(selected.copy(name = name, specification = spec, image = image, imagesJson = imagesJson))
        }
        // Editing an arrangement may select a different identity; it must not rename every other arrangement.
        val med = selected ?: dao.findMedicine(identityOf(name, spec))
        if (selected == null && med != null && image != null) {
            val photos = (med.photos() + image + decodePhotos(imagesJson)).distinct()
            dao.updateMedicine(med.copy(image = photos.firstOrNull(), imagesJson = encodePhotos(photos)))
        }
        val medId = med?.id ?: dao.insertMedicine(Medicine(name = name.trim(), specification = spec.trim(), defaultDose = dose.trim(), image = image, imagesJson = imagesJson))
        if (dailyFrequency != null) {
            val current = requireNotNull(dao.medicine(medId))
            dao.updateMedicine(current.copy(dailyFrequency = normalizeDailyFrequency(dailyFrequency)))
        }
        if (dao.medicinePeriods(medId).none { it.active }) dao.insertPeriod(UsePeriod(medicineId = medId))
        if (old == null) dao.insertItem(NoteItem(groupId = groupId, medicineId = medId, dose = dose.trim(), position = dao.nextItem(groupId), starred = dao.medicine(medId)!!.starred))
        else { dao.updateItem(old.copy(medicineId = medId, dose = dose.trim(), starred = dao.medicine(medId)!!.starred)); old.id }
    }
    suspend fun updateMedicine(value: Medicine) = db.withTransaction {
        require(value.name.isNotBlank()) { "请填写名称" }
        val identity = identityOf(value.name, value.specification)
        val existing = dao.findMedicine(identity)
        require(existing == null || existing.id == value.id) { "相同名称和规格的药品已存在，请使用已有档案" }
        dao.updateMedicine(value.copy(name = value.name.trim(), specification = value.specification.trim(), identity = identity, dailyFrequency = normalizeDailyFrequency(value.dailyFrequency)))
    }
    suspend fun savePeriod(value: UsePeriod) = db.withTransaction {
        validateDates(value.start, value.end)
        require(!value.active || value.end == null) { "当前使用记录不能有停用日期" }
        val previous = if (value.id != 0L) dao.medicinePeriods(value.medicineId).firstOrNull { it.id == value.id } else null
        require(previous == null || previous.active || !value.active) { "再次使用请添加新记录，原有日期会保留" }
        if (value.active) dao.medicinePeriods(value.medicineId).filter { it.active && it.id != value.id }.forEach { dao.updatePeriod(it.copy(active = false)) }
        if (value.id == 0L) dao.insertPeriod(value) else dao.updatePeriod(value)
    }
    suspend fun saveDivider(value: NoteDivider): Long = db.withTransaction {
        require(value.text.isNotBlank()) { "请填写分隔文字" }
        requireNotNull(dao.group(value.groupId)) { "大类已被删除" }
        if (value.id == 0L) dao.insertDivider(value.copy(text = value.text.trim(), position = dao.nextItem(value.groupId)))
        else {
            val old = requireNotNull(dao.divider(value.id)) { "分隔条已被删除" }
            require(old.groupId == value.groupId)
            dao.updateDivider(old.copy(text = value.text.trim())); old.id
        }
    }
    suspend fun reorderEntries(groupId: Long, keys: List<Long>) = db.withTransaction {
        val current = dao.groupItems(groupId).map { it.id }.toSet() + dao.dividers().first().filter { it.groupId == groupId }.map { -it.id }
        require(keys.size == keys.distinct().size && keys.toSet() == current) { "列表已变化，请重新打开排序" }
        keys.forEachIndexed { position, key ->
            if (key > 0) dao.item(key)?.let { dao.updateItem(it.copy(position = position)) }
            else dao.divider(-key)?.let { dao.updateDivider(it.copy(position = position)) }
        }
    }
    suspend fun toggleItemStar(id: Long) = db.withTransaction {
        val item = requireNotNull(dao.item(id)) { "条目已被删除" }
        val medicine = requireNotNull(dao.medicine(item.medicineId))
        val starred = !medicine.starred
        dao.updateMedicine(medicine.copy(starred = starred))
        dao.setItemStars(medicine.id, starred)
    }
    suspend fun moveItemToEdge(id: Long, top: Boolean) = db.withTransaction {
        val item = requireNotNull(dao.item(id)) { "条目已被删除" }
        val entries = (dao.groupItems(item.groupId).map { NotebookEntry(item = it) } +
            dao.dividers().first().filter { it.groupId == item.groupId }.map { NotebookEntry(divider = it) })
            .sortedWith(compareBy<NotebookEntry> { it.position }.thenBy { it.key }).map { it.key }
        val rest = entries.filterNot { it == id }
        reorderEntries(item.groupId, if (top) listOf(id) + rest else rest + id)
    }
    suspend fun reorderCards(ids: List<Long>) = db.withTransaction { ids.forEachIndexed { p, id -> dao.card(id)?.let { dao.updateCard(it.copy(position = p)) } } }
    suspend fun reorderGroups(ids: List<Long>) = db.withTransaction { ids.forEachIndexed { p, id -> dao.group(id)?.let { dao.updateGroup(it.copy(position = p)) } } }
    suspend fun reorderItems(ids: List<Long>) = db.withTransaction { ids.forEachIndexed { p, id -> dao.item(id)?.let { dao.updateItem(it.copy(position = p)) } } }
}

class ImageStore(private val context: Context) {
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "medicine-images").apply { mkdirs() }
        val target = File(directory, UUID.randomUUID().toString())
        try {
            context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "无法读取图片" }; target.outputStream().use { input.copyTo(it) } }
            require(target.length() > 0) { "图片为空" }
            target.absolutePath
        } catch (e: Exception) { target.delete(); throw e }
    }
    // Only unreferenced, saved images are removed; deleting a home arrangement never touches images.
    suspend fun removeIfUnused(path: String?, db: MedDatabase) = withContext(Dispatchers.IO) {
        if (path != null && db.dao().medicines().first().none { path in it.photos() }) {
            val file = File(path)
            if (file.canonicalFile.parentFile == File(context.filesDir, "medicine-images").canonicalFile) file.delete()
        }
    }
}

class MedApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, MedDatabase::class.java, "medreminder.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build() }
    val repository by lazy { Repository(database) }
    val scheduler by lazy { ReminderScheduler(this, database) }
    override fun onCreate() { super.onCreate(); scheduler.createChannel() }
}

// Preserve the original image column as the cover for existing installs and reminders.
fun Medicine.photos(): List<String> = (listOfNotNull(image) + decodePhotos(imagesJson)).distinct()
fun decodePhotos(value: String): List<String> = if (value.isBlank()) emptyList() else runCatching {
    val array = org.json.JSONArray(value)
    (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }
}.getOrDefault(emptyList())
fun encodePhotos(paths: List<String>): String = org.json.JSONArray(paths.distinct()).toString()
