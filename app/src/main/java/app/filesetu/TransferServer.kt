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
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Small web server: serves the PC page, receives uploads, serves outbox files. */
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
        return t.isNotEmpty() && request.cookies["fs"] == t
    }

    private fun Application.module() {
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
                    call.respondText("1 मिनट बाद कोशिश करें", status = HttpStatusCode.TooManyRequests)
                    return@post
                }
                val pin = call.request.queryParameters["pin"]?.trim()
                if (pin != null && pin == Store.pin.value) {
                    failures.remove(who)
                    call.response.header(HttpHeaders.SetCookie, "fs=${Store.token}; Path=/; HttpOnly; SameSite=Strict")
                    call.respondText("ok")
                } else {
                    failures[who] = (if (now - last > 60_000) 1 else count + 1) to now
                    call.respondText("गलत PIN", status = HttpStatusCode.Unauthorized)
                }
            }

            get("/api/state") {
                if (!call.authed()) { call.respondText("", status = HttpStatusCode.Unauthorized); return@get }
                val out = Store.outbox.value.joinToString(",") {
                    """{"id":${js(it.id)},"name":${js(it.name)},"size":${it.size}}"""
                }
                val rec = Store.received.value.joinToString(",") {
                    """{"name":${js(it.name)},"size":${it.size}}"""
                }
                val device = js("${Build.MANUFACTURER} ${Build.MODEL}")
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(
                    """{"device":$device,"outbox":[$out],"received":[$rec]}""",
                    ContentType.Application.Json
                )
            }

            get("/api/file/{id}") {
                if (!call.authed()) { call.respondText("", status = HttpStatusCode.Unauthorized); return@get }
                val f = Store.outbox.value.find { it.id == call.parameters["id"] }
                if (f == null) { call.respondText("फ़ाइल नहीं मिली", status = HttpStatusCode.NotFound); return@get }
                call.response.header(HttpHeaders.ContentDisposition, attachment(f.name))
                call.respond(UriContent(ctx, f))
            }

            put("/api/upload") {
                if (!call.authed()) { call.respondText("", status = HttpStatusCode.Unauthorized); return@put }
                val name = Storage.safeName(call.request.queryParameters["name"] ?: "file")
                val expected = call.request.contentLength()
                val target = withContext(Dispatchers.IO) { Storage.create(ctx, name) }
                var total = 0L
                try {
                    total = receiveInto(call.receiveChannel(), target, name, expected)
                    if (expected != null && total != expected) throw IOException("अधूरी फ़ाइल ($total/$expected)")
                    val finalName = withContext(Dispatchers.IO) { target.finish() }
                    Store.addReceived(InFile(finalName, target.uri, total, System.currentTimeMillis()))
                    Store.status.value = "मिली: $finalName (${fmtSize(total)})"
                    call.respondText("""{"name":${js(finalName)}}""", ContentType.Application.Json)
                } catch (e: Throwable) {
                    withContext(Dispatchers.IO) {
                        runCatching { target.out.close() }
                        target.abort()
                    }
                    Store.status.value = "अधूरी रह गई: $name"
                    throw e
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
                    Store.status.value = "आ रही है: $name — ${fmtSize(total)}" +
                        (expected?.let { " / ${fmtSize(it)}" } ?: "")
                }
            }
        }
        return total
    }

    /** Streams one outbox file to the PC without loading it in memory. */
    private class UriContent(private val ctx: Context, private val f: OutFile) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.OctetStream
        override val contentLength: Long? = f.size.takeIf { it > 0 }

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val input = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(f.uri) }
                ?: throw IOException("फ़ाइल नहीं खुली: ${f.name}")
            val buf = ByteArray(256 * 1024)
            var sent = 0L
            var lastUi = 0L
            input.use {
                while (true) {
                    val n = withContext(Dispatchers.IO) { it.read(buf) }
                    if (n < 0) break
                    channel.writeFully(buf, 0, n)
                    sent += n
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 500) {
                        lastUi = now
                        Store.status.value = "PC ले रहा है: ${f.name} — ${fmtSize(sent)} / ${fmtSize(f.size)}"
                    }
                }
            }
            channel.flush()
            Store.status.value = "PC ने ली: ${f.name}"
        }
    }

    companion object {
        fun js(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
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
