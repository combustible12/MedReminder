package com.combustible12.medreminder

import android.Manifest
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
        topBar = { TopAppBar(title = { Text(if (detailId != null) "药品资料" else if (tab == 0) "用药便签" else "药品库", fontSize = 23.sp, fontWeight = FontWeight.SemiBold) }, navigationIcon = { if (detailId != null) IconButton(onClick = { detailId = null }) { Icon(Icons.Outlined.ArrowBack, "返回") } }, actions = {
            if (tab == 0 && detailId == null) {
                IconButton(onClick = { order = OrderEditor("排列卡片", state.cards.map { it.id to it.name.ifBlank { "未命名卡片" } }, repo::reorderCards) }) { Icon(Icons.Outlined.SwapVert, "调整卡片顺序", tint = Quiet) }
                IconButton(onClick = { permissionSheet = true }) { Icon(Icons.Outlined.NotificationsNone, "提醒权限", tint = Quiet) }
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)) },
        bottomBar = { NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0; detailId = null }, icon = { Icon(Icons.Outlined.StickyNote2, null) }, label = { Text("便签") }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFF0F2F8)))
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1; detailId = null }, icon = { Icon(Icons.Outlined.Inventory2, null) }, label = { Text("药品库") }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFF0F2F8)))
        } },
        floatingActionButton = { if (tab == 0 && detailId == null) FloatingActionButton(onClick = { cardEditor = NoteCard(color = state.cards.size % Colors.size) }, containerColor = Color(0xFF829BCD), contentColor = Color.White, shape = RoundedCornerShape(22.dp)) { Icon(Icons.Outlined.Add, "添加卡片") } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.reminders.any { it.enabled } && permissionProblem != null) TextButton(onClick = { permissionSheet = true }, modifier = Modifier.fillMaxWidth()) { Text("提醒未生效：$permissionProblem", color = Color(0xFFAF6A66), fontSize = 12.sp) }
            val detail = state.medicines.firstOrNull { it.id == detailId }
            if (detailId != null && detail != null) {
                MedicineDetail(detail, state.periods.filter { it.medicineId == detail.id }, { fullImage = it }, { medicineEditor = detail }, { periodEditor = it }, {
                    confirm = Confirmation("删除药品档案？", "将删除此药品的全部使用历史和关联首页条目，无法恢复。") { val image = detail.image; ReminderScheduler.mutex.withLock { repo.dao.deleteMedicine(detail.id) }; ImageStore(app).removeIfUnused(image, app.database); app.scheduler.scheduleAll(); detailId = null }
                })
            } else if (tab == 0) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.cards.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(top = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("从一张空白卡片开始", color = Quiet); Spacer(Modifier.height(8.dp)); Text("点击右下角 ＋ 添加", color = Quiet, fontSize = 13.sp) } }
                    items(state.cards, key = { it.id }) { card ->
                        NoteCardView(card, state, { cardEditor = card }, { groupEditor = GroupEditor(card.id) }, { groupEditor = GroupEditor(card.id, it) }, { itemEditor = ItemEditor(it.id) }, { itemEditor = ItemEditor(it.groupId, it) }, { fullImage = it }, {
                            confirm = Confirmation("删除卡片？", "删除卡片内的大类和首页安排，并取消提醒。药品库和使用历史会保留。") {
                                ReminderScheduler.mutex.withLock { state.groups.filter { it.cardId == card.id }.forEach { app.scheduler.cancel(it.id) }; repo.dao.deleteCard(card.id) }
                            }
                        }, { order = OrderEditor("排列大类", state.groups.filter { it.cardId == card.id }.map { it.id to it.name }, repo::reorderGroups) }, { group -> order = OrderEditor("排列条目", state.items.filter { it.groupId == group.id }.map { it.id to (state.medicines.firstOrNull { med -> med.id == it.medicineId }?.name ?: "") }, repo::reorderItems) }, permissionProblem)
                    }
                }
            } else { MedicineLibrary(state, { detailId = it.id }, { fullImage = it }) }
        }
    }
    cardEditor?.let { card -> CardDialog(card, { cardEditor = null }) { updated -> perform { repo.saveCard(updated); cardEditor = null } } }
    groupEditor?.let { editor -> GroupDialog(editor, state.reminders.firstOrNull { it.groupId == editor.group?.id }, { groupEditor = null }, { updated, reminder -> perform { val id = repo.saveGroup(updated, reminder); app.scheduler.schedule(id)?.let { snack.showSnackbar(it) }; groupEditor = null } }, {
        val g = editor.group ?: return@GroupDialog
        confirm = Confirmation("删除大类？", "取消此大类提醒并删除首页安排，药品库和历史记录会保留。") { ReminderScheduler.mutex.withLock { app.scheduler.cancel(g.id); repo.dao.deleteGroup(g.id) }; groupEditor = null }
    }) }
    itemEditor?.let { editor -> ItemDialog(editor, state, app, { itemEditor = null }, { selected, name, spec, dose, image -> perform { repo.saveItem(editor.groupId, editor.item?.id, selected, name, spec, dose, image); app.scheduler.schedule(editor.groupId); itemEditor = null } }, { med -> medicineEditor = med }, {
        val i = editor.item ?: return@ItemDialog
        confirm = Confirmation("删除首页条目？", "只移除此处安排，药品库和使用历史会保留。") { repo.dao.deleteItem(i.id); app.scheduler.cancel(i.groupId); app.scheduler.schedule(i.groupId); itemEditor = null }
    }) }
    medicineEditor?.let { med -> MedicineDialog(med, app, { medicineEditor = null }) { updated -> perform { repo.updateMedicine(updated); ImageStore(app).removeIfUnused(med.image, app.database); medicineEditor = null } } }
    periodEditor?.let { period -> PeriodDialog(period, { periodEditor = null }, { updated -> perform { repo.savePeriod(updated); periodEditor = null } }, { confirm = Confirmation("删除这段使用记录？", "只删除这段日期记录，不删除药品和首页安排。") { repo.dao.deletePeriod(period.id); periodEditor = null } }) }
    confirm?.let { c -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(c.title) }, text = { Text(c.text) }, confirmButton = { TextButton(onClick = { confirm = null; perform(c.action) }) { Text("删除", color = Color(0xFFB56F71)) } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
    order?.let { o -> OrderDialog(o, { order = null }) { ids -> perform { o.save(ids); order = null } } }
    fullImage?.let { path -> Dialog(onDismissRequest = { fullImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(color = Color.White, modifier = Modifier.fillMaxSize()) { Box { AsyncImage(File(path), "药品大图", modifier = Modifier.fillMaxSize().padding(24.dp), contentScale = ContentScale.Fit); IconButton(onClick = { fullImage = null }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Icon(Icons.Outlined.Close, "关闭大图") } } } } }
    if (permissionSheet) AlertDialog(onDismissRequest = { permissionSheet = false }, title = { Text("提醒权限") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(permissionProblem ?: "通知与精确闹钟权限已具备。")
        Text("请在手机系统中允许后台运行或自启动。划掉最近任务通常仍可提醒；系统强行停止应用、关机或厂商后台限制可能阻止提醒。重启后需先解锁手机。", fontSize = 13.sp, color = Quiet)
        if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许通知") }
        TextButton(onClick = { app.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("通知设置") }
        if (Build.VERSION.SDK_INT >= 31) TextButton(onClick = { app.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("允许精确闹钟") }
        TextButton(onClick = { app.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("应用后台与电池设置") }
    } }, confirmButton = { TextButton(onClick = { permissionSheet = false; refreshPermissions() }) { Text("完成") } })
}

@Composable
private fun NoteCardView(card: NoteCard, state: Notebook, edit: () -> Unit, addGroup: () -> Unit, editGroup: (NoteGroup) -> Unit, addItem: (NoteGroup) -> Unit, editItem: (NoteItem) -> Unit, image: (String) -> Unit, delete: () -> Unit, reorder: () -> Unit, reorderItems: (NoteGroup) -> Unit, permissionProblem: String?) {
    val groups = state.groups.filter { it.cardId == card.id }
    val color = Colors[card.color.mod(Colors.size)]
    val accent = Accents[card.color.mod(Colors.size)]
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(color, color.copy(alpha = .65f)))).padding(12.dp)) {
        if (card.name.isNotBlank() || groups.isEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (card.name.isNotBlank()) Text(card.name, Modifier.weight(1f).padding(start = 6.dp), color = accent, fontSize = 14.sp) else Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.MoreHoriz, "卡片操作", tint = Quiet) }
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
                Text(group.name, Modifier.weight(1f).clickable { editGroup(group) }.padding(horizontal = 9.dp, vertical = 10.dp), fontSize = 13.sp, color = accent, fontWeight = FontWeight.Medium)
                r?.time()?.let { time -> Text(time + if (r.enabled && permissionProblem == null) "" else if (r.enabled) " · 未生效" else " · 关闭", Modifier.clickable { editGroup(group) }.padding(4.dp), color = accent, fontSize = 12.sp) }
                var gm by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { gm = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreHoriz, "大类操作", tint = Quiet, modifier = Modifier.size(19.dp)) }
                    DropdownMenu(gm, { gm = false }) {
                        DropdownMenuItem(text = { Text("编辑与提醒") }, onClick = { gm = false; editGroup(group) })
                        DropdownMenuItem(text = { Text("排列条目") }, onClick = { gm = false; reorderItems(group) })
                        if (group.id == groups.firstOrNull()?.id && card.name.isBlank()) {
                            DropdownMenuItem(text = { Text("卡片名称与配色") }, onClick = { gm = false; edit() })
                            DropdownMenuItem(text = { Text("添加大类") }, onClick = { gm = false; addGroup() })
                            DropdownMenuItem(text = { Text("排列大类") }, onClick = { gm = false; reorder() })
                            DropdownMenuItem(text = { Text("删除卡片") }, onClick = { gm = false; delete() })
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp)).background(Color.White.copy(alpha = .64f)).padding(horizontal = 14.dp)) {
                state.items.filter { it.groupId == group.id }.forEach { item ->
                    val med = state.medicines.firstOrNull { it.id == item.medicineId }
                    if (med != null) {
                        Row(Modifier.fillMaxWidth().clickable { editItem(item) }.padding(vertical = 12.dp).heightIn(min = 45.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(.45f)) {
                                Text(med.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                if (med.specification.isNotBlank()) Text(med.specification, fontSize = 12.sp, color = Quiet, modifier = Modifier.padding(top = 3.dp))
                            }
                            Box(Modifier.weight(.25f), contentAlignment = Alignment.Center) {
                                Text(item.dose, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                            }
                            Box(Modifier.weight(.30f), contentAlignment = Alignment.CenterEnd) {
                                med.image?.let { path -> AsyncImage(File(path), "查看${med.name}图片", Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable { image(path) }, contentScale = ContentScale.Crop) }
                            }
                        }
                        HorizontalDivider(color = Ink.copy(alpha = .055f))
                    }
                }
                TextButton(onClick = { addItem(group) }, contentPadding = PaddingValues(vertical = 9.dp, horizontal = 0.dp)) { Text("＋ 添加项目", color = accent.copy(alpha = .72f), fontSize = 13.sp) }
            }
            Spacer(Modifier.height(7.dp))
        }
        if (groups.isEmpty()) TextButton(onClick = addGroup) { Text("＋ 添加大类", color = accent, fontSize = 13.sp) }
    }
}

