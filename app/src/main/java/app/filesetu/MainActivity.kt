package app.filesetu

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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

class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    /** Files shared from WhatsApp, Gallery, file manager etc. go to the outbox and the server starts. */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intentUri(intent)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> uris += intentUris(intent)
            else -> return
        }
        intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris += it } }
        if (uris.isEmpty()) return
        Store.addToOutbox(this, uris.distinct())
        if (!Store.running.value) TransferService.start(this)
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
    }

    fun shareFile(f: InFile) {
        val i = Intent(Intent.ACTION_SEND).setType(Storage.mimeOf(f.name))
            .putExtra(Intent.EXTRA_STREAM, f.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(i, "आगे भेजें")) }
    }

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
        val status by Store.status.collectAsState()
        val error by Store.error.collectAsState()
        var addrs by remember { mutableStateOf(localAddresses()) }
        var battery by remember { mutableStateOf(batteryOptimized()) }

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
                Text("FileSetu", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("PC ↔ मोबाइल फ़ाइल ट्रांसफ़र — बिना internet", fontSize = 14.sp, color = Color.Gray)
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
                            Text("PC के Chrome / Edge में यह पता खोलें:", fontSize = 15.sp)
                            if (addrs.isEmpty()) {
                                Text(
                                    "कोई नेटवर्क नहीं मिला। Hotspot चालू करें, या Wi-Fi से जुड़ें, या USB cable लगाकर USB tethering चालू करें।",
                                    color = Color(0xFFC62828),
                                )
                            }
                            SelectionContainer {
                                Column {
                                    addrs.forEach { a ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                a.kind,
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
                            Button(
                                onClick = { TransferService.stop(this@MainActivity) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text("बंद करें", fontSize = 18.sp) }
                        } else {
                            Text(
                                "1. PC को फ़ोन के Hotspot से जोड़ें (या दोनों एक Wi-Fi पर, या USB tethering)।\n" +
                                    "2. नीचे \"चालू करें\" दबाएँ।\n3. PC के browser में दिखाया गया पता खोलें और PIN डालें।",
                                fontSize = 14.sp,
                            )
                            Button(
                                onClick = { TransferService.start(this@MainActivity) },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) { Text("चालू करें", fontSize = 20.sp) }
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
                                "बड़ी फ़ाइल के समय फ़ोन ट्रांसफ़र रोक सकता है। Battery optimization बंद कर दें।",
                                fontSize = 13.sp,
                            )
                            TextButton(onClick = { openBatterySettings() }) { Text("Battery setting खोलें") }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "PC को भेजने के लिए (${outbox.size})",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    if (outbox.isNotEmpty()) TextButton(onClick = { Store.clearOutbox() }) { Text("सब हटाएँ") }
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ फ़ाइल चुनें")
                }
                if (outbox.isEmpty()) {
                    Text(
                        "या WhatsApp / Gallery / File Manager में Share → FileSetu दबाएँ।",
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

            item {
                Spacer(Modifier.height(6.dp))
                Text("PC से आई फ़ाइलें (${received.size})", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("सेव होती हैं: Download/${Storage.FOLDER}", fontSize = 12.sp, color = Color.Gray)
            }
            items(received, key = { "r" + it.uri.toString() }) { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fmtSize(f.size), fontSize = 12.sp, color = Color.Gray)
                    }
                    TextButton(onClick = { openFile(f) }) { Text("खोलें") }
                    TextButton(onClick = { shareFile(f) }) { Text("भेजें") }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
