package app.filesetu

import android.content.Context
import android.os.Build
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small web server: serves the PC page, receives uploads and texts, serves outbox files and a ZIP of them. */
class TransferServer(private val ctx: Context) {
    private var server: EmbeddedServer<*, *>? = null
    private val indexHtml: String by lazy {
        ctx.assets.open("index.html").use { it.readBytes().decodeToString() }
    }
    private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()

    fun start(port: Int) {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.start(wait = false)
    }

    fun stop() {
        runCatching { server?.stop(200, 800) }
        server = null
    }

    private fun ApplicationCall.authed(): Boolean {
        val t = Store.token
        val ok = t.isNotEmpty() && request.cookies["fs"] == t
        if (ok) Store.touch()
        return ok
    }

    private fun Application.module() {
        install(AutoHeadResponse)   // download managers ask HEAD first for name/size
        routing {
            get("/") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(indexHtml, ContentType.Text.Html.withCharset(Charsets.UTF_8))
            }

            post("/api/login") {
                val who = call.request.local.remoteHost
                val now = System.currentTimeMillis()
                val (count, last) = failures[who] ?: (0 to 0L)
                if (count >= 5 && now - last < 60_000) {
                    call.respondText("wait", status = HttpStatusCode.TooManyRequests)
                    return@post
                }
                val pin = call.request.queryParameters["pin"]?.trim()
                if (pin != null && pin == Store.pin.value) {
                    failures.remove(who)
                    Store.touch()
                    call.response.header(HttpHeaders.SetCookie, "fs=${Store.token}; Path=/; HttpOnly; SameSite=Strict")
                    call.respondText("ok")
                } else {
                    failures[who] = (if (now - last > 60_000) 1 else count + 1) to now
                    call.respondText("bad", status = HttpStatusCode.Unauthorized)
                }
            }

            get("/api/state") {
                if (!call.authed()) { call.respondText("", status = HttpStatusCode.Unauthorized); return@get }
                val out = Store.outbox.value.joinToString(",") {
                    """{"id":${js(it.id)},"name":${js(it.name)},"size":${it.size}}"""
                }
                val rec = Store.received.value.take(30).joinToString(",") {
                    """{"name":${js(it.name)},"size":${it.size},"time":${it.time}}"""
                }
                val notes = Store.notes.value.joinToString(",") {
                    """{"id":${js(it.id)},"fromPc":${it.fromPc},"text":${js(it.text)},"time":${it.time}}"""
                }
                val device = js("${Build.MANUFACTURER} ${Build.MODEL}")
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(
                    """{"device":$device,"version":"${BuildConfig.VERSION_NAME}","idleMinutes":${Store.IDLE_MINUTES},"outbox":[$out],"received":[$rec],"notes":[$notes]}""",
                    ContentType.Application.Json
                )
            }

            // The file name is part of the URL so every browser / download manager saves it with the right name.
            get("/api/file/{id}/{name?}") {
                if (!call.authed()) { call.respondText("", status = HttpStatusCode.Unauthorized); return@get }
                val f = Store.outbox.value.find { it.id == call.parameters["id"] }
                if (f == null) { call.respondText("not found", status = HttpStatusCode.NotFound); return@get }
                call.response.header(HttpHeaders.ContentDisposition, attachment(f.name))
                val range = if (f.size > 0) parseRange(call.request.headers[HttpHeaders.Range], f.size) else null
                if (f.size > 0) call.response.header(HttpHeaders.AcceptRanges, "bytes")
                if (range != null) {
                    call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/${f.size}")
                }
                call.respond(UriContent(ctx, f, range))
            }

            get("/api/zip/{name?}") {
                if (!call.authed()) { call.respondText("", status = HttpStatusCode.Unauthorized); return@get }
                val files = Store.outbox.value
                if (files.isEmpty()) { call.respondText("empty", status = HttpStatusCode.NotFound); return@get }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                call.response.header(HttpHeaders.ContentDisposition, attachment("FileSetu_$stamp.zip"))
                call.respond(ZipContent(ctx, files))
            }

            post("/api/text") {
                if (!call.authed()) { call.respondText("", status = HttpStatusCode.Unauthorized); return@post }
                val text = call.receiveText().trim().take(10_000)
                if (text.isEmpty()) { call.respondText("empty", status = HttpStatusCode.BadRequest); return@post }
                Store.addNote(fromPc = true, text = text)
                Notifier.received(ctx, tr("PC से text आया", "Text from PC"), text.take(120))
                call.respondText("ok")
            }

            delete("/api/text/{id}") {
                if (!call.authed()) { call.respondText("", status = HttpStatusCode.Unauthorized); return@delete }
                Store.removeNote(call.parameters["id"] ?: "")
                call.respondText("ok")
            }

            put("/api/upload") {
                if (!call.authed()) { call.respondText("", status = HttpStatusCode.Unauthorized); return@put }
                val name = Storage.safeName(call.request.queryParameters["name"] ?: "file")
                val expected = call.request.contentLength()
                val target = withContext(Dispatchers.IO) { Storage.create(ctx, name) }
                Store.activeTransfers.incrementAndGet()
                try {
                    val total = receiveInto(call.receiveChannel(), target, name, expected)
                    if (expected != null && total != expected) throw IOException("incomplete ($total/$expected)")
                    val finalName = withContext(Dispatchers.IO) { target.finish() }
                    Store.addReceived(InFile(finalName, target.uri, total, System.currentTimeMillis()))
                    Store.status.value = tr("मिली: ", "Received: ") + "$finalName (${fmtSize(total)})"
                    Notifier.received(ctx, tr("PC से फ़ाइल आई", "File from PC"), "$finalName (${fmtSize(total)})")
                    call.respondText("""{"name":${js(finalName)}}""", ContentType.Application.Json)
                } catch (e: Throwable) {
                    withContext(Dispatchers.IO) {
                        runCatching { target.out.close() }
                        target.abort()
                    }
                    Store.status.value = tr("अधूरी रह गई: ", "Incomplete: ") + name
                    throw e
                } finally {
                    Store.activeTransfers.decrementAndGet()
                    Store.touch()
                }
            }
        }
    }

    private suspend fun receiveInto(ch: ByteReadChannel, t: SaveTarget, name: String, expected: Long?): Long {
        val buf = ByteArray(256 * 1024)
        var total = 0L
        var lastUi = 0L
        t.out.use { out ->
            while (true) {
                val n = ch.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) { if (ch.isClosedForRead) break else continue }
                withContext(Dispatchers.IO) { out.write(buf, 0, n) }
                total += n
                val now = System.currentTimeMillis()
                if (now - lastUi > 500) {
                    lastUi = now
                    Store.touch()
                    Store.status.value = tr("आ रही है: ", "Receiving: ") + "$name — ${fmtSize(total)}" +
                        (expected?.let { " / ${fmtSize(it)}" } ?: "")
                }
            }
        }
        return total
    }

    /** Streams one outbox file to the PC without loading it in memory. */
    private class UriContent(
        private val ctx: Context,
        private val f: OutFile,
        private val range: LongRange?,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.OctetStream
        override val status: HttpStatusCode? = if (range != null) HttpStatusCode.PartialContent else null
        override val contentLength: Long? =
            range?.let { it.last - it.first + 1 } ?: f.size.takeIf { it > 0 }

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val input = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(f.uri) }
                ?: throw IOException("cannot open ${f.name}")
            Store.activeTransfers.incrementAndGet()
            try {
                val buf = ByteArray(256 * 1024)
                var sent = 0L
                var lastUi = 0L
                var left = contentLength ?: Long.MAX_VALUE
                input.use {
                    if (range != null && range.first > 0) withContext(Dispatchers.IO) { skipFully(it, range.first) }
                    while (left > 0) {
                        val want = minOf(buf.size.toLong(), left).toInt()
                        val n = withContext(Dispatchers.IO) { it.read(buf, 0, want) }
                        if (n < 0) break
                        channel.writeFully(buf, 0, n)
                        left -= n
                        sent += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 500) {
                            lastUi = now
                            Store.touch()
                            Store.status.value = tr("PC ले रहा है: ", "PC downloading: ") +
                                "${f.name} — ${fmtSize(sent)} / ${fmtSize(f.size)}"
                        }
                    }
                }
                channel.flush()
                Store.status.value = tr("PC ने ली: ", "PC got: ") + f.name
            } finally {
                Store.activeTransfers.decrementAndGet()
                Store.touch()
            }
        }
    }

    /** Builds the ZIP on the fly; nothing is written to the phone's storage. */
    private class ZipContent(private val ctx: Context, private val files: List<OutFile>) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.Zip

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val sink = ByteArrayOutputStream(512 * 1024)
            val zip = ZipOutputStream(sink).apply { setLevel(1) }
            val used = HashSet<String>()
            val buf = ByteArray(128 * 1024)
            Store.activeTransfers.incrementAndGet()
            try {
                for ((i, f) in files.withIndex()) {
                    val input = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(f.uri) }.getOrNull() }
                        ?: continue
                    zip.putNextEntry(ZipEntry(uniqueName(f.name, used)))
                    input.use {
                        while (true) {
                            val n = withContext(Dispatchers.IO) {
                                val r = it.read(buf)
                                if (r > 0) zip.write(buf, 0, r)
                                r
                            }
                            if (n < 0) break
                            if (sink.size() >= 256 * 1024) drain(sink, channel)
                        }
                    }
                    zip.closeEntry()
                    Store.touch()
                    Store.status.value = "ZIP: ${i + 1}/${files.size} — ${f.name}"
                }
                zip.finish()
                drain(sink, channel)
                channel.flush()
                Store.status.value = tr("PC ने ZIP ली (${files.size} फ़ाइलें)", "PC got ZIP (${files.size} files)")
            } finally {
                Store.activeTransfers.decrementAndGet()
                Store.touch()
            }
        }

        private suspend fun drain(sink: ByteArrayOutputStream, channel: ByteWriteChannel) {
            if (sink.size() == 0) return
            val bytes = sink.toByteArray()
            sink.reset()
            channel.writeFully(bytes, 0, bytes.size)
        }

        private fun uniqueName(name: String, used: MutableSet<String>): String {
            if (used.add(name.lowercase())) return name
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var i = 1
            while (!used.add("$base ($i)$ext".lowercase())) i++
            return "$base ($i)$ext"
        }
    }

    companion object {
        /** Single "bytes=a-b", "bytes=a-" or "bytes=-n" range; null = send the whole file. */
        fun parseRange(h: String?, size: Long): LongRange? {
            val m = Regex("""^bytes=(\d*)-(\d*)$""").find(h?.trim() ?: return null) ?: return null
            val (a, b) = m.destructured
            val r = when {
                a.isEmpty() && b.isEmpty() -> return null
                a.isEmpty() -> (size - b.toLong()).coerceAtLeast(0)..(size - 1)
                else -> a.toLong()..(if (b.isEmpty()) size - 1 else minOf(b.toLong(), size - 1))
            }
            return if (r.first in 0 until size && r.last >= r.first) r else null
        }

        fun skipFully(input: java.io.InputStream, n: Long) {
            var left = n
            val scratch = ByteArray(64 * 1024)
            while (left > 0) {
                val s = input.skip(left)
                if (s > 0) { left -= s; continue }
                val r = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
                if (r < 0) throw IOException("range beyond end")
                left -= r
            }
        }

        fun js(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                c == ' ' || c == ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
            return sb.append('"').toString()
        }

        fun attachment(name: String): String {
            val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
            val utf = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''$utf"
        }
    }
}