@Composable
private fun MedicineLibrary(state: Notebook, open: (Medicine) -> Unit, image: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.padding(horizontal = 18.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索药品名称") }, leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Quiet) }, singleLine = true, shape = RoundedCornerShape(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
            listOf("全部", "当前使用", "已停用").forEachIndexed { i, text -> FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(text) }) }
        }
        val meds = state.medicines.filter { med -> med.name.contains(query, ignoreCase = true) && when (filter) { 1 -> state.periods.any { it.medicineId == med.id && it.active }; 2 -> state.periods.none { it.medicineId == med.id && it.active }; else -> true } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (meds.isEmpty()) item { Text("暂无药品记录", color = Quiet, modifier = Modifier.padding(vertical = 40.dp)) }
            items(meds, key = { it.id }) { med ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFF6F7FA)).clickable { open(med) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(med.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        if (med.specification.isNotBlank()) Text(med.specification, color = Quiet, fontSize = 12.sp)
                        Text(if (state.periods.any { it.medicineId == med.id && it.active }) "当前使用" else "已停用", color = Quiet, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    med.image?.let { path -> AsyncImage(File(path), "查看图片", Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).clickable { image(path) }, contentScale = ContentScale.Crop) }
                }
            }
        }
    }
}

@Composable
private fun MedicineDetail(med: Medicine, periods: List<UsePeriod>, image: (String) -> Unit, edit: () -> Unit, period: (UsePeriod) -> Unit, delete: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(med.name, fontSize = 25.sp, fontWeight = FontWeight.Bold); if (med.specification.isNotBlank()) Text(med.specification, color = Quiet); if (med.defaultDose.isNotBlank()) Text("记录用量：${med.defaultDose}", fontSize = 13.sp, color = Quiet) }
        med.image?.let { path -> item { AsyncImage(File(path), "药品图片", Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(18.dp)).clickable { image(path) }, contentScale = ContentScale.Fit) } }
        item { TextButton(onClick = edit) { Text("编辑药品资料") } }
        item { Text("使用记录", fontWeight = FontWeight.SemiBold); Text("日期可留空，重新使用时添加新记录", fontSize = 12.sp, color = Quiet) }
        items(periods, key = { it.id }) { p ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF5F6FA)).clickable { period(p) }.padding(16.dp)) {
                Text(if (p.active) "当前使用" else "已停用", fontWeight = FontWeight.Medium)
                if (p.start != null || p.end != null) Text(if (p.active) "${p.start ?: "开始日期未填"} 至今" else "${p.start ?: "开始日期未填"} 至 ${p.end ?: "停用日期未填"}", fontSize = 13.sp, color = Quiet)
            }
        }
        item { TextButton(onClick = { period(UsePeriod(medicineId = med.id)) }) { Text("＋ 添加使用记录") } }
        item { TextButton(onClick = delete) { Text("删除药品档案", color = Color(0xFFB56F71)) } }
    }
}

