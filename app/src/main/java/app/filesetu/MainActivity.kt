package app.filesetu

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        askPermissions()
        handleShare(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1565C0))) {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < 29) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    /** Files (or plain text) shared from WhatsApp, Gallery, file manager etc. go to the outbox and the server starts. */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intentUri(intent)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> uris += intentUris(intent)
            else -> return
        }
        intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris += it } }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && !text.isNullOrBlank()) Store.addNote(fromPc = false, text = text.trim())
        if (uris.isNotEmpty()) Store.addToOutbox(this, uris.distinct())
        if ((uris.isNotEmpty() || !text.isNullOrBlank()) && !Store.running.value) TransferService.start(this)
        intent.action = null
    }

    @Suppress("DEPRECATION")
    private fun intentUri(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun intentUris(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    fun openFile(f: InFile) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, Storage.mimeOf(f.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(i, f.name)) }
            .onFailure { toast(tr("फ़ाइल नहीं खुली (शायद हटा दी गई)", "Could not open (maybe deleted)")) }
    }

    fun shareFile(f: InFile) {
        val i = Intent(Intent.ACTION_SEND).setType(Storage.mimeOf(f.name))
            .putExtra(Intent.EXTRA_STREAM, f.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(i, tr("आगे भेजें", "Share"))) }
    }

    fun copyText(t: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("FileSetu", t))
        if (Build.VERSION.SDK_INT < 33) toast(tr("Copy हो गया", "Copied"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun batteryOptimized(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm != null && !pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    fun openBatterySettings() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    @Composable
    private fun MainScreen() {
        val running by Store.running.collectAsState()
        val port by Store.port.collectAsState()
        val pin by Store.pin.collectAsState()
        val outbox by Store.outbox.collectAsState()
        val received by Store.received.collectAsState()
        val notes by Store.notes.collectAsState()
        val status by Store.status.collectAsState()
        val error by Store.error.collectAsState()
        val lang by Store.lang.collectAsState()
        val en = lang == "en"
        fun t(hi: String, enText: String) = if (en) enText else hi

        var addrs by remember { mutableStateOf(localAddresses()) }
        var battery by remember { mutableStateOf(batteryOptimized()) }
        var draft by remember { mutableStateOf("") }
        val timeFmt = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

        LaunchedEffect(Unit) {
            while (true) {
                addrs = localAddresses()
                battery = batteryOptimized()
                delay(3000)
            }
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach { u ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                Store.addToOutbox(this@MainActivity, uris)
                if (!Store.running.value) TransferService.start(this@MainActivity)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("FileSetu", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(t("PC ↔ मोबाइल फ़ाइल ट्रांसफ़र — बिना internet", "PC ↔ phone file transfer — no internet"),
                            fontSize = 14.sp, color = Color.Gray)
                    }
                    TextButton(onClick = { Store.setLang(if (en) "hi" else "en") }) { Text(if (en) "हिंदी" else "English") }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (running) Color(0xFFE8F5E9) else Color(0xFFF1F3F6)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running) {
                            Text(t("PC के Chrome / Edge में यह पता खोलें:", "Open this address in Chrome / Edge on the PC:"), fontSize = 15.sp)
                            if (addrs.isEmpty()) {
                                Text(
                                    t("कोई नेटवर्क नहीं मिला। Hotspot चालू करें, या Wi-Fi से जुड़ें, या USB cable लगाकर USB tethering चालू करें।",
                                        "No network found. Turn on Hotspot, join Wi-Fi, or connect a USB cable and turn on USB tethering."),
                                    color = Color(0xFFC62828),
                                )
                            }
                            SelectionContainer {
                                Column {
                                    addrs.forEach { a ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                if (a.kind == "नेटवर्क") t("नेटवर्क", "Network") else a.kind,
                                                fontSize = 12.sp,
                                                color = Color.White,
                                                modifier = Modifier
                                                    .background(Color(0xFF1565C0), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                            Spacer(Modifier.padding(4.dp))
                                            Text(
                                                "http://${a.ip}:$port",
                                                fontSize = 21.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                        }
                                    }
                                }
                            }
                            Text("PIN:  $pin", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                            if (status.isNotEmpty()) Text(status, fontSize = 14.sp, color = Color(0xFF2E7D32))
                            Text(
                                t("${Store.IDLE_MINUTES} मिनट कोई काम न होने पर अपने-आप बंद हो जाएगा।",
                                    "Stops by itself after ${Store.IDLE_MINUTES} idle minutes."),
                                fontSize = 12.sp, color = Color.Gray,
                            )
                            Button(
                                onClick = { TransferService.stop(this@MainActivity) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text(t("बंद करें", "Stop"), fontSize = 18.sp) }
                        } else {
                            Text(
                                t("1. PC को फ़ोन के Hotspot से जोड़ें (या दोनों एक Wi-Fi पर, या USB tethering)।\n" +
                                    "2. नीचे \"चालू करें\" दबाएँ।\n3. PC के browser में दिखाया गया पता खोलें और PIN डालें।",
                                    "1. Connect the PC to the phone's Hotspot (or same Wi-Fi, or USB tethering).\n" +
                                        "2. Tap \"Start\" below.\n3. Open the shown address in the PC browser and enter the PIN."),
                                fontSize = 14.sp,
                            )
                            if (status.isNotEmpty()) Text(status, fontSize = 13.sp, color = Color.Gray)
                            Button(
                                onClick = { TransferService.start(this@MainActivity) },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) { Text(t("चालू करें", "Start"), fontSize = 20.sp) }
                        }
                        error?.let { Text(it, color = Color(0xFFC62828)) }
                    }
                }
            }

            if (battery) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                t("बड़ी फ़ाइल के समय फ़ोन ट्रांसफ़र रोक सकता है। Battery optimization बंद कर दें।",
                                    "The phone may pause big transfers. Turn off battery optimization."),
                                fontSize = 13.sp,
                            )
                            TextButton(onClick = { openBatterySettings() }) { Text(t("Battery setting खोलें", "Open battery setting")) }
                        }
                    }
                }
            }

            // ----- files to PC -----
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        t("PC को भेजने के लिए", "To send to PC") + " (${outbox.size})",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    if (outbox.isNotEmpty()) TextButton(onClick = { Store.clearOutbox() }) { Text(t("सब हटाएँ", "Clear")) }
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(t("+ फ़ाइल चुनें", "+ Choose files"))
                }
                if (outbox.isEmpty()) {
                    Text(
                        t("या WhatsApp / Gallery / File Manager में Share → FileSetu दबाएँ।",
                            "Or tap Share → FileSetu in WhatsApp / Gallery / File Manager."),
                        fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            items(outbox, key = { "o" + it.id }) { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fmtSize(f.size), fontSize = 12.sp, color = Color.Gray)
                    }
                    TextButton(onClick = { Store.removeFromOutbox(f.id) }) { Text("✕") }
                }
                HorizontalDivider()
            }

            // ----- text / links -----
            item {
                Spacer(Modifier.height(6.dp))
                Text(t("Text / Link (दोनों तरफ़)", "Text / Link (both ways)"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        placeholder = { Text(t("नंबर, link, छोटा नोट…", "Number, link, short note…")) },
                        modifier = Modifier.weight(1f), maxLines = 4,
                    )
                    TextButton(
                        onClick = {
                            if (draft.isNotBlank()) {
                                Store.addNote(fromPc = false, text = draft.trim())
                                draft = ""
                                if (!Store.running.value) TransferService.start(this@MainActivity)
                            }
                        },
                    ) { Text(t("भेजें", "Send")) }
                }
            }
            items(notes, key = { "n" + it.id }) { n ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (n.fromPc) t("PC से", "From PC") else t("फ़ोन से", "From phone")) + " · " + timeFmt.format(Date(n.time)),
                            fontSize = 11.sp, color = if (n.fromPc) Color(0xFF1565C0) else Color.Gray,
                        )
                        SelectionContainer { Text(n.text, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                    }
                    TextButton(onClick = { copyText(n.text) }) { Text("Copy") }
                    TextButton(onClick = { Store.removeNote(n.id) }) { Text("✕") }
                }
                HorizontalDivider()
            }

            // ----- received history -----
            item {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(t("PC से आई फ़ाइलें", "Files from PC") + " (${received.size})", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(t("सेव होती हैं: ", "Saved in: ") + "Download/${Storage.FOLDER}", fontSize = 12.sp, color = Color.Gray)
                    }
                    if (received.isNotEmpty()) {
                        TextButton(onClick = { Store.clearHistory() }) { Text(t("सूची साफ़ करें", "Clear list")) }
                    }
                }
            }
            items(received, key = { "r" + it.uri.toString() + it.time }) { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fmtSize(f.size) + " · " + timeFmt.format(Date(f.time)), fontSize = 12.sp, color = Color.Gray)
                    }
                    TextButton(onClick = { openFile(f) }) { Text(t("खोलें", "Open")) }
                    TextButton(onClick = { shareFile(f) }) { Text(t("भेजें", "Share")) }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
