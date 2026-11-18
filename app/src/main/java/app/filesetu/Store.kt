package app.filesetu

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** A file on the phone that the PC may download. */
data class OutFile(val id: String, val uri: Uri, val name: String, val size: Long)

/** A file the PC sent to the phone. */
data class InFile(val name: String, val uri: Uri, val size: Long, val time: Long)

/** Shared state between the screen, the service and the web server. */
object Store {
    val running = MutableStateFlow(false)
    val port = MutableStateFlow(0)
    val pin = MutableStateFlow("")
    val outbox = MutableStateFlow<List<OutFile>>(emptyList())
    val received = MutableStateFlow<List<InFile>>(emptyList())
    val status = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)

    @Volatile
    var token: String = ""

    fun addToOutbox(ctx: Context, uris: List<Uri>) {
        val known = outbox.value.map { it.uri }.toSet()
        val added = uris.filter { it !in known }.map { uri ->
            val (name, size) = describe(ctx, uri)
            OutFile(UUID.randomUUID().toString().take(8), uri, name, size)
        }
        if (added.isNotEmpty()) outbox.update { it + added }
    }

    fun removeFromOutbox(id: String) = outbox.update { list -> list.filterNot { it.id == id } }

    fun clearOutbox() = outbox.update { emptyList() }

    fun addReceived(f: InFile) = received.update { listOf(f) + it }

    private fun describe(ctx: Context, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = 0L
        runCatching {
            ctx.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return (name ?: uri.lastPathSegment ?: "file") to size
    }
}

fun fmtSize(b: Long): String = when {
    b <= 0 -> "—"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