@Composable
private fun EditorDialog(title: String, dismiss: () -> Unit, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title, fontSize = 20.sp) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }, confirmButton = { TextButton(onClick = save) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun CardDialog(card: NoteCard, dismiss: () -> Unit, save: (NoteCard) -> Unit) {
    var name by remember(card.id) { mutableStateOf(card.name) }
    var color by remember(card.id) { mutableIntStateOf(card.color) }
    EditorDialog(if (card.id == 0L) "添加卡片" else "编辑卡片", dismiss, { save(card.copy(name = name.trim(), color = color)) }) {
        OutlinedTextField(name, { name = it }, label = { Text("名称（可不填）") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Colors.forEachIndexed { i, c -> Box(Modifier.size(35.dp).clip(RoundedCornerShape(12.dp)).background(c).then(if (i == color) Modifier.border(2.dp, Accents[i], RoundedCornerShape(12.dp)) else Modifier).clickable { color = i }) } }
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
        OutlinedTextField(name, { name = it; error = null }, label = { Text("大类名称") }, modifier = Modifier.fillMaxWidth(), isError = error != null)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m; enabled = true }, hour ?: 0, minute ?: 0, true).show() }) { Text(if (hour != null && minute != null) "%02d:%02d".format(hour, minute) else "设置时间（可不填）") }
            if (hour != null) TextButton(onClick = { hour = null; minute = null; enabled = false }) { Text("清除") }
        }
        if (hour != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("每日提醒", Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
        if (editor.group != null) TextButton(onClick = delete) { Text("删除大类", color = Color(0xFFB56F71)) }
    }
}

