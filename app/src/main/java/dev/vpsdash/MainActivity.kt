package dev.vpsdash

import android.os.Bundle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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

private val OK = Color(0xFF22C55E)
private val WARN = Color(0xFFF5B83D)
private val CRIT = Color(0xFFEF4444)
private val DIM = Color(0xFF94A3B8)
private val TEXT = Color(0xFFF1F5F9)
private val ACCENT = Color(0xFFD97757)
private val BG = Color(0xFF0B1120)
private val CARD = Color(0xFF151E30)
private val BORDER = Color(0xFF223049)
private val TRACK = Color(0xFF26324A)
private val NUM = TextStyle(fontFeatureSettings = "tnum")
private fun lvlColor(l: String) = when (l) { "crit" -> CRIT; "warn" -> WARN; else -> OK }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = ACCENT, background = BG, surface = BG, onBackground = TEXT, onSurface = TEXT)) {
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
                    modifier = Modifier.fillMaxWidth().background(BG, RoundedCornerShape(10.dp)).padding(10.dp),
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
            Text(s?.host ?: "Your VPS", fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (updated > 0) "Updated " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(updated)) else "No data yet") +
                    (s?.let { "  ·  up ${fmtDur(it.uptime)}" } ?: ""),
                color = DIM, fontSize = 12.sp, style = NUM,
            )
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = DIM, strokeWidth = 2.dp)
        IconButton(onClick = onRefresh, enabled = !busy) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh", tint = DIM)
        }
        IconButton(onClick = onSetup) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = DIM)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        s?.let { StatusPill(it) }
        Spacer(Modifier.weight(1f))
        Text("Live", fontSize = 13.sp, color = DIM)
        Switch(checked = live, onCheckedChange = onLive, modifier = Modifier.padding(start = 8.dp))
    }

    error?.let { ErrorBox(it, onTrustNewKey) }
    if (s == null) return

    // At-a-glance gauges
    Panel {
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 6.dp)) {
            Gauge("CPU", s.cpuBusy, "load ${String.format(Locale.US, "%.2f", s.load.firstOrNull() ?: 0.0)}", level(s.cpuBusy, 70.0, 90.0), Modifier.weight(1f))
            Gauge("Memory", s.memPct, "${fmtBytes(s.memUsed)} / ${fmtBytes(s.memTotal)}", level(s.memPct, 70.0, 90.0), Modifier.weight(1f))
            Gauge("Disk", s.diskPct, "${fmtBytes(s.diskUsed)} / ${fmtBytes(s.diskTotal)}", level(s.diskPct, 80.0, 90.0), Modifier.weight(1f))
        }
    }

    if (s.alerts.isNotEmpty()) Section("Alerts") { s.alerts.forEach { AlertRow(it) } }

    // Claude
    Section("Claude Code  ·  ${s.sessions.size} running", ACCENT) {
        if (s.version.isNotBlank()) Stat("version ${s.version}  ·  total ${fmtBytes(s.claudeRss)}")
        if (s.sessions.isEmpty()) Stat("No Claude Code processes found")
        s.sessions.forEach { x ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(BG, RoundedCornerShape(10.dp))) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(ACCENT, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)))
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(x.cwd, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "cpu ${f0(x.cpu)}%  ·  ${fmtBytes(x.rss)} + ${fmtBytes(x.childRss)} in ${x.mcp} MCP  ·  ${fmtDur(x.age)}",
                        color = DIM, fontSize = 12.sp, style = NUM,
                    )
                }
            }
        }
    }

    // CPU
    Section("CPU") {
        Stat("usr ${f0(s.cpuUsr)}%  ·  sys ${f0(s.cpuSys)}%  ·  iowait ${f0(s.cpuIowait)}%  ·  steal ${f0(s.cpuSteal)}%",
            level(maxOf(s.cpuSteal * 3, s.cpuIowait * 2), 30.0, 60.0))
        CoreBars(s.cores)
        Stat("load ${s.load.joinToString("  ") { String.format(Locale.US, "%.2f", it) }}")
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

    // Disk & network
    Section("Disk & network") {
        MetricBar("disk", s.diskPct, "${fmtBytes(s.diskUsed)} / ${fmtBytes(s.diskTotal)}", level(s.diskPct, 80.0, 90.0))
        Stat("read ${fmtBytes(s.diskRead)}/s  ·  write ${fmtBytes(s.diskWrite)}/s")
        Stat("↓ ${fmtBytes(s.netRx)}/s  ·  ↑ ${fmtBytes(s.netTx)}/s")
        when (s.apiOk) {
            true -> Stat("Anthropic API reachable · ${f0(s.apiMs ?: 0.0)}ms", level(s.apiMs ?: 0.0, 150.0, 300.0))
            false -> Stat("Anthropic API unreachable", "crit")
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
private fun Panel(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CARD),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BORDER),
    ) { content() }
}

