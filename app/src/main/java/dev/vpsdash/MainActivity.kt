package dev.vpsdash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OK = Color(0xFF4CD37A)
private val WARN = Color(0xFFF5C451)
private val CRIT = Color(0xFFFF5C5C)
private val DIM = Color(0xFF8A939C)
private val ACCENT = Color(0xFFD97757)
private val CARD = Color(0xFF161C22)
private fun lvlColor(l: String) = when (l) { "crit" -> CRIT; "warn" -> WARN; else -> OK }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = ACCENT, background = Color(0xFF0E1419), surface = Color(0xFF0E1419))) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var cfg by remember { mutableStateOf(Prefs.config(ctx)) }
    var pub by remember { mutableStateOf(KeyManager.publicKey(ctx)) }
    var stats by remember { mutableStateOf(Stats.parseOrNull(Prefs.lastJson(ctx))) }
    var updated by remember { mutableStateOf(Prefs.lastTime(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(true) }
    var showSetup by remember { mutableStateOf(cfg == null || pub == null || stats == null) }

    suspend fun refresh(pushWidgets: Boolean = true): Boolean {
        busy = true
        val e = Refresh.fetchAndPublish(ctx, pushWidgets)
        error = e
        if (e == null) {
            stats = Stats.parseOrNull(Prefs.lastJson(ctx))
            updated = Prefs.lastTime(ctx)
        }
        busy = false
        return e == null
    }

    // Live polling while the app is on screen
    LaunchedEffect(cfg, pub, live, showSetup) {
        if (cfg != null && pub != null && live && !showSetup) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var n = 0
                while (true) {
                    refresh(pushWidgets = n % 12 == 0) // widgets get a push about once a minute
                    n++
                    delay(5_000)
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showSetup) {
            SetupScreen(
                cfg = cfg, pub = pub, busy = busy, error = error,
                onSave = { Prefs.saveConfig(ctx, it); cfg = it },
                onGenerate = {
                    scope.launch {
                        busy = true
                        pub = withContext(Dispatchers.Default) { KeyManager.generate(ctx) }
                        busy = false
                    }
                },
                onTest = {
                    scope.launch {
                        if (refresh()) {
                            Refresh.schedule(ctx)
                            showSetup = false
                        }
                    }
                },
                onTrustNewKey = { Prefs.setFingerprint(ctx, null); error = null },
                onCancel = if (stats != null) ({ showSetup = false }) else null,
            )
        } else {
            Dashboard(
                s = stats, updated = updated, error = error, busy = busy, live = live,
                onLive = { live = it },
                onRefresh = { scope.launch { refresh() } },
                onSetup = { showSetup = true },
                onTrustNewKey = { Prefs.setFingerprint(ctx, null); error = null; scope.launch { refresh() } },
            )
        }
    }
}

// ---------------- Setup ----------------
@Composable
private fun SetupScreen(
    cfg: ServerConfig?, pub: String?, busy: Boolean, error: String?,
    onSave: (ServerConfig) -> Unit, onGenerate: () -> Unit, onTest: () -> Unit,
    onTrustNewKey: () -> Unit, onCancel: (() -> Unit)?,
) {
    val clipboard = LocalClipboardManager.current
    var host by remember { mutableStateOf(cfg?.host ?: "") }
    var port by remember { mutableStateOf((cfg?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(cfg?.user ?: "") }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Connect your VPS", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        onCancel?.let { TextButton(onClick = it) { Text("Close") } }
    }

    Section("1 · Server") {
        OutlinedTextField(host, { host = it.trim() }, label = { Text("Host / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(user, { user = it.trim() }, label = { Text("Username") }, singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = { onSave(ServerConfig(host, port.toIntOrNull() ?: 22, user)) },
            enabled = host.isNotBlank() && user.isNotBlank(),
        ) { Text(if (cfg == null) "Save" else "Update") }
    }

    Section("2 · Access key") {
        if (pub == null) {
            Text("Creates an SSH key on this phone. The private key never leaves the device.", color = DIM, fontSize = 13.sp)
            Button(onClick = onGenerate, enabled = !busy) { Text("Generate key") }
        } else {
            val cmd = KeyManager.installCommand(pub)
            Text(
                "SSH into your VPS as ${cfg?.user ?: "your user"} and paste this once. " +
                    "The key is locked to read-only stats - it can't open a shell or run anything else.",
                color = DIM, fontSize = 13.sp,
            )
            SelectionContainer {
                Text(
                    cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF0A0E12), RoundedCornerShape(8.dp)).padding(10.dp),
                )
            }
            Button(onClick = { clipboard.setText(AnnotatedString(cmd)) }) { Text("Copy command") }
        }
    }

    Section("3 · Test") {
        Text("Needs claude-dash installed at /usr/local/bin on the VPS.", color = DIM, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onTest, enabled = !busy && cfg != null && pub != null) { Text("Connect") }
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        error?.let { ErrorBox(it, onTrustNewKey) }
    }
}

// ---------------- Dashboard ----------------
@Composable
private fun Dashboard(
    s: Stats?, updated: Long, error: String?, busy: Boolean, live: Boolean,
    onLive: (Boolean) -> Unit, onRefresh: () -> Unit, onSetup: () -> Unit, onTrustNewKey: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = s?.let { lvlColor(it.health) } ?: DIM, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(s?.host ?: "VPS", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                (if (updated > 0) "Updated " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(updated)) else "No data yet") +
                    (s?.let { "  ·  up ${fmtDur(it.uptime)}" } ?: ""),
                color = DIM, fontSize = 12.sp,
            )
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Live", fontSize = 12.sp, color = DIM)
        Switch(checked = live, onCheckedChange = onLive, modifier = Modifier.padding(start = 4.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
        OutlinedButton(onClick = onSetup) { Text("Settings") }
    }

    error?.let { ErrorBox(it, onTrustNewKey) }
    if (s == null) return

    // Health
    Section("Health") {
        if (s.alerts.isEmpty()) Text("✓ No issues detected", color = OK)
        s.alerts.forEach { a -> Text((if (a.level == "crit") "✗ " else "! ") + a.msg, color = lvlColor(a.level), fontSize = 14.sp) }
    }

    // CPU
    Section("CPU") {
        MetricBar("Total", s.cpuBusy, "${f0(s.cpuBusy)}%")
        Stat("usr ${f0(s.cpuUsr)}%  ·  sys ${f0(s.cpuSys)}%  ·  iowait ${f0(s.cpuIowait)}%  ·  steal ${f0(s.cpuSteal)}%",
            level(maxOf(s.cpuSteal * 3, s.cpuIowait * 2), 30.0, 60.0))
        s.cores.forEachIndexed { i, c -> MetricBar("core $i", c, "${f0(c)}%", thin = true) }
        Stat("load ${s.load.joinToString(" ") { String.format(Locale.US, "%.2f", it) }}")
    }

    // Memory
    Section("Memory") {
        MetricBar("RAM", s.memPct, "${fmtBytes(s.memUsed)} / ${fmtBytes(s.memTotal)}")
        if (s.zramTotal > 0) MetricBar("zram", s.zramPct, "${fmtBytes(s.zramUsed)}  ${String.format(Locale.US, "%.1fx", s.zramRatio)}", level(s.zramPct, 60.0, 85.0))
        if (s.swapTotal > 0) MetricBar("swap", s.swapPct, fmtBytes(s.swapUsed), level(s.swapPct, 10.0, 40.0))
        Stat("available ${fmtBytes(s.memAvail)}")
        Stat(
            "pressure  cpu ${f0(s.psiCpu)}%  ·  mem ${f0(s.psiMem)}%  ·  io ${f0(s.psiIo)}%",
            worst(level(s.psiMem, 2.0, 10.0), level(s.psiIo, 10.0, 30.0), level(s.psiCpu, 30.0, 60.0)),
        )
    }

    // Claude
    Section("Claude Code  ·  ${s.sessions.size} running", ACCENT) {
        if (s.version.isNotBlank()) Stat("version ${s.version}  ·  total ${fmtBytes(s.claudeRss)}")
        if (s.sessions.isEmpty()) Stat("No Claude Code processes found")
        s.sessions.forEach { x ->
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF0E1419), RoundedCornerShape(8.dp)).padding(10.dp),
            ) {
                Text(x.cwd, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "pid ${x.pid}  ·  cpu ${f0(x.cpu)}%  ·  ${fmtBytes(x.rss)} + ${fmtBytes(x.childRss)} in ${x.mcp} MCP  ·  ${fmtDur(x.age)}",
                    color = DIM, fontSize = 12.sp,
                )
            }
        }
    }

    // Disk & network
    Section("Disk & network") {
        MetricBar("disk", s.diskPct, "${fmtBytes(s.diskUsed)} / ${fmtBytes(s.diskTotal)}", level(s.diskPct, 80.0, 90.0))
        Stat("read ${fmtBytes(s.diskRead)}/s  ·  write ${fmtBytes(s.diskWrite)}/s")
        Stat("↓ ${fmtBytes(s.netRx)}/s  ·  ↑ ${fmtBytes(s.netTx)}/s")
        when (s.apiOk) {
            true -> Stat("Anthropic API reachable · ${f0(s.apiMs ?: 0.0)}ms", level(s.apiMs ?: 0.0, 150.0, 300.0))
            false -> Stat("Anthropic API UNREACHABLE", "crit")
            null -> Stat("API check pending")
        }
    }

    // System
    Section("System") {
        Stat("processes ${s.procs}  ·  zombies ${s.zombies}", level(s.zombies.toDouble(), 5.0, 20.0))
        Stat("open files ${s.fds}  ·  inotify ${s.inotify}/${s.inotifyMax}",
            level(pct(s.inotify.toLong(), s.inotifyMax.toLong()), 60.0, 80.0))
        Stat("earlyoom ${s.earlyoom}  ·  kills 24h ${s.oomKills ?: "?"}",
            if (s.earlyoom != "active" || (s.oomKills ?: 0) > 0) "warn" else "ok")
        Stat("failed units ${s.failedUnits ?: "?"}", if ((s.failedUnits ?: 0) > 0) "warn" else "ok")
    }
    Spacer(Modifier.height(24.dp))
}

// ---------------- building blocks ----------------
@Composable
private fun Section(title: String, titleColor: Color = DIM, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CARD), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            content()
        }
    }
}

@Composable
private fun MetricBar(label: String, pct: Double, right: String, lvl: String = level(pct, 70.0, 90.0), thin: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = DIM, fontSize = if (thin) 11.sp else 13.sp, modifier = Modifier.width(56.dp))
        LinearProgressIndicator(
            progress = { (pct / 100).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(if (thin) 5.dp else 8.dp),
            color = lvlColor(lvl),
            trackColor = Color(0x22FFFFFF),
        )
        Text(right, fontSize = if (thin) 11.sp else 13.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun Stat(text: String, lvl: String? = null) {
    Text(text, fontSize = 13.sp, color = if (lvl == null || lvl == "ok") DIM else lvlColor(lvl))
}

@Composable
private fun ErrorBox(msg: String, onTrustNewKey: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0x33FF5C5C), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(msg, color = CRIT, fontSize = 13.sp)
        if (msg.contains("host key changed", ignoreCase = true)) {
            OutlinedButton(onClick = onTrustNewKey) { Text("Trust new host key") }
        }
    }
}
