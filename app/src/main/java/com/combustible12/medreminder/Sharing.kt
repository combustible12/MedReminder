package com.combustible12.medreminder

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

// Copy the selected original into a dedicated share cache. Its URI stays readable
// after the viewer closes or the medicine is edited; other pictures are not shared.
internal fun imageShareIntent(context: Context, path: String): Intent {
    val source = File(path)
    require(source.isFile && source.length() > 0) { "图片不存在，请重新添加" }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, options)
    val mime = options.outMimeType?.takeIf { it.startsWith("image/") } ?: "image/*"
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
    val directory = File(context.cacheDir, "shared-images").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    val target = File(directory, "medicine-${UUID.randomUUID()}.$extension")
    try { source.copyTo(target) } catch (e: Exception) { target.delete(); throw e }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", target)
    return Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "药品图片", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
