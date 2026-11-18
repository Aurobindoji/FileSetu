package app.filesetu

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Where a received file is being written. Call [finish] when complete or [abort] on failure. */
class SaveTarget(
    val uri: Uri,
    val out: OutputStream,
    val finish: () -> String,
    val abort: () -> Unit,
)

object Storage {
    const val FOLDER = "FileSetu"
    private val bad = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun safeName(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\').replace(bad, "_").trim().trim('.')
        if (n.isEmpty()) n = "file"
        if (n.length > 150) {
            val ext = n.substringAfterLast('.', "").take(10)
            n = n.take(140) + if (ext.isNotEmpty()) ".$ext" else ""
        }
        return n
    }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Creates a new file in Download/FileSetu. Name clashes get " (1)" etc. */
    fun create(ctx: Context, name: String): SaveTarget =
        if (Build.VERSION.SDK_INT >= 29) createMediaStore(ctx, name) else createLegacy(ctx, name)

    private fun createMediaStore(ctx: Context, name: String): SaveTarget {
        val r = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("फ़ाइल नहीं बन सकी")
        val out = r.openOutputStream(uri) ?: run { r.delete(uri, null, null); throw IOException("फ़ाइल नहीं खुली") }
        return SaveTarget(
            uri, out,
            finish = {
                r.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                var finalName = name
                r.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) finalName = c.getString(0)
                }
                finalName
            },
            abort = { runCatching { r.delete(uri, null, null) } },
        )
    }

    @Suppress("DEPRECATION")
    private fun createLegacy(ctx: Context, name: String): SaveTarget {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Download/$FOLDER नहीं बन सका (storage permission?)")
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var file = File(dir, name)
        var i = 1
        while (file.exists() || File(dir, file.name + ".part").exists()) file = File(dir, "$base ($i)$ext").also { i++ }
        val part = File(dir, file.name + ".part")
        val out = FileOutputStream(part)
        val uri = FileProvider.getUriForFile(ctx, "app.filesetu.files", file)
        return SaveTarget(
            uri, out,
            finish = {
                if (!part.renameTo(file)) throw IOException("rename failed")
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
                file.name
            },
            abort = { part.delete() },
        )
    }
}
