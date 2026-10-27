package com.combustible12.medreminder

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

private val Ink = Color(0xFF151B2D)
private val Quiet = Color(0xFF8C929C)
private val Colors = listOf(Color(0xFFFDECF2), Color(0xFFFFF2E5), Color(0xFFE7F7EF), Color(0xFFEFECFC), Color(0xFFE5F4FD))
private val Accents = listOf(Color(0xFFC67D91), Color(0xFFC68C5E), Color(0xFF65A894), Color(0xFF9384BE), Color(0xFF73A6C7))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF7289B2), background = Color.White, surface = Color.White, onSurface = Ink)) { NotebookApp(application as MedApplication) } }
    }
}
private data class ItemEditor(val groupId: Long, val item: NoteItem? = null)
private data class GroupEditor(val cardId: Long, val group: NoteGroup? = null)
private data class Confirmation(val title: String, val text: String, val action: suspend () -> Unit)
private data class OrderEditor(val title: String, val rows: List<Pair<Long, String>>, val save: suspend (List<Long>) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookApp(app: MedApplication) {
    val repo = app.repository
    val state by repo.notebook.collectAsStateWithLifecycle(Notebook())
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cardEditor by remember { mutableStateOf<NoteCard?>(null) }
    var groupEditor by remember { mutableStateOf<GroupEditor?>(null) }
    var dividerEditor by remember { mutableStateOf<NoteDivider?>(null) }
    var itemEditor by remember { mutableStateOf<ItemEditor?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var medicineEditor by remember { mutableStateOf<Medicine?>(null) }
    var periodEditor by remember { mutableStateOf<UsePeriod?>(null) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    var order by remember { mutableStateOf<OrderEditor?>(null) }
    var fullImage by remember { mutableStateOf<String?>(null) }
    var permissionSheet by remember { mutableStateOf(false) }
    var permissionProblem by remember { mutableStateOf(app.scheduler.permissionProblem()) }
    var permissionEpoch by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    var busy by remember { mutableStateOf(false) }
    fun perform(action: suspend () -> Unit) { if (busy) return; busy = true; scope.launch { try { action() } catch (e: Exception) { snack.showSnackbar(e.message ?: "保存失败，请重试") } finally { busy = false } } }
    fun refreshPermissions() { permissionEpoch++; permissionProblem = app.scheduler.permissionProblem(); scope.launch { app.scheduler.scheduleAll() } }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshPermissions() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.reminders, permissionEpoch) { permissionProblem = app.scheduler.permissionProblem(); app.scheduler.scheduleAll() }
    BackHandler(detailId != null) { detailId = null }
    Scaffold(containerColor = Color.White, snackbarHost = { SnackbarHost(snack) },
        topBar = { TopAppBar(title = { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(if (detailId != null) "药品资料" else when (tab) { 0 -> "用药便签"; 1 -> "药品库"; else -> "用药时长" }, fontSize = 23.sp, fontWeight = FontWeight.SemiBold); if (detailId == null) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AppUpdateButton(compact = true) } } }, navigationIcon = { if (detailId != null) IconButton(onClick = { detailId = null }) { Icon(Icons.Outlined.ArrowBack, "返回") } }, actions = {
            if (tab == 0 && detailId == null) {
                IconButton(onClick = { order = OrderEditor("排列卡片", state.cards.map { it.id to it.name.ifBlank { "未命名卡片" } }, repo::reorderCards) }) { Icon(Icons.Outlined.SwapVert, "调整卡片顺序", tint = Quiet) }
                IconButton(onClick = { permissionSheet = true }) { Icon(Icons.Outlined.NotificationsNone, "提醒权限", tint = Quiet) }
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)) },
        bottomBar = { NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0; detailId = null }, icon = { Icon(Icons.Outlined.StickyNote2, null) }, label = { Text("便签") }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFF0F2F8)))
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1; detailId = null }, icon = { Icon(Icons.Outlined.Inventory2, null) }, label = { Text("药品库") }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFF0F2F8)))
            NavigationBarItem(selected = tab == 2, onClick = { tab = 2; detailId = null }, icon = { Icon(Icons.Outlined.History, null) }, label = { Text("用药时长") }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFF0F2F8)))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (detailId == null) {
                    FilledIconButton(
                        onClick = {
                            if (tab == 0) cardEditor = NoteCard(color = state.cards.size % Colors.size)
                            else medicineEditor = Medicine(name = "")
                        },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF829BCD), contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp)
                    ) { Icon(Icons.Outlined.Add, if (tab == 0) "添加卡片" else "添加药品") }
                }
            }
        } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.reminders.any { it.enabled } && permissionProblem != null) TextButton(onClick = { permissionSheet = true }, modifier = Modifier.fillMaxWidth()) { Text("提醒未生效：$permissionProblem", color = Color(0xFFAF6A66), fontSize = 12.sp) }
            val detail = state.medicines.firstOrNull { it.id == detailId }
            if (detailId != null && detail != null) {
                MedicineDetail(detail, { fullImage = it }, { medicineEditor = detail }, {
                    confirm = Confirmation("删除药品档案？", "将删除此药品的全部使用历史和关联首页条目，无法恢复。") { val images = detail.photos(); ReminderScheduler.mutex.withLock { repo.dao.deleteMedicine(detail.id) }; images.forEach { ImageStore(app).removeIfUnused(it, app.database) }; app.scheduler.scheduleAll(); detailId = null }
                })
            } else if (tab == 0) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (state.cards.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(top = 50.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("从一张空白卡片开始", color = Quiet); Spacer(Modifier.height(4.dp)); Text("点击底部右侧 ＋ 添加", color = Quiet, fontSize = 13.sp) } }
                    items(state.cards, key = { it.id }) { card ->
                        NoteCardView(card, state, { cardEditor = card }, { groupEditor = GroupEditor(card.id) }, { groupEditor = GroupEditor(card.id, it) }, { itemEditor = ItemEditor(it.id) }, { itemEditor = ItemEditor(it.groupId, it) }, { fullImage = it }, {
                            confirm = Confirmation("删除卡片？", "删除卡片内的大类和便签安排，并取消提醒；不再出现在其他便签中的药品将自动记录停药日期。") {
                                ReminderScheduler.mutex.withLock { state.groups.filter { it.cardId == card.id }.forEach { app.scheduler.cancel(it.id) }; repo.removeNoteCard(card.id) }
                            }
                        }, { order = OrderEditor("排列大类", state.groups.filter { it.cardId == card.id }.map { it.id to it.name }, repo::reorderGroups) }, { group -> order = OrderEditor("药品与分隔条排序", state.groupEntries(group.id).map { entry -> entry.key to (entry.divider?.let { "分隔条：${it.text}" } ?: state.medicines.firstOrNull { med -> med.id == entry.item?.medicineId }?.name.orEmpty()) }) { keys -> repo.reorderEntries(group.id, keys) } }, permissionProblem, { group -> dividerEditor = NoteDivider(groupId = group.id) }, { dividerEditor = it }, { group, keys -> perform { repo.reorderEntries(group.id, keys) } }, { item, action ->
                            when (action) {
                                "delete" -> confirm = Confirmation("删除药品？", "删除便签中全天所有时段的这个药品，并自动记录停药日期；药品库档案和既往用药记录保留。") {
                                    ReminderScheduler.mutex.withLock {
                                        repo.removeNoteItem(item.id).forEach { groupId ->
                                            app.scheduler.cancel(groupId)
                                            app.scheduler.schedule(groupId)
                                        }
                                    }
                                }
                                "top" -> perform { repo.moveItemToEdge(item.id, true) }
                                "bottom" -> perform { repo.moveItemToEdge(item.id, false) }
                                "star" -> perform { repo.toggleItemStar(item.id) }
                            }
                        })
                    }
                }
            } else if (tab == 1) { MedicineLibrary(state, { detailId = it.id }, { fullImage = it }) }
            else { MedicationDurationPage(state) { periodEditor = it } }
        }
    }
    cardEditor?.let { card -> CardDialog(card, { cardEditor = null }) { updated -> perform { repo.saveCard(updated); cardEditor = null } } }
    groupEditor?.let { editor -> GroupDialog(editor, state.reminders.firstOrNull { it.groupId == editor.group?.id }, { groupEditor = null }, { updated, reminder -> perform { val id = repo.saveGroup(updated, reminder); app.scheduler.schedule(id)?.let { snack.showSnackbar(it) }; groupEditor = null } }, {
        val g = editor.group ?: return@GroupDialog
        confirm = Confirmation("删除大类？", "取消此大类提醒并删除便签安排；不再出现在其他便签中的药品将自动记录停药日期。") { ReminderScheduler.mutex.withLock { app.scheduler.cancel(g.id); repo.removeNoteGroup(g.id) }; groupEditor = null }
    }) }
    dividerEditor?.let { divider -> DividerDialog(divider, { dividerEditor = null }, { text -> perform { repo.saveDivider(divider.copy(text = text)); dividerEditor = null } }, {
        confirm = Confirmation("删除分隔条？", "只删除这条分隔线，药品安排保留。") { repo.dao.deleteDivider(divider.id); dividerEditor = null }
    }) }
    itemEditor?.let { editor -> ItemDialog(editor, state, app, { itemEditor = null }, { selected, name, spec, dose, images, frequency, mealTiming, cardIds -> perform {
        if (editor.item == null) repo.addMedicineSchedule(editor.groupId, selected, name, spec, dose, images.firstOrNull(), encodePhotos(images), frequency, mealTiming, cardIds)
        else repo.saveItem(editor.groupId, editor.item.id, selected, name, spec, dose, images.firstOrNull(), encodePhotos(images), editSelected = true, dailyFrequency = frequency, mealTiming = mealTiming)
        state.medicines.firstOrNull { it.id == selected }?.photos()?.forEach { ImageStore(app).removeIfUnused(it, app.database) }
        app.scheduler.scheduleAll(); itemEditor = null
    } }, {
        val i = editor.item ?: return@ItemDialog
        confirm = Confirmation("删除药品？", "删除便签中全天所有时段的这个药品，并自动记录停药日期；药品库档案和既往用药记录保留。") {
            ReminderScheduler.mutex.withLock {
                repo.removeNoteItem(i.id).forEach { groupId ->
                    app.scheduler.cancel(groupId)
                    app.scheduler.schedule(groupId)
                }
            }
            itemEditor = null
        }
    }) }
    medicineEditor?.let { med -> MedicineDialog(med, app, { medicineEditor = null }) { updated -> perform {
        if (med.id == 0L) repo.addMedicine(updated) else repo.updateMedicine(updated)
        med.photos().forEach { ImageStore(app).removeIfUnused(it, app.database) }
        medicineEditor = null
    } } }
    periodEditor?.let { period -> PeriodDialog(period, { periodEditor = null }, { updated -> perform { repo.savePeriod(updated); periodEditor = null } }, { confirm = Confirmation("删除这段使用记录？", "只删除这段日期记录，不删除药品和首页安排。") { repo.dao.deletePeriod(period.id); periodEditor = null } }) }
    confirm?.let { c -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(c.title) }, text = { Text(c.text) }, confirmButton = { TextButton(onClick = { confirm = null; perform(c.action) }) { Text("删除", color = Color(0xFFB56F71)) } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
    order?.let { o -> OrderDialog(o, { order = null }) { ids -> perform { o.save(ids); order = null } } }
    fullImage?.let { path ->
        val photos = state.medicines.firstOrNull { path in it.photos() }?.photos() ?: listOf(path)
        PhotoViewer(photos, photos.indexOf(path).coerceAtLeast(0)) { fullImage = null }
    }

    if (permissionSheet) AlertDialog(onDismissRequest = { permissionSheet = false }, title = { Text("提醒权限") }, text = { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(permissionProblem ?: "通知与精确闹钟权限已具备。")
        Text("请在手机系统中允许后台运行或自启动。划掉最近任务通常仍可提醒；系统强行停止应用、关机或厂商后台限制可能阻止提醒。重启后需先解锁手机。", fontSize = 13.sp, color = Quiet)
        if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许通知") }
        TextButton(onClick = { app.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("通知设置") }
        if (Build.VERSION.SDK_INT >= 31) TextButton(onClick = { app.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("允许精确闹钟") }
        TextButton(onClick = { app.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("应用后台与电池设置") }
    } }, confirmButton = { TextButton(onClick = { permissionSheet = false; refreshPermissions() }) { Text("完成") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteCardView(card: NoteCard, state: Notebook, edit: () -> Unit, addGroup: () -> Unit, editGroup: (NoteGroup) -> Unit, addItem: (NoteGroup) -> Unit, editItem: (NoteItem) -> Unit, image: (String) -> Unit, delete: () -> Unit, reorder: () -> Unit, reorderItems: (NoteGroup) -> Unit, permissionProblem: String?, addDivider: (NoteGroup) -> Unit, editDivider: (NoteDivider) -> Unit, saveOrder: (NoteGroup, List<Long>) -> Unit, itemAction: (NoteItem, String) -> Unit) {
    val groups = state.groups.filter { it.cardId == card.id }
    val color = Colors[card.color.mod(Colors.size)]
    val accent = Accents[card.color.mod(Colors.size)]
    var menu by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(color, color.copy(alpha = .65f)))).padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (card.name.isNotBlank() || groups.isEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (card.name.isNotBlank()) Text(card.name, Modifier.weight(1f).padding(start = 6.dp), color = accent, fontSize = 14.sp) else Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(width = 36.dp, height = 24.dp)) { Icon(Icons.Outlined.MoreHoriz, "卡片操作", tint = Quiet) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("名称与配色") }, onClick = { menu = false; edit() })
                        DropdownMenuItem(text = { Text("添加大类") }, onClick = { menu = false; addGroup() })
                        DropdownMenuItem(text = { Text("排列大类") }, onClick = { menu = false; reorder() })
                        DropdownMenuItem(text = { Text("删除卡片") }, onClick = { menu = false; delete() })
                    }
                }
            }
            groups.forEach { group ->
                val r = state.reminders.firstOrNull { it.groupId == group.id }
                Row(Modifier.fillMaxWidth().padding(start = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp, 13.dp).clip(RoundedCornerShape(4.dp)).background(accent.copy(alpha = .6f)))
                    Text(group.name, Modifier.weight(1f).clickable { editGroup(group) }.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 13.sp, color = accent, fontWeight = FontWeight.Medium)
                    r?.time()?.let { time -> Text(time + if (r.enabled && permissionProblem == null) "" else if (r.enabled) " · 未生效" else " · 关闭", Modifier.clickable { editGroup(group) }.padding(horizontal = 4.dp, vertical = 2.dp), color = accent, fontSize = 12.sp) }
                    var gm by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { gm = true }, modifier = Modifier.size(width = 32.dp, height = 24.dp)) { Icon(Icons.Outlined.MoreHoriz, "大类操作", tint = Quiet, modifier = Modifier.size(19.dp)) }
                        DropdownMenu(gm, { gm = false }) {
                            DropdownMenuItem(text = { Text("编辑与提醒") }, onClick = { gm = false; editGroup(group) })
                            DropdownMenuItem(text = { Text("药品排序") }, onClick = { gm = false; reorderItems(group) })
                            if (group.id == groups.firstOrNull()?.id && card.name.isBlank()) {
                                DropdownMenuItem(text = { Text("卡片名称与配色") }, onClick = { gm = false; edit() })
                                DropdownMenuItem(text = { Text("添加大类") }, onClick = { gm = false; addGroup() })
                                DropdownMenuItem(text = { Text("排列大类") }, onClick = { gm = false; reorder() })
                                DropdownMenuItem(text = { Text("删除卡片") }, onClick = { gm = false; delete() })
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp)).background(androidx.compose.ui.graphics.lerp(color, Color.White, .85f)).padding(horizontal = 14.dp)) {
                    val groupItems = state.groupEntries(group.id)
                    DragEntryList(groupItems.map { it.key }, { saveOrder(group, it) }) { index, entryKey ->
                        val entry = groupItems.first { it.key == entryKey }
                        val divider = entry.divider
                        if (divider != null) {
                            Row(Modifier.fillMaxWidth().clickable { editDivider(divider) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = accent.copy(alpha = .4f))
                                Text(divider.text, color = accent, fontSize = 12.sp, modifier = Modifier.widthIn(max = 180.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = accent.copy(alpha = .4f))
                            }
                        } else {
                        val item = requireNotNull(entry.item)
                        val med = state.medicines.firstOrNull { it.id == item.medicineId }
                        if (med != null) {
                            SwipeMedicineActions(item.id, med.starred, med.name, androidx.compose.ui.graphics.lerp(color, Color.White, .85f), { itemAction(item, it) }) {
                            Row(Modifier.fillMaxWidth().clickable { editItem(item) }.padding(top = if (index == 0) 6.dp else 3.dp, bottom = if (index == groupItems.lastIndex) 6.dp else 3.dp).heightIn(min = 22.5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(.45f)) {
                                    Text(med.name + if (med.starred) " ⭐" else "", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                }
                                Column(Modifier.weight(.25f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(item.dose, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                                }
                                Box(Modifier.weight(.30f), contentAlignment = Alignment.CenterEnd) {
                                    med.image?.let { path -> AsyncImage(File(path), "查看${med.name}图片", Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable { image(path) }, contentScale = ContentScale.Crop) }
                                }
                            }
                            }
                            Spacer(Modifier.height(if (index == groupItems.lastIndex) 1.dp else 0.5.dp))
                        }
                    }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { addItem(group) }, modifier = Modifier.heightIn(min = 24.dp), contentPadding = PaddingValues(vertical = 4.5.dp, horizontal = 0.dp)) { Text("＋ 添加项目", color = accent.copy(alpha = .72f), fontSize = 13.sp) }
                        TextButton(onClick = { addDivider(group) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.5.dp)) { Text("＋ 分隔条", color = accent.copy(alpha = .72f), fontSize = 12.sp) }
                        TextButton(onClick = { reorderItems(group) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.5.dp)) { Text("排序", color = accent.copy(alpha = .72f), fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(3.5.dp))
            }
            if (groups.isEmpty()) TextButton(onClick = addGroup, modifier = Modifier.heightIn(min = 24.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("＋ 添加大类", color = accent, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun MedicineLibrary(state: Notebook, open: (Medicine) -> Unit, image: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.padding(horizontal = 18.dp)) {
        SoftTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索药品名称") }, leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Quiet) }, singleLine = true, shape = RoundedCornerShape(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 5.dp)) {
            listOf("全部", "当前使用", "已停用").forEachIndexed { i, text -> FilterChip(border = null, selected = filter == i, onClick = { filter = i }, label = { Text(text) }) }
        }
        val meds = state.medicines.filter { med -> med.name.contains(query, ignoreCase = true) && when (filter) { 1 -> state.periods.any { it.medicineId == med.id && it.active }; 2 -> state.periods.none { it.medicineId == med.id && it.active }; else -> true } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.5.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
            if (meds.isEmpty()) item { Text("暂无药品记录", color = Quiet, modifier = Modifier.padding(vertical = 20.dp)) }
            items(meds, key = { it.id }) { med ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFF6F7FA)).clickable { open(med) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(med.name + if (med.starred) " ⭐" else "", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        if (med.usageLine().isNotBlank()) Text(med.usageLine(), color = Quiet, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (state.periods.any { it.medicineId == med.id && it.active }) "当前使用" else "已停用", color = Quiet, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    med.image?.let { path -> AsyncImage(File(path), "查看图片", Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).clickable { image(path) }, contentScale = ContentScale.Crop) }
                }
                }
            }
        }
    }
}

@Composable
private fun MedicineDetail(med: Medicine, image: (String) -> Unit, edit: () -> Unit, delete: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 22.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { Text(med.name + if (med.starred) " ⭐" else "", fontSize = 25.sp, fontWeight = FontWeight.Bold); if (med.usageLine().isNotBlank()) Text(med.usageLine(), color = Quiet, fontSize = 13.sp) }
        if (med.photos().isNotEmpty()) item { PhotoStrip(med.photos(), image) }
        item { TextButton(onClick = edit) { Text("编辑药品资料") } }
        item { TextButton(onClick = delete) { Text("删除药品档案", color = Color(0xFFB56F71)) } }
    }
}

@Composable
private fun MedicationDurationPage(state: Notebook, period: (UsePeriod) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val medicines = state.medicines.filter { it.name.contains(query, ignoreCase = true) }
    Column(Modifier.padding(horizontal = 18.dp)) {
        SoftTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索药品名称") }, leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Quiet) }, singleLine = true, shape = RoundedCornerShape(18.dp))
        Spacer(Modifier.height(7.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (medicines.isEmpty()) item { Text("暂无药品记录", color = Quiet, modifier = Modifier.padding(vertical = 20.dp)) }
            items(medicines, key = { it.id }) { med ->
                val periods = state.periods.filter { it.medicineId == med.id }.sortedWith(compareByDescending<UsePeriod> { it.active }.thenByDescending { it.start ?: "" }.thenByDescending { it.id })
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFF6F7FA)).padding(horizontal = 15.dp, vertical = 11.dp)) {
                    Text(med.name + if (med.starred) " ⭐" else "", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    if (periods.isEmpty()) Text("暂无记录", color = Quiet, fontSize = 13.sp, modifier = Modifier.padding(vertical = 7.dp))
                    periods.forEachIndexed { index, value ->
                        if (index > 0) HorizontalDivider(color = Color(0xFFE5E7EC))
                        Row(Modifier.fillMaxWidth().clickable { period(value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(value.start ?: "未填", Modifier.weight(.38f), fontSize = 13.sp, maxLines = 1)
                            Text(if (value.active) "至今" else value.end ?: "未填", Modifier.weight(.38f), fontSize = 13.sp, maxLines = 1)
                            Text(value.durationDays()?.let { "${it}天" } ?: "—", Modifier.weight(.24f), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
                        }
                    }
                    TextButton(onClick = { period(UsePeriod(medicineId = med.id, active = false)) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) { Text("＋ 添加使用记录", fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun EditorDialog(title: String, dismiss: () -> Unit, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title, fontSize = 20.sp) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }, confirmButton = { TextButton(onClick = save) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun CardDialog(card: NoteCard, dismiss: () -> Unit, save: (NoteCard) -> Unit) {
    var name by remember(card.id) { mutableStateOf(card.name) }
    var color by remember(card.id) { mutableIntStateOf(card.color) }
    EditorDialog(if (card.id == 0L) "添加卡片" else "编辑卡片", dismiss, { save(card.copy(name = name.trim(), color = color)) }) {
        SoftTextField(name, { name = it }, label = { Text("名称（可不填）") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Colors.forEachIndexed { i, c -> Box(Modifier.size(35.dp).clip(RoundedCornerShape(12.dp)).background(c).clickable { color = i }, contentAlignment = Alignment.Center) { if (i == color) Icon(Icons.Outlined.Check, "已选配色", tint = Accents[i]) } } }
    }
}

@Composable
private fun GroupDialog(editor: GroupEditor, existing: Reminder?, dismiss: () -> Unit, save: (NoteGroup, Reminder) -> Unit, delete: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(editor.group?.name ?: "") }
    var hour by remember { mutableStateOf(existing?.hour) }
    var minute by remember { mutableStateOf(existing?.minute) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    EditorDialog(if (editor.group == null) "添加大类" else "编辑大类", dismiss, {
        if (name.isBlank()) error = "请填写大类名称" else save(editor.group?.copy(name = name.trim()) ?: NoteGroup(cardId = editor.cardId, name = name.trim()), Reminder(editor.group?.id ?: 0, hour, minute, enabled))
    }) {
        SoftTextField(name, { name = it; error = null }, label = { Text("大类名称") }, modifier = Modifier.fillMaxWidth(), isError = error != null)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m; enabled = true }, hour ?: 0, minute ?: 0, true).show() }) { Text(if (hour != null && minute != null) "%02d:%02d".format(hour, minute) else "设置时间（可不填）") }
            if (hour != null) TextButton(onClick = { hour = null; minute = null; enabled = false }) { Text("清除") }
        }
        if (hour != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("每日提醒", Modifier.weight(1f)); Switch(enabled, { enabled = it }, colors = SwitchDefaults.colors(uncheckedBorderColor = Color.Transparent)) }
        if (editor.group != null) TextButton(onClick = delete) { Text("删除大类", color = Color(0xFFB56F71)) }
    }
}

@Composable
private fun ItemDialog(editor: ItemEditor, state: Notebook, app: MedApplication, dismiss: () -> Unit, save: (Long?, String, String, String, List<String>, String, String, List<Long>) -> Unit, delete: () -> Unit) {
    val original = state.medicines.firstOrNull { it.id == editor.item?.medicineId }
    var selectedId by remember { mutableStateOf(original?.id) }
    val selected = state.medicines.firstOrNull { it.id == selectedId }
    var name by remember { mutableStateOf(original?.name ?: "") }
    var spec by remember { mutableStateOf(original?.specification ?: "") }
    var dose by remember { mutableStateOf(editor.item?.dose ?: "") }
    var frequency by remember { mutableStateOf(original?.dailyFrequency ?: "") }
    var mealTiming by remember { mutableStateOf(original?.mealTiming ?: "") }
    val sourceCardId = state.groups.firstOrNull { it.id == editor.groupId }?.cardId
    var selectedCardIds by remember(editor.groupId) { mutableStateOf(sourceCardId?.let(::setOf) ?: emptySet()) }
    var images by remember { mutableStateOf(original?.photos() ?: emptyList()) }
    var chooser by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val imported = remember { mutableListOf<String>() }
    DisposableEffect(Unit) { onDispose { app.scope.launch { imported.forEach { ImageStore(app).removeIfUnused(it, app.database) } } } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch { loading = true; try { for (uri in uris) { val path = ImageStore(app).import(uri).also { imported.add(it) }; images = images + path } } catch (e: Exception) { error = e.message ?: "图片读取失败" } finally { loading = false } }
    }
    EditorDialog(if (editor.item == null) "添加项目" else "编辑项目", dismiss, {
        if (loading) error = "正在保存图片" else if (name.isBlank()) error = "请填写名称" else {
            val count = frequency.toIntOrNull()
            if (editor.item == null && (count == 1 || count == 2) && selectedCardIds.size != count) error = "请选择$count 张服用卡片"
            else save(selectedId, name, spec, dose, images, frequency, mealTiming, selectedCardIds.toList())
        }
    }) {
        Row { TextButton(onClick = { chooser = true }) { Text("从药品库选择") }; if (selectedId != null) TextButton(onClick = { selectedId = null; name = ""; spec = ""; dose = ""; images = emptyList(); frequency = ""; mealTiming = "" }) { Text("新建药品") } }
        SoftTextField(name, { name = it; error = null }, label = { Text("药品或护理名称") }, modifier = Modifier.fillMaxWidth())
        SoftTextField(spec, { spec = it }, label = { Text("规格（可不填）") }, placeholder = { Text("如 20mg") }, modifier = Modifier.fillMaxWidth())
        if (selected != null && state.items.count { it.medicineId == selected.id } > 1) Text("药名、规格和图片会同步到引用此药品的条目，用量单独保存。", color = Quiet, fontSize = 12.sp)
        SoftTextField(dose, { dose = it }, label = { Text("每次用量") }, placeholder = { Text("如 1片、2粒、1次") }, modifier = Modifier.fillMaxWidth())
        FrequencyField(frequency, { frequency = it })
        MealTimingField(mealTiming, { mealTiming = it })
        if (editor.item == null && (frequency.toIntOrNull() == 1 || frequency.toIntOrNull() == 2)) {
            val required = requireNotNull(frequency.toIntOrNull())
            Text("选择服用卡片（选$required 张）", fontSize = 13.sp, color = Quiet)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.cards.sortedWith(compareBy<NoteCard> { it.position }.thenBy { it.id }).forEach { card ->
                    val selectedCard = card.id in selectedCardIds
                    FilterChip(selected = selectedCard, onClick = {
                        selectedCardIds = if (selectedCard) selectedCardIds - card.id else if (selectedCardIds.size < required) selectedCardIds + card.id else selectedCardIds
                        error = null
                    }, label = { Text(card.name.ifBlank { "未命名卡片" }) }, border = null)
                }
            }
        } else if (editor.item == null && frequency == "3") {
            Text("保存后自动加入早、中、晚卡片，并按${mealTiming.ifBlank { "当前分类" }}放置", fontSize = 12.sp, color = Quiet)
        }
        PhotoEditor(images, loading, true,
            { launcher.launch("image/*") }, { path -> images = images - path })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (editor.item != null) TextButton(onClick = delete) { Text("删除首页条目", color = Color(0xFFB56F71)) }
    }
    if (chooser) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { chooser = false }, title = { Text("选择药品") }, text = { Column { SoftTextField(query, { query = it }, label = { Text("搜索名称") }); LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(state.medicines.filter { it.name.contains(query, ignoreCase = true) }, key = { it.id }) { med -> TextButton(onClick = { selectedId = med.id; name = med.name; spec = med.specification; images = med.photos(); dose = med.defaultDose; frequency = med.dailyFrequency; mealTiming = med.mealTiming; chooser = false }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text(med.name + if (med.starred) " ⭐" else ""); if (med.usageLine().isNotBlank()) Text(med.usageLine(), fontSize = 12.sp, color = Quiet) } } }
        } } }, confirmButton = { TextButton(onClick = { chooser = false }) { Text("取消") } })
    }
}

@Composable
private fun MedicineDialog(med: Medicine, app: MedApplication, dismiss: () -> Unit, save: (Medicine) -> Unit) {
    var name by remember { mutableStateOf(med.name) }
    var spec by remember { mutableStateOf(med.specification) }
    var dose by remember { mutableStateOf(med.defaultDose) }
    var frequency by remember { mutableStateOf(med.dailyFrequency) }
    var mealTiming by remember { mutableStateOf(med.mealTiming) }
    var images by remember { mutableStateOf(med.photos()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val imported = remember { mutableListOf<String>() }
    DisposableEffect(Unit) { onDispose { app.scope.launch { imported.forEach { ImageStore(app).removeIfUnused(it, app.database) } } } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty()) scope.launch { loading = true; try { for (uri in uris) { val path = ImageStore(app).import(uri).also { imported.add(it) }; images = images + path } } catch (e: Exception) { error = e.message ?: "图片读取失败" } finally { loading = false } } }
    EditorDialog(if (med.id == 0L) "添加药品" else "编辑药品资料", dismiss, { if (name.isBlank()) error = "请填写名称" else if (!loading) save(med.copy(name = name, specification = spec, defaultDose = dose, dailyFrequency = frequency, mealTiming = mealTiming, image = images.firstOrNull(), imagesJson = encodePhotos(images))) }) {
        Text("名称、规格和图片会同步用于引用此药品的首页条目。各条目的用量保持独立。", fontSize = 12.sp, color = Quiet)
        SoftTextField(name, { name = it }, label = { Text("药品名称") }, modifier = Modifier.fillMaxWidth())
        SoftTextField(spec, { spec = it }, label = { Text("规格（可不填）") }, modifier = Modifier.fillMaxWidth())
        SoftTextField(dose, { dose = it }, label = { Text("复用时带出的用量（可不填）") }, modifier = Modifier.fillMaxWidth())
        FrequencyField(frequency, { frequency = it })
        MealTimingField(mealTiming, { mealTiming = it })
        PhotoEditor(images, loading, true, { launcher.launch("image/*") }, { path -> images = images - path })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PeriodDialog(period: UsePeriod, dismiss: () -> Unit, save: (UsePeriod) -> Unit, delete: () -> Unit) {
    var start by remember { mutableStateOf(period.start ?: "") }
    var end by remember { mutableStateOf(period.end ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    EditorDialog(if (period.id == 0L) "添加早期使用记录" else "调整使用日期", dismiss, {
        val a = start.ifBlank { null }; val b = if (period.active) null else end.ifBlank { null }
        try {
            if (period.id == 0L) require(a != null && b != null) { "请选择开始和结束日期" }
            validateDates(a, b)
            save(period.copy(start = a, end = b))
        } catch (e: Exception) { error = e.message ?: "停用日期不能早于开始日期" }
    }) {
        if (period.id == 0L) Text("仅补录过去的用药时段，不改变当前用药状态", fontSize = 12.sp, color = Quiet)
        DateSelectionField(
            value = start,
            label = "开始日期",
            optional = period.id != 0L,
            maxDate = end.takeIf { !period.active && it.isNotBlank() }?.let { LocalDate.parse(it) },
            change = { start = it; error = null }
        )
        if (!period.active) DateSelectionField(
            value = end,
            label = "结束日期",
            optional = period.id != 0L,
            minDate = start.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
            change = { end = it; error = null }
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (period.id != 0L) TextButton(onClick = delete) { Text("删除这段记录", color = Color(0xFFB56F71)) }
    }
}

@Composable
private fun DateSelectionField(
    value: String,
    label: String,
    optional: Boolean,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
    change: (String) -> Unit
) {
    val context = LocalContext.current
    val selected = remember(value) { value.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) } }
    fun openPicker() {
        val initial = selected ?: LocalDate.now()
        DatePickerDialog(
            context,
            { _, year, month, day -> change("%04d-%02d-%02d".format(year, month + 1, day)) },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        ).apply {
            minDate?.let { datePicker.minDate = it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            maxDate?.let { datePicker.maxDate = it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        }.show()
    }
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(onClick = ::openPicker)
            .semantics { contentDescription = "选择$label" }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 7.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label + if (optional) "（可不选）" else "", fontSize = 12.sp, color = Quiet)
                Text(selected?.toString() ?: "选择日期", fontSize = 17.sp, color = if (selected == null) Quiet else Ink)
            }
            if (optional && selected != null) {
                IconButton(
                    onClick = { change("") },
                    modifier = Modifier.semantics { contentDescription = "清除$label" }
                ) { Icon(Icons.Outlined.Close, null, tint = Quiet) }
            } else {
                Icon(Icons.Outlined.CalendarMonth, null, tint = Quiet, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun OrderDialog(order: OrderEditor, dismiss: () -> Unit, save: (List<Long>) -> Unit) {
    var draft by remember(order) { mutableStateOf(order.rows.map { it.first }) }
    EditorDialog(order.title, dismiss, { save(draft) }) {
        Text("长按条目上下拖动，可多次调整，完成后点保存", color = Quiet, fontSize = 12.sp)
        DragEntryList(draft, { draft = it }) { _, id ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(order.rows.first { it.first == id }.second, Modifier.weight(1f))
                Icon(Icons.Outlined.DragHandle, "长按拖动", tint = Quiet)
            }
        }
    }
}

@Composable
private fun DividerDialog(divider: NoteDivider, dismiss: () -> Unit, save: (String) -> Unit, delete: () -> Unit) {
    var text by remember(divider.id) { mutableStateOf(divider.text) }
    var error by remember { mutableStateOf(false) }
    EditorDialog(if (divider.id == 0L) "添加分隔条" else "编辑分隔条", dismiss, {
        if (text.isBlank()) error = true else save(text.trim())
    }) {
        SoftTextField(text, { text = it; error = false }, label = { Text("分隔文字") }, modifier = Modifier.fillMaxWidth(), isError = error)
        if (error) Text("请填写分隔文字", color = MaterialTheme.colorScheme.error)
        if (divider.id != 0L) TextButton(onClick = delete) { Text("删除分隔条", color = Color(0xFFB56F71)) }
    }
}


/** Stable parent coordinates keep the finger attached while keyed children move. */
@Composable
internal fun DragEntryList(source: List<Long>, save: (List<Long>) -> Unit, content: @Composable (Int, Long) -> Unit) {
    var ordered by remember { mutableStateOf(source) }
    var dragging by remember { mutableStateOf<Long?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    val bounds = remember { mutableStateMapOf<Long, Pair<Float, Float>>() }
    val latestSave by rememberUpdatedState(save)
    val latestSource by rememberUpdatedState(source)
    // Source membership must be honored in the current composition, before effects run.
    // A deleted key must never reach content(), whose model already excludes it.
    val sameMembers = ordered.size == source.size && ordered.toSet() == source.toSet()
    val visibleOrder = if (dragging != null && sameMembers) ordered else source
    LaunchedEffect(source) {
        ordered = source
        dragging = null
        bounds.keys.retainAll(source.toSet())
    }
    Column(Modifier.fillMaxWidth().pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                ordered = latestSource
                val id = latestSource.firstOrNull { id -> bounds[id]?.let { point.y >= it.first && point.y < it.second } == true }
                dragging = id
                fingerY = point.y
                grabOffset = point.y - (bounds[id]?.first ?: point.y)
            },
            onDrag = { change, amount ->
                val id = dragging
                if (id != null && ordered.size == latestSource.size && ordered.toSet() == latestSource.toSet()) {
                    change.consume()
                    fingerY += amount.y
                    val target = ordered.firstOrNull { other -> other != id && bounds[other]?.let { fingerY >= it.first && fingerY < it.second } == true }
                    if (target != null) {
                        ordered = ordered.toMutableList().apply { val from = indexOf(id); val to = indexOf(target); add(to, removeAt(from)) }
                    }
                }
            },
            onDragEnd = {
                if (dragging != null && ordered != latestSource && ordered.size == latestSource.size && ordered.toSet() == latestSource.toSet()) latestSave(ordered)
                dragging = null
            },
            onDragCancel = { dragging = null; ordered = latestSource }
        )
    }) {
        visibleOrder.forEachIndexed { index, id ->
            key(id) {
                Box(Modifier.fillMaxWidth().onGloballyPositioned { c ->
                    val top = c.positionInParent().y
                    bounds[id] = top to top + c.size.height
                }.zIndex(if (dragging == id) 1f else 0f).graphicsLayer {
                    translationY = if (dragging == id) fingerY - grabOffset - (bounds[id]?.first ?: 0f) else 0f
                    alpha = if (dragging == id) .8f else 1f
                }) { content(index, id) }
            }
        }
    }
}


/** A short horizontal swipe settles to an endpoint; long-press sorting remains separate. */
@Composable internal fun SwipeMedicineActions(id: Long, starred: Boolean, name: String, background: Color, action: (String) -> Unit, content: @Composable () -> Unit) {
    val reveal = with(LocalDensity.current) { 200.dp.toPx() }
    val offset = remember(id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var opened by remember(id) { mutableStateOf(false) }
    var distance by remember(id) { mutableFloatStateOf(0f) }
    fun settle() { scope.launch { offset.animateTo(if (opened) -reveal else 0f, tween(180)) } }
    val gestures = Modifier
            .pointerInput(id, reveal) {
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f; scope.launch { offset.stop() } },
                    onHorizontalDrag = { change, amount ->
                        change.consume(); distance += amount
                        scope.launch { offset.snapTo((offset.value + amount).coerceIn(-reveal, 0f)) }
                    },
                    onDragEnd = { if (distance != 0f) opened = distance < 0f; settle() },
                    onDragCancel = { settle() }
                )
            }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(gestures).semantics { contentDescription = "药品条目：$name" }) {
        if (offset.value < 0f) Row(Modifier.align(Alignment.CenterEnd).width(200.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
            val actions = listOf(
                Triple("top", "置顶", Icons.Outlined.VerticalAlignTop),
                Triple("bottom", "置底", Icons.Outlined.VerticalAlignBottom),
                Triple("star", if (starred) "取消标星" else "标星", if (starred) Icons.Outlined.Star else Icons.Outlined.StarBorder),
                Triple("delete", "删除", Icons.Outlined.Delete)
            )
            val colors = listOf(Color(0xFF24BB65), Color(0xFF19ACE3), Color(0xFFE9B90A), Color(0xFFF04B4D))
            actions.forEachIndexed { index, (key, label, icon) ->
                IconButton(onClick = { opened = false; settle(); action(key) }, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(40.dp).background(colors[index], CircleShape), contentAlignment = Alignment.Center) {
                        Icon(icon, label, tint = Color.White, modifier = Modifier.size(25.dp))
                    }
                }
            }
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.fillMaxWidth().background(background)) { content() }
    }
}

@Composable
private fun FrequencyField(value: String, change: (String) -> Unit) {
    SoftTextField(value, { change(it.filter { ch -> ch in '0'..'9' }) }, label = { Text("每天次数（可不填）") }, placeholder = { Text("如 1、2、3") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MealTimingField(value: String, change: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("服用时间", fontSize = 13.sp, color = Quiet)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("" to "未设置", "餐前" to "餐前", "餐后" to "餐后").forEach { (key, label) ->
                FilterChip(selected = value == key, onClick = { change(key) }, label = { Text(label) }, border = null)
            }
        }
    }
}
