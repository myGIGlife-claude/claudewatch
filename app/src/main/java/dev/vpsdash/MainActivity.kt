package dev.vpsdash

import android.os.Bundle
import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.key
import androidx.lifecycle.lifecycleScope
import dev.vpsdash.widget.StatsWidget
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
    /** Server a widget tap asked to show. */
    private val requested = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requested.value = intent.getStringExtra(EXTRA_SERVER)
        setContent { Themed { App(requested.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requested.value = intent.getStringExtra(EXTRA_SERVER)
    }

    companion object {
        const val EXTRA_SERVER = "server"
    }
}

/** Opened by the launcher when a widget is added (or reconfigured): pick which server it shows. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()

        val servers = Prefs.servers(this)
        if (servers.size == 1) return pick(widgetId, servers[0].id)
        enableEdgeToEdge()
        setContent {
            Themed {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Which server should this widget show?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (servers.isEmpty()) {
                        Text("You haven't added a server yet. Open ClaudeWatch to connect one, then add the widget again.", color = DIM)
                        Button(onClick = { startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java)); finish() }) {
                            Text("Open ClaudeWatch")
                        }
                    }
                    servers.forEach { s ->
                        Panel(Modifier.fillMaxWidth().clickable { pick(widgetId, s.id) }) {
                            Column(Modifier.padding(16.dp)) {
                                Text(s.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("${s.user}@${s.host}:${s.port}", color = DIM, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pick(widgetId: Int, serverId: String) {
        Prefs.setWidgetServer(this, widgetId, serverId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        lifecycleScope.launch {
            StatsWidget.pushAll(applicationContext)
            Refresh.now(applicationContext)
            finish()
        }
    }
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = ACCENT, background = BG, surface = BG, onBackground = TEXT, onSurface = TEXT)) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
fun App(requested: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var servers by remember { mutableStateOf(Prefs.servers(ctx)) }
    var selId by remember { mutableStateOf(requested ?: servers.firstOrNull()?.id) }
    val cfg = servers.find { it.id == selId } ?: servers.firstOrNull()
    var pub by remember { mutableStateOf(KeyManager.publicKey(ctx)) }
    var stats by remember(cfg?.id) { mutableStateOf(cfg?.let { Stats.parseOrNull(Prefs.lastJson(ctx, it.id)) }) }
    var updated by remember(cfg?.id) { mutableStateOf(cfg?.let { Prefs.lastTime(ctx, it.id) } ?: 0L) }
    var error by remember(cfg?.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(true) }
    // null = dashboard, "" = adding a server, otherwise the id of the server being edited
    var setup by remember { mutableStateOf(if (cfg == null || pub == null || stats == null) cfg?.id ?: "" else null) }

    LaunchedEffect(requested) {
        if (requested != null && servers.any { it.id == requested }) { selId = requested; setup = null }
    }

    suspend fun refresh(pushWidgets: Boolean = true): Boolean {
        val c = cfg ?: return false
        busy = true
        val e = Refresh.fetchAndPublish(ctx, c.id, pushWidgets)
        error = e
        if (e == null) {
            stats = Stats.parseOrNull(Prefs.lastJson(ctx, c.id))
            updated = Prefs.lastTime(ctx, c.id)
        }
        busy = false
        return e == null
    }

    // Live polling of the selected server while the app is on screen
    LaunchedEffect(cfg?.id, pub, live, setup) {
        if (cfg != null && pub != null && live && setup == null) {
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
        val editing = setup
        if (editing != null) {
            val existing = servers.find { it.id == editing }
            key(editing) {
                SetupScreen(
                    cfg = existing, pub = pub, busy = busy, error = error,
                    onSave = { c ->
                        Prefs.saveServer(ctx, c)
                        servers = Prefs.servers(ctx)
                        selId = c.id
                        setup = c.id
                    },
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
                                setup = null
                            }
                        }
                    },
                    onTrustNewKey = { existing?.let { Prefs.setFingerprint(ctx, it.id, null) }; error = null },
                    onDelete = existing?.let { e ->
                        {
                            Prefs.deleteServer(ctx, e.id)
                            servers = Prefs.servers(ctx)
                            selId = servers.firstOrNull()?.id
                            setup = if (servers.isEmpty()) "" else null
                            scope.launch { StatsWidget.pushAll(ctx) }
                        }
                    },
                    onCancel = if (servers.isNotEmpty()) ({ setup = null }) else null,
                )
            }
        } else {
            ServerTabs(servers, cfg?.id, onSelect = { selId = it }, onAdd = { setup = "" })
            Dashboard(
                s = stats, title = cfg?.name?.ifBlank { null }, user = cfg?.user ?: "your user",
                updated = updated, error = error, busy = busy, live = live,
                onLive = { live = it },
                onRefresh = { scope.launch { refresh() } },
                onSetup = { setup = cfg?.id },
                onTrustNewKey = { cfg?.let { Prefs.setFingerprint(ctx, it.id, null) }; error = null; scope.launch { refresh() } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerTabs(servers: List<ServerConfig>, selected: String?, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (servers.size > 1) servers.forEach { s ->
            FilterChip(selected = s.id == selected, onClick = { onSelect(s.id) }, label = { Text(s.label, maxLines = 1) })
        }
        AssistChip(onClick = onAdd, label = { Text("+ Add server") })
    }
}

// ---------------- Setup ----------------
@Composable
private fun SetupScreen(
    cfg: ServerConfig?, pub: String?, busy: Boolean, error: String?,
    onSave: (ServerConfig) -> Unit, onGenerate: () -> Unit, onTest: () -> Unit,
    onTrustNewKey: () -> Unit, onDelete: (() -> Unit)?, onCancel: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(cfg?.name ?: "") }
    var host by remember { mutableStateOf(cfg?.host ?: "") }
    var port by remember { mutableStateOf((cfg?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(cfg?.user ?: "") }
    val who = cfg?.user ?: "your user"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (cfg == null) "Add a server" else "Edit ${cfg.label}", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
        OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Use the same user that runs Claude Code, so plan usage and sessions show up.", color = DIM, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(ServerConfig(cfg?.id ?: ServerConfig.newId(), host, port.toIntOrNull() ?: 22, user, name.trim())) },
                enabled = host.isNotBlank() && user.isNotBlank(),
            ) { Text(if (cfg == null) "Save" else "Update") }
            onDelete?.let { OutlinedButton(onClick = it) { Text("Remove server", color = CRIT) } }
        }
    }

    Section("2 · Install the server script") {
        Text(
            "SSH into the server as $who and paste this. It downloads claude-dash from GitHub " +
                "and installs it to /usr/local/bin (asks for your sudo password once).",
            color = DIM, fontSize = 13.sp,
        )
        CommandBox(KeyManager.SERVER_INSTALL)
    }

    Section("3 · Authorize this phone") {
        if (pub == null) {
            Text("Creates an SSH key on this phone. The private key never leaves the device, and one key works for all your servers.", color = DIM, fontSize = 13.sp)
            Button(onClick = onGenerate, enabled = !busy) { Text("Generate key") }
        } else {
            Text(
                "Still as $who, paste this. The key is locked to read-only stats: it can't open a shell or run anything else. " +
                    "In ~/.ssh/authorized_keys it's the line ending in ${pub.substringAfterLast(' ')}.",
                color = DIM, fontSize = 13.sp,
            )
            CommandBox(KeyManager.installCommand(pub))
        }
    }

    Section("4 · Connect") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onTest, enabled = !busy && cfg != null && pub != null) { Text("Connect") }
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        if (cfg == null) Text("Save the server first.", color = DIM, fontSize = 13.sp)
        error?.let { ErrorBox(it, onTrustNewKey) }
    }
}

@Composable
private fun CommandBox(cmd: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(cmd) { mutableStateOf(false) }
    SelectionContainer {
        Text(
            cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(BG, RoundedCornerShape(10.dp)).padding(10.dp),
        )
    }
    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(cmd)); copied = true }) {
        Text(if (copied) "Copied" else "Copy command")
    }
}

// ---------------- Dashboard ----------------
@Composable
private fun Dashboard(
    s: Stats?, title: String?, user: String, updated: Long, error: String?, busy: Boolean, live: Boolean,
    onLive: (Boolean) -> Unit, onRefresh: () -> Unit, onSetup: () -> Unit, onTrustNewKey: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title ?: s?.host ?: "Your VPS", fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    if (s.usage != null) UsagePanel(s.usage, user)
    else Section("Claude plan usage", ACCENT) {
        Stat("Update the server script to see plan usage and reset times. SSH in as $user and paste:")
        CommandBox(KeyManager.SERVER_INSTALL)
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
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
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
        Meter(f, lvl, Modifier.weight(1f))
        Text(right, fontSize = 13.sp, style = NUM, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Meter(fraction: Float, lvl: String, modifier: Modifier) {
    Box(modifier.height(8.dp).background(TRACK, RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(lvlColor(lvl), RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun UsagePanel(u: Usage, user: String) {
    Section("Claude plan usage", ACCENT) {
        u.error?.let {
            Stat(it, "warn")
            Stat(
                "Plan usage comes from Claude Code's login on the server. SSH in as $user, run claude and sign in " +
                    "with /login (or just start claude once if the login expired). Then check it with:"
            )
            CommandBox(KeyManager.USAGE_CHECK)
        }
        u.session?.let { LimitRow("5-hour session", it) }
        u.week?.let { LimitRow("Weekly", it) }
        u.weekOpus?.let { LimitRow("Weekly · Opus", it) }
    }
}

@Composable
private fun LimitRow(label: String, l: Limit) {
    val ctx = LocalContext.current
    val lvl = level(l.pct, 70.0, 90.0)
    val f by animateFloatAsState((l.pct / 100).toFloat().coerceIn(0f, 1f), tween(600), label = label)
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${f0(l.pct)}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = lvlColor(lvl), style = NUM)
        }
        Meter(f, lvl, Modifier.fillMaxWidth())
        l.resets?.let {
            Text(
                "Resets in ${fmtUntil(it)}, at ${fmtClock(ctx, it)}",
                color = DIM, fontSize = 12.sp, style = NUM,
            )
        }
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
