package app.filesetu

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** A file on the phone that the PC may download. */
data class OutFile(val id: String, val uri: Uri, val name: String, val size: Long)

/** A file the PC sent to the phone. */
data class InFile(val name: String, val uri: Uri, val size: Long, val time: Long)

/** A short text/link exchanged between PC and phone. */
data class Note(val id: String, val fromPc: Boolean, val text: String, val time: Long)

/** Shared state between the screen, the service and the web server. */
object Store {
    const val IDLE_MINUTES = 15
    private const val MAX_HISTORY = 200
    private const val MAX_NOTES = 50

    val running = MutableStateFlow(false)
    val port = MutableStateFlow(0)
    val pin = MutableStateFlow("")
    val outbox = MutableStateFlow<List<OutFile>>(emptyList())
    val received = MutableStateFlow<List<InFile>>(emptyList())
    val notes = MutableStateFlow<List<Note>>(emptyList())
    val status = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val lang = MutableStateFlow("hi")

    @Volatile var token: String = ""
    @Volatile var lastActivity: Long = System.currentTimeMillis()
    val activeTransfers = AtomicInteger(0)

    private var prefs: SharedPreferences? = null

    /** Loads saved language and history once per process. */
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("filesetu", Context.MODE_PRIVATE)
        prefs = p
        lang.value = p.getString("lang", "hi") ?: "hi"
        received.value = runCatching {
            val arr = JSONArray(p.getString("history", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                InFile(o.getString("name"), Uri.parse(o.getString("uri")), o.getLong("size"), o.getLong("time"))
            }
        }.getOrDefault(emptyList())
    }

    fun setLang(l: String) {
        lang.value = l
        prefs?.edit()?.putString("lang", l)?.apply()
    }

    fun touch() { lastActivity = System.currentTimeMillis() }

    fun addToOutbox(ctx: Context, uris: List<Uri>) {
        val known = outbox.value.map { it.uri }.toSet()
        val added = uris.filter { it !in known }.map { uri ->
            val (name, size) = describe(ctx, uri)
            OutFile(UUID.randomUUID().toString().take(8), uri, name, size)
        }
        if (added.isNotEmpty()) outbox.update { it + added }
        touch()
    }

    fun removeFromOutbox(id: String) = outbox.update { list -> list.filterNot { it.id == id } }

    fun clearOutbox() = outbox.update { emptyList() }

    fun addReceived(f: InFile) {
        received.update { (listOf(f) + it).take(MAX_HISTORY) }
        saveHistory()
    }

    /** Clears the list only; the files stay in Download/FileSetu. */
    fun clearHistory() {
        received.value = emptyList()
        saveHistory()
    }

    fun addNote(fromPc: Boolean, text: String): Note {
        val n = Note(UUID.randomUUID().toString().take(8), fromPc, text, System.currentTimeMillis())
        notes.update { (listOf(n) + it).take(MAX_NOTES) }
        touch()
        return n
    }

    fun removeNote(id: String) = notes.update { list -> list.filterNot { it.id == id } }

    private fun saveHistory() {
        val arr = JSONArray()
        received.value.forEach {
            arr.put(JSONObject().put("name", it.name).put("uri", it.uri.toString()).put("size", it.size).put("time", it.time))
        }
        prefs?.edit()?.putString("history", arr.toString())?.apply()
    }

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

/** Picks the Hindi or English text by the chosen language. */
fun tr(hi: String, en: String): String = if (Store.lang.value == "en") en else hi

fun fmtSize(b: Long): String = when {
    b <= 0 -> "—"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