@Composable
private fun ItemDialog(editor: ItemEditor, state: Notebook, app: MedApplication, dismiss: () -> Unit, save: (Long?, String, String, String, String?) -> Unit, editMedicine: (Medicine) -> Unit, delete: () -> Unit) {
    val original = state.medicines.firstOrNull { it.id == editor.item?.medicineId }
    var selectedId by remember { mutableStateOf(original?.id) }
    val selected = state.medicines.firstOrNull { it.id == selectedId }
    var name by remember { mutableStateOf(original?.name ?: "") }
    var spec by remember { mutableStateOf(original?.specification ?: "") }
    var dose by remember { mutableStateOf(editor.item?.dose ?: "") }
    var image by remember { mutableStateOf(original?.image) }
    var chooser by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val imported = remember { mutableListOf<String>() }
    DisposableEffect(Unit) { onDispose { app.scope.launch { imported.forEach { ImageStore(app).removeIfUnused(it, app.database) } } } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { loading = true; try { image = ImageStore(app).import(uri).also { imported.add(it) } } catch (e: Exception) { error = e.message ?: "图片读取失败" } finally { loading = false } }
    }
    EditorDialog(if (editor.item == null) "添加项目" else "编辑项目", dismiss, {
        if (loading) error = "正在保存图片" else if ((selected?.name ?: name).isBlank()) error = "请填写名称" else save(selectedId, selected?.name ?: name, selected?.specification ?: spec, dose, selected?.image ?: image)
    }) {
        Row { TextButton(onClick = { chooser = true }) { Text("从药品库选择") }; if (selectedId != null) TextButton(onClick = { selectedId = null; name = ""; spec = ""; image = null }) { Text("新建药品") } }
        if (selected != null) {
            Text(selected.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            if (selected.specification.isNotBlank()) Text(selected.specification, color = Quiet, fontSize = 12.sp)
            TextButton(onClick = { editMedicine(selected) }) { Text("编辑药品名称、规格或图片", fontSize = 12.sp) }
        } else {
            OutlinedTextField(name, { name = it; error = null }, label = { Text("药品或护理名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(spec, { spec = it }, label = { Text("规格（可不填）") }, placeholder = { Text("如 20mg") }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(dose, { dose = it }, label = { Text("每次用量") }, placeholder = { Text("如 1片、2粒、1次") }, modifier = Modifier.fillMaxWidth())
        val path = if (selected != null) selected.image else image
        path?.let { AsyncImage(File(it), "药品图片预览", Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
        if (selected == null) Row { TextButton(onClick = { launcher.launch("image/*") }, enabled = !loading) { Text(if (loading) "正在保存图片…" else if (image == null) "选择图片（可不填）" else "更换图片") }; if (image != null) TextButton(onClick = { image = null }) { Text("移除图片") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (editor.item != null) TextButton(onClick = delete) { Text("删除首页条目", color = Color(0xFFB56F71)) }
    }
    if (chooser) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { chooser = false }, title = { Text("选择药品") }, text = { Column { OutlinedTextField(query, { query = it }, label = { Text("搜索名称") }); LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(state.medicines.filter { it.name.contains(query, ignoreCase = true) }, key = { it.id }) { med -> TextButton(onClick = { selectedId = med.id; dose = med.defaultDose; chooser = false }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text(med.name); if (med.specification.isNotBlank()) Text(med.specification, fontSize = 12.sp, color = Quiet) } } }
        } } }, confirmButton = { TextButton(onClick = { chooser = false }) { Text("取消") } })
    }
}

@Composable
private fun MedicineDialog(med: Medicine, app: MedApplication, dismiss: () -> Unit, save: (Medicine) -> Unit) {
    var name by remember { mutableStateOf(med.name) }
    var spec by remember { mutableStateOf(med.specification) }
    var dose by remember { mutableStateOf(med.defaultDose) }
    var image by remember { mutableStateOf(med.image) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val imported = remember { mutableListOf<String>() }
    DisposableEffect(Unit) { onDispose { app.scope.launch { imported.forEach { ImageStore(app).removeIfUnused(it, app.database) } } } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) scope.launch { loading = true; try { image = ImageStore(app).import(uri).also { imported.add(it) } } catch (e: Exception) { error = e.message ?: "图片读取失败" } finally { loading = false } } }
    EditorDialog("编辑药品资料", dismiss, { if (name.isBlank()) error = "请填写名称" else if (!loading) save(med.copy(name = name, specification = spec, defaultDose = dose, image = image)) }) {
        Text("名称、规格和图片会同步用于引用此药品的首页条目。各条目的用量保持独立。", fontSize = 12.sp, color = Quiet)
        OutlinedTextField(name, { name = it }, label = { Text("药品名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(spec, { spec = it }, label = { Text("规格（可不填）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(dose, { dose = it }, label = { Text("复用时带出的用量（可不填）") }, modifier = Modifier.fillMaxWidth())
        image?.let { AsyncImage(File(it), "图片预览", Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
        Row { TextButton(onClick = { launcher.launch("image/*") }, enabled = !loading) { Text(if (loading) "正在保存…" else "选择图片") }; if (image != null) TextButton(onClick = { image = null }) { Text("移除图片") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PeriodDialog(period: UsePeriod, dismiss: () -> Unit, save: (UsePeriod) -> Unit, delete: () -> Unit) {
    var start by remember { mutableStateOf(period.start ?: "") }
    var end by remember { mutableStateOf(period.end ?: "") }
    var active by remember { mutableStateOf(period.active) }
    var error by remember { mutableStateOf<String?>(null) }
    EditorDialog("使用日期记录", dismiss, {
        val a = start.trim().ifBlank { null }; val b = if (active) null else end.trim().ifBlank { null }
        try { validateDates(a, b); save(period.copy(start = a, end = b, active = active)) } catch (_: Exception) { error = "请用 YYYY-MM-DD 格式，停用日期不能早于开始日期" }
    }) {
        OutlinedTextField(start, { start = it; error = null }, label = { Text("开始日期（可不填）") }, placeholder = { Text("YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
        if (period.id == 0L || period.active) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("当前使用", Modifier.weight(1f)); Switch(active, { active = it }) }
        else Text("此段已停用。再次使用请添加新的使用记录，原有日期会保留。", fontSize = 12.sp, color = Quiet)
        if (!active) OutlinedTextField(end, { end = it; error = null }, label = { Text("停用日期（可不填）") }, placeholder = { Text("YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (period.id != 0L) TextButton(onClick = delete) { Text("删除这段记录", color = Color(0xFFB56F71)) }
    }
}

@Composable
private fun OrderDialog(order: OrderEditor, dismiss: () -> Unit, save: (List<Long>) -> Unit) {
    var rows by remember { mutableStateOf(order.rows) }
    EditorDialog(order.title, dismiss, { save(rows.map { it.first }) }) {
        if (rows.isEmpty()) Text("暂无可排列内容", color = Quiet)
        rows.forEachIndexed { index, row -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.second, Modifier.weight(1f))
            IconButton(onClick = { rows = rows.toMutableList().apply { add(index - 1, removeAt(index)) } }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移${row.second}") }
            IconButton(onClick = { rows = rows.toMutableList().apply { add(index + 1, removeAt(index)) } }, enabled = index < rows.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移${row.second}") }
        } }
    }
}
