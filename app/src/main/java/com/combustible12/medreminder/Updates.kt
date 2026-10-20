package com.combustible12.medreminder

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal class AppUpdater(private val context: Context) {
    data class Release(val url: String, val digest: String, val name: String)
    private fun connect(url: String): HttpURLConnection {
        var next = url
        repeat(6) {
            val uri = Uri.parse(next)
            require(uri.scheme == "https" && uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "更新地址无效" }
            val c = URL(next).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 30000; c.useCaches = false
            c.setRequestProperty("User-Agent", "MedReminder-Android")
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                next = URL(URL(next), location ?: error("更新跳转失败")).toString()
            } else {
                if (code !in 200..299) { c.disconnect(); error(if (code == 403 || code == 429) "更新服务暂时受限，请稍后重试" else "更新请求失败（$code）") }
                return c
            }
        }
        error("更新跳转过多")
    }
    fun latest(): Release {
        // Release assets do not consume the anonymous GitHub API quota.
        return runCatching {
            val c = connect("https://github.com/combustible12/MedReminder/releases/latest/download/update.json")
            val manifest = try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
            val digest = manifest.getString("sha256")
            require(digest.matches(Regex("[0-9a-fA-F]{64}"))) { "更新校验信息无效" }
            Release(manifest.getString("url"), digest, manifest.getString("name"))
        }.getOrElse { latestFromApi() }
    }
    private fun latestFromApi(): Release {
        val c = connect("https://api.github.com/repos/combustible12/MedReminder/releases?per_page=100")
        val releases = try { JSONArray(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft")) continue
            val assets = release.getJSONArray("assets")
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                if (asset.getString("name").endsWith(".apk", true)) {
                    val digest = asset.optString("digest").removePrefix("sha256:")
                    require(digest.matches(Regex("[0-9a-fA-F]{64}"))) { "发布包缺少完整性校验，请等待重新发布" }
                    return Release(asset.getString("browser_download_url"), digest, release.optString("name"))
                }
            }
        }
        error("暂时没有可用的更新安装包")
    }
    @Suppress("DEPRECATION")
    fun validate(file: File): Boolean {
        val pm = context.packageManager
        // GET_SIGNATURES also populates archive certificates reliably on Android 9.
        // This app uses one fixed signing key; signing-key rotation is not supported.
        val flags = PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: error("安装包无效，请重新下载")
        require(apk.packageName == context.packageName) { "安装包应用标识不匹配" }
        val current = pm.getPackageInfo(context.packageName, flags)
        val old = current.signatures
        val fresh = apk.signatures
        require(!old.isNullOrEmpty() && !fresh.isNullOrEmpty() && old.toSet() == fresh.toSet()) { "安装包签名不一致，无法覆盖升级" }
        val nextVersion = if (Build.VERSION.SDK_INT >= 28) apk.longVersionCode else apk.versionCode.toLong()
        val installed = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
        return nextVersion > installed
    }
    fun download(release: Release, progress: (String) -> Unit): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val part = File(dir, "download.part")
        val dest = File(dir, "medreminder.apk")
        val c = connect(release.url)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L; var last = 0L
            c.inputStream.use { input -> part.outputStream().use { out ->
                val buffer = ByteArray(32768)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    count += n; require(count <= 50_000_000) { "安装包大小异常" }
                    out.write(buffer, 0, n); digest.update(buffer, 0, n)
                    if (System.currentTimeMillis() - last > 250) {
                        last = System.currentTimeMillis()
                        progress(if (c.contentLengthLong > 0) "正在下载 ${count * 100 / c.contentLengthLong}%" else "已下载 ${count / 1024} KB")
                    }
                }
            } }
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(release.digest, true)) { "安装包校验失败，请重试" }
            if (!validate(part)) { part.delete(); return null }
            dest.delete(); check(part.renameTo(dest)) { "无法保存安装包" }
            return dest
        } finally { c.disconnect(); part.delete() }
    }
    fun install(file: File) {
        check(validate(file)) { "当前已是最新版本" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

@Composable
fun AppUpdateButton() {
    val context = LocalContext.current
    val updater = remember(context) { AppUpdater(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    // The verified file survives activity recreation while the system permission screen is open.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = File(context.cacheDir, "updates/medreminder.apk")
        status = if (!context.packageManager.canRequestPackageInstalls()) "未允许安装，点击按钮可重试" else runCatching {
            updater.install(file); "请在系统界面确认安装"
        }.getOrElse { it.message ?: "安装未完成，请重试" }
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF829BCD))
        if (status.isNotBlank()) Text(status, color = Color(0xFF8C929C), fontSize = 12.sp)
        Button(onClick = {
            busy = true; status = "正在检查更新…"
            scope.launch {
                try {
                    val release = withContext(Dispatchers.IO) { updater.latest() }
                    status = "正在获取 ${release.name}…"
                    val file = withContext(Dispatchers.IO) { updater.download(release) { value -> scope.launch { status = value } } }
                    if (file == null) status = "当前已经是最新版本"
                    else if (!context.packageManager.canRequestPackageInstalls()) {
                        status = "请允许安装，返回后自动继续"
                        permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    } else { updater.install(file); status = "请在系统界面确认安装" }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = e.message ?: "更新失败，请重试" }
                finally { busy = false }
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF829BCD), contentColor = Color.White)) {
            Icon(Icons.Outlined.SystemUpdateAlt, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "正在处理…" else "一键更新并升级", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