@Composable
private fun Section(title: String, titleColor: Color = DIM, content: @Composable () -> Unit) {
    Panel {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            content()
        }
    }
}

@Composable
private fun StatusPill(s: Stats) {
    val (txt, col) = when (s.health) {
        "crit" -> "Critical" to CRIT
        "warn" -> "${s.alerts.size} warning${if (s.alerts.size == 1) "" else "s"}" to WARN
        else -> "Healthy" to OK
    }
    Row(
        Modifier.background(col.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(col, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(txt, color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Gauge(label: String, pct: Double, sub: String, lvl: String, modifier: Modifier) {
    val p by animateFloatAsState((pct / 100).toFloat().coerceIn(0f, 1f), tween(600), label = label)
    val col = lvlColor(lvl)
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 8.dp.toPx()
                val arc = Size(size.width - w, size.height - w)
                val tl = Offset(w / 2, w / 2)
                drawArc(TRACK, 135f, 270f, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
                if (p > 0f) drawArc(col, 135f, 270f * p, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text("${f0(pct)}%", fontSize = 20.sp, fontWeight = FontWeight.Bold, style = NUM)
        }
        Text(label, color = TEXT, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = DIM, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NUM)
    }
}

@Composable
private fun CoreBars(cores: List<Double>) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        cores.forEach { c ->
            val f by animateFloatAsState((c / 100).toFloat().coerceIn(0.04f, 1f), tween(600), label = "core")
            Box(Modifier.weight(1f).fillMaxHeight().background(TRACK, RoundedCornerShape(3.dp)), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(f).background(lvlColor(level(c, 70.0, 90.0)), RoundedCornerShape(3.dp)))
            }
        }
    }
}

@Composable
private fun MetricBar(label: String, pct: Double, right: String, lvl: String = level(pct, 70.0, 90.0)) {
    val f by animateFloatAsState((pct / 100).toFloat().coerceIn(0f, 1f), tween(600), label = label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = DIM, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        Box(Modifier.weight(1f).height(8.dp).background(TRACK, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(lvlColor(lvl), RoundedCornerShape(4.dp)))
        }
        Text(right, fontSize = 13.sp, style = NUM, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Stat(text: String, lvl: String? = null) {
    Text(text, fontSize = 13.sp, style = NUM, color = if (lvl == null || lvl == "ok") DIM else lvlColor(lvl))
}

@Composable
private fun AlertRow(a: Alert) {
    val col = lvlColor(a.level)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(col.copy(alpha = 0.10f), RoundedCornerShape(10.dp)),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(col, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)))
        Text(a.msg, color = col, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
    }
}

@Composable
private fun ErrorBox(msg: String, onTrustNewKey: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(CRIT.copy(alpha = 0.14f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(msg, color = CRIT, fontSize = 13.sp)
        if (msg.contains("host key changed", ignoreCase = true)) {
            OutlinedButton(onClick = onTrustNewKey) { Text("Trust new host key") }
        }
    }
}
