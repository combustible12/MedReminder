package com.combustible12.medreminder

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import androidx.compose.ui.draw.clip

@Composable
internal fun SoftTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false, shape: Shape = RoundedCornerShape(16.dp),
    isError: Boolean = false,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default
) {
    TextField(value, onValueChange, modifier, label = label, placeholder = placeholder,
        leadingIcon = leadingIcon, singleLine = singleLine, shape = shape, isError = isError, keyboardOptions = keyboardOptions,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
            disabledContainerColor = Color.White, errorContainerColor = Color.White,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent))
}

@Composable
internal fun PhotoStrip(paths: List<String>, open: (String) -> Unit, remove: ((String) -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        paths.forEachIndexed { index, path ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(File(path), "查看图片${index + 1}", Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).clickable { open(path) }, contentScale = ContentScale.Crop)
                if (remove != null) TextButton(onClick = { remove(path) }) { Text("删除第${index + 1}张") }
            }
        }
    }
}

@Composable
internal fun PhotoEditor(paths: List<String>, loading: Boolean, editable: Boolean, add: () -> Unit, remove: (String) -> Unit) {
    var viewing by remember { mutableStateOf<String?>(null) }
    if (paths.isNotEmpty()) PhotoStrip(paths, { viewing = it }, if (editable && !loading) remove else null)
    if (editable) TextButton(onClick = add, enabled = !loading) {
        Text(if (loading) "正在读取图片…" else if (paths.isEmpty()) "添加图片（可多选，可不填）" else "继续添加图片")
    }
    viewing?.let { path ->
        if (path in paths) PhotoViewer(paths, paths.indexOf(path)) { viewing = null }
    }
}

@Composable
internal fun PhotoViewer(paths: List<String>, initialIndex: Int, dismiss: () -> Unit) {
    if (paths.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    var index by remember(paths, initialIndex) { mutableIntStateOf(initialIndex.coerceIn(paths.indices)) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                // At fit size a horizontal drag changes images; a two-finger gesture
                // takes ownership to zoom, and at zoomed size one finger pans.
                Box(Modifier.fillMaxSize().padding(vertical = 64.dp).clip(RoundedCornerShape(0.dp))
                    .pointerInput(index) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var horizontal = 0f
                            var transformed = false
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (pressed > 1 || scale > 1.01f || transformed) {
                                    transformed = true
                                    val old = scale
                                    val next = (old * zoom).coerceIn(1f, 6f)
                                    val center = event.calculateCentroid() - Offset(size.width / 2f, size.height / 2f)
                                    val target = (offset - center) * (next / old) + center + pan
                                    scale = next
                                    val maxX = size.width * (next - 1f) / 2f
                                    val maxY = size.height * (next - 1f) / 2f
                                    offset = if (next <= 1.01f) Offset.Zero else Offset(target.x.coerceIn(-maxX, maxX), target.y.coerceIn(-maxY, maxY))
                                } else horizontal += pan.x
                                event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                            } while (event.changes.any { it.pressed })
                            if (!transformed && kotlin.math.abs(horizontal) > 60.dp.toPx()) {
                                index = (index + if (horizontal < 0) 1 else -1).coerceIn(paths.indices)
                            }
                        }
                    }) {
                    AsyncImage(File(paths[index]), "药品大图", Modifier.fillMaxSize().semantics { stateDescription = "缩放${(scale * 100).toInt()}%" }.graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    }, contentScale = ContentScale.Fit)
                }
                TextButton(onClick = {
                    val selectedPath = paths[index]
                    sharing = true
                    scope.launch {
                        try {
                            val intent = withContext(Dispatchers.IO) { imageShareIntent(context, selectedPath) }
                            context.startActivity(android.content.Intent.createChooser(intent, "分享药品图片"))
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { android.widget.Toast.makeText(context, "图片分享失败，请重试", android.widget.Toast.LENGTH_LONG).show() }
                        finally { sharing = false }
                    }
                }, enabled = !sharing, modifier = Modifier.align(Alignment.TopStart)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (sharing) "准备中…" else "分享")
                }
                Text("${index + 1} / ${paths.size}", Modifier.align(Alignment.TopCenter).padding(18.dp))
                IconButton(onClick = dismiss, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Outlined.Close, "关闭大图") }
                Row(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { index-- }, enabled = index > 0) { Icon(Icons.Outlined.ChevronLeft, "上一张") }
                    TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("恢复原图") }
                    IconButton(onClick = { index++ }, enabled = index < paths.lastIndex) { Icon(Icons.Outlined.ChevronRight, "下一张") }
                }
            }
        }
    }
}
