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
data class Medicine(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val specification: String = "", val image: String? = null, val defaultDose: String = "", val identity: String = identityOf(name, specification), @ColumnInfo(defaultValue = "''") val imagesJson: String = "")
@Entity(tableName = "items", foreignKeys = [ForeignKey(entity = NoteGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = Medicine::class, parentColumns = ["id"], childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE)], indices = [Index("groupId"), Index("medicineId")])
data class NoteItem(@PrimaryKey(autoGenerate = true) val id: Long = 0, val groupId: Long, val medicineId: Long, val dose: String, val position: Int = 0)
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

data class Notebook(val cards: List<NoteCard> = emptyList(), val groups: List<NoteGroup> = emptyList(), val items: List<NoteItem> = emptyList(), val medicines: List<Medicine> = emptyList(), val periods: List<UsePeriod> = emptyList(), val reminders: List<Reminder> = emptyList())

@Dao
interface MedDao {
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
    @Query("DELETE FROM cards WHERE id=:id") suspend fun deleteCard(id: Long)
    @Query("DELETE FROM groups WHERE id=:id") suspend fun deleteGroup(id: Long)
    @Query("DELETE FROM items WHERE id=:id") suspend fun deleteItem(id: Long)
    @Query("DELETE FROM medicines WHERE id=:id") suspend fun deleteMedicine(id: Long)
    @Query("DELETE FROM periods WHERE id=:id") suspend fun deletePeriod(id: Long)
    @Query("SELECT COALESCE(MAX(position), -1)+1 FROM cards") suspend fun nextCard(): Int
    @Query("SELECT COALESCE(MAX(position), -1)+1 FROM groups WHERE cardId=:cardId") suspend fun nextGroup(cardId: Long): Int
    @Query("SELECT COALESCE(MAX(position), -1)+1 FROM items WHERE groupId=:groupId") suspend fun nextItem(groupId: Long): Int
}

@Database(entities = [NoteCard::class, NoteGroup::class, Medicine::class, NoteItem::class, UsePeriod::class, Reminder::class], version = 2, exportSchema = true)
abstract class MedDatabase : RoomDatabase() {
    abstract fun dao(): MedDao
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE medicines ADD COLUMN imagesJson TEXT NOT NULL DEFAULT ''")
    }
}

class Repository(val db: MedDatabase) {
    val dao = db.dao()
    val notebook = combine(dao.cards(), dao.groups(), dao.items(), dao.medicines(), dao.periods()) { c, g, i, m, p -> Notebook(c, g, i, m, p) }.combine(dao.reminders()) { n, r -> n.copy(reminders = r) }
    suspend fun saveCard(value: NoteCard) { if (value.id == 0L) dao.insertCard(value.copy(position = dao.nextCard())) else dao.updateCard(value) }
    suspend fun saveGroup(value: NoteGroup, reminder: Reminder): Long = db.withTransaction {
        val id = if (value.id == 0L) dao.insertGroup(value.copy(position = dao.nextGroup(value.cardId))) else { dao.updateGroup(value); value.id }
        require(reminder.hour == null && reminder.minute == null || reminder.hour in 0..23 && reminder.minute in 0..59) { "提醒时间无效" }
        dao.putReminder(reminder.copy(groupId = id, enabled = reminder.enabled && reminder.hour != null && reminder.minute != null))
        id
    }
    suspend fun saveItem(groupId: Long, itemId: Long?, selectedId: Long?, name: String, spec: String, dose: String, image: String?, imagesJson: String = ""): Long = db.withTransaction {
        require(name.isNotBlank()) { "请填写名称" }
        val old = itemId?.let { dao.item(it) }
        if (itemId != null) requireNotNull(old) { "条目已被删除" }
        requireNotNull(dao.group(groupId)) { "大类已被删除" }
        val selected = selectedId?.let { dao.medicine(it) }
        if (selectedId != null) requireNotNull(selected) { "药品已被删除" }
        // Editing an arrangement may select a different identity; it must not rename every other arrangement.
        val med = selected ?: dao.findMedicine(identityOf(name, spec))
        if (selected == null && med != null && image != null && image != med.image) dao.updateMedicine(med.copy(image = image, imagesJson = imagesJson))
        val medId = med?.id ?: dao.insertMedicine(Medicine(name = name.trim(), specification = spec.trim(), defaultDose = dose.trim(), image = image, imagesJson = imagesJson))
        if (dao.medicinePeriods(medId).none { it.active }) dao.insertPeriod(UsePeriod(medicineId = medId))
        if (old == null) dao.insertItem(NoteItem(groupId = groupId, medicineId = medId, dose = dose.trim(), position = dao.nextItem(groupId)))
        else { dao.updateItem(old.copy(medicineId = medId, dose = dose.trim())); old.id }
    }
    suspend fun updateMedicine(value: Medicine) = db.withTransaction {
        require(value.name.isNotBlank()) { "请填写名称" }
        val identity = identityOf(value.name, value.specification)
        val existing = dao.findMedicine(identity)
        require(existing == null || existing.id == value.id) { "相同名称和规格的药品已存在，请使用已有档案" }
        dao.updateMedicine(value.copy(name = value.name.trim(), specification = value.specification.trim(), identity = identity))
    }
    suspend fun savePeriod(value: UsePeriod) = db.withTransaction {
        validateDates(value.start, value.end)
        require(!value.active || value.end == null) { "当前使用记录不能有停用日期" }
        val previous = if (value.id != 0L) dao.medicinePeriods(value.medicineId).firstOrNull { it.id == value.id } else null
        require(previous == null || previous.active || !value.active) { "再次使用请添加新记录，原有日期会保留" }
        if (value.active) dao.medicinePeriods(value.medicineId).filter { it.active && it.id != value.id }.forEach { dao.updatePeriod(it.copy(active = false)) }
        if (value.id == 0L) dao.insertPeriod(value) else dao.updatePeriod(value)
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
    val database by lazy { Room.databaseBuilder(this, MedDatabase::class.java, "medreminder.db").addMigrations(MIGRATION_1_2).build() }
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
