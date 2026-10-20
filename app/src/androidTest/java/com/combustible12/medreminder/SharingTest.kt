package com.combustible12.medreminder

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SharingTest {
    @Test fun sharesOnlySelectedOriginalAndKeepsReadableCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = File(context.cacheDir, "share-first"); val second = File(context.cacheDir, "share-second")
        for ((file, color) in listOf(first to android.graphics.Color.RED, second to android.graphics.Color.BLUE)) {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        try {
            val expected = second.readBytes()
            val intent = imageShareIntent(context, second.path)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("image/png", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals(1, intent.clipData!!.itemCount)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertArrayEquals(expected, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertFalse(first.readBytes().contentEquals(expected))
            second.delete()
            assertArrayEquals(expected, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue(runCatching { imageShareIntent(context, second.path) }.isFailure)
        } finally { first.delete(); second.delete() }
    }
}
