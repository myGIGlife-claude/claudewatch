package dev.vpsdash.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.LocalContext
import dev.vpsdash.Limit
import dev.vpsdash.MainActivity
import dev.vpsdash.fmtClock
import dev.vpsdash.Prefs
import dev.vpsdash.R
import dev.vpsdash.Refresh
import dev.vpsdash.Stats
import dev.vpsdash.f0
import dev.vpsdash.fmtBytes
import dev.vpsdash.level
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val KEY_JSON = stringPreferencesKey("json")
private val KEY_TIME = longPreferencesKey("time")
private val KEY_ERR = stringPreferencesKey("err")
private val KEY_CONFIGURED = booleanPreferencesKey("configured")
private val KEY_SERVER = stringPreferencesKey("server")
private val KEY_NAME = stringPreferencesKey("name")
private val SERVER_PARAM = ActionParameters.Key<String>(MainActivity.EXTRA_SERVER)

private val BG = Color(0xF20B1120)
private val TILE = Color(0xFF151E30)
private val TRACK = Color(0xFF26324A)
private val TEXT = Color(0xFFF1F5F9)
private val DIM = Color(0xFF94A3B8)
private val OK = Color(0xFF22C55E)
private val WARN = Color(0xFFF5B83D)
private val CRIT = Color(0xFFEF4444)
private val ACCENT = Color(0xFFD97757)

private fun lvlColor(l: String) = when (l) { "crit" -> CRIT; "warn" -> WARN; else -> OK }
private fun cp(c: Color) = ColorProvider(c)

class StatsWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetBody(currentState()) }
    }

    companion object {
        /** Copies the latest cached result into every placed widget and redraws them. */
        suspend fun pushAll(ctx: Context) {
            val mgr = GlanceAppWidgetManager(ctx)
            mgr.getGlanceIds(StatsWidget::class.java).forEach { id ->
                // Each widget shows the server picked when it was added (first server if none/removed)
                val cfg = Prefs.server(ctx, Prefs.widgetServer(ctx, mgr.getAppWidgetId(id)))
                val json = cfg?.let { Prefs.lastJson(ctx, it.id) }
                val err = cfg?.let { Prefs.lastError(ctx, it.id) }
                updateAppWidgetState(ctx, id) { p ->
                    if (json != null) p[KEY_JSON] = json else p.remove(KEY_JSON)
                    p[KEY_TIME] = cfg?.let { Prefs.lastTime(ctx, it.id) } ?: 0L
                    if (err != null) p[KEY_ERR] = err else p.remove(KEY_ERR)
                    p[KEY_CONFIGURED] = cfg != null
                    p[KEY_SERVER] = cfg?.id ?: ""
                    p[KEY_NAME] = cfg?.name ?: ""
                }
                StatsWidget().update(ctx, id)
            }
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Refresh.now(context)
    }
}

class StatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Refresh.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Refresh.now(context) // fill a freshly placed widget right away
    }
}

@Composable
private fun WidgetBody(p: Preferences) {
    val size = LocalSize.current
    val compact = size.height < 150.dp
    val tall = size.height >= 200.dp
    val s = Stats.parseOrNull(p[KEY_JSON])
    val time = p[KEY_TIME] ?: 0L
    val err = p[KEY_ERR]
    val configured = p[KEY_CONFIGURED] ?: false
    val stale = time > 0 && System.currentTimeMillis() - time > 35 * 60_000

    Column(
        modifier = GlanceModifier.fillMaxSize().background(BG).cornerRadius(24.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(actionStartActivity<MainActivity>(actionParametersOf(SERVER_PARAM to (p[KEY_SERVER] ?: "")))),
    ) {
        // Header: status dot, host, time, refresh
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val dot = when {
                s == null -> DIM
                err != null || stale -> WARN
                else -> lvlColor(s.health)
            }
            Box(modifier = GlanceModifier.size(8.dp).background(dot).cornerRadius(4.dp)) {}
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = p[KEY_NAME]?.ifBlank { null } ?: s?.host ?: "ClaudeWatch",
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = cp(TEXT), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            if (time > 0) {
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time)),
                    style = TextStyle(color = cp(if (err != null || stale) WARN else DIM), fontSize = 11.sp),
                )
            }
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Refresh",
                modifier = GlanceModifier.size(36.dp).padding(8.dp)
                    .clickable(actionRunCallback<RefreshAction>()),
            )
        }

        if (s == null) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            Text(
                text = when {
                    !configured -> "Tap to connect your VPS"
                    err != null -> err
                    else -> "Loading…"
                },
                style = TextStyle(color = cp(if (err != null) WARN else DIM), fontSize = 12.sp),
                maxLines = 3,
            )
            return@Column
        }

        // Metric tiles
        Spacer(modifier = GlanceModifier.height(4.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Tile("CPU", s.cpuBusy, if (s.cpuSteal >= 5) "steal ${f0(s.cpuSteal)}%" else "load ${String.format(Locale.US, "%.2f", s.load.firstOrNull() ?: 0.0)}",
                level(s.cpuBusy, 70.0, 90.0), compact, GlanceModifier.defaultWeight())
            Spacer(modifier = GlanceModifier.width(6.dp))
            Tile("MEM", s.memPct, "${fmtBytes(s.memUsed)}/${fmtBytes(s.memTotal)}",
                level(s.memPct, 70.0, 90.0), compact, GlanceModifier.defaultWeight())
            Spacer(modifier = GlanceModifier.width(6.dp))
            Tile("DISK", s.diskPct, "${fmtBytes(s.diskUsed)}/${fmtBytes(s.diskTotal)}",
                level(s.diskPct, 80.0, 90.0), compact, GlanceModifier.defaultWeight())
        }
        if (tall && s.zramTotal > 0) {
            Bar("ZRAM", s.zramPct, "${fmtBytes(s.zramUsed)} ${String.format(Locale.US, "%.1fx", s.zramRatio)}",
                level(s.zramPct, 60.0, 85.0))
        }

        // Plan usage tiles: clock time, not a countdown, since the widget only redraws every ~15 min
        val u = s.usage
        if (u != null && (u.session != null || u.week != null)) {
            val ctx = LocalContext.current
            fun resets(l: Limit) = l.resets?.let { "resets ${fmtClock(ctx, it)}" } ?: ""
            Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
                u.session?.let { Tile("SESSION", it.pct, resets(it), level(it.pct, 70.0, 90.0), compact, GlanceModifier.defaultWeight()) }
                if (u.session != null && u.week != null) Spacer(modifier = GlanceModifier.width(6.dp))
                u.week?.let { Tile("WEEKLY", it.pct, resets(it), level(it.pct, 70.0, 90.0), compact, GlanceModifier.defaultWeight()) }
            }
        }

        // Claude + API
        if (!compact) Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Claude ", style = TextStyle(color = cp(ACCENT), fontSize = 12.sp, fontWeight = FontWeight.Bold))
            Text(
                text = "${s.sessions.size} running · ${fmtBytes(s.claudeRss)}",
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = cp(TEXT), fontSize = 12.sp),
                maxLines = 1,
            )
            val (apiTxt, apiCol) = when (s.apiOk) {
                true -> "API ${f0(s.apiMs ?: 0.0)}ms" to lvlColor(level(s.apiMs ?: 0.0, 150.0, 300.0))
                false -> "API down" to CRIT
                null -> "API ?" to DIM
            }
            Text(text = apiTxt, style = TextStyle(color = cp(apiCol), fontSize = 12.sp))
        }

        // Health line
        val first = s.alerts.firstOrNull()
        Text(
            text = when {
                err != null -> err
                first != null -> first.msg + if (s.alerts.size > 1) "  +${s.alerts.size - 1} more" else ""
                else -> "All systems healthy"
            },
            modifier = GlanceModifier.padding(top = 2.dp),
            style = TextStyle(
                color = cp(if (err != null) WARN else first?.let { lvlColor(it.level) } ?: OK),
                fontSize = 11.sp,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun Tile(label: String, pct: Double, sub: String, lvl: String, compact: Boolean, modifier: GlanceModifier) {
    Column(modifier = modifier.background(TILE).cornerRadius(14.dp).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = cp(DIM), fontSize = 10.sp, fontWeight = FontWeight.Bold),
            )
            Text(
                text = "${f0(pct)}%",
                style = TextStyle(color = cp(lvlColor(lvl)), fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.Bold),
            )
        }
        LinearProgressIndicator(
            progress = (pct / 100.0).toFloat().coerceIn(0f, 1f),
            modifier = GlanceModifier.fillMaxWidth().height(4.dp).padding(top = 1.dp),
            color = cp(lvlColor(lvl)),
            backgroundColor = cp(TRACK),
        )
        if (!compact) {
            Text(
                text = sub,
                modifier = GlanceModifier.padding(top = 3.dp),
                style = TextStyle(color = cp(DIM), fontSize = 10.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Bar(label: String, pct: Double, right: String, lvl: String = level(pct, 70.0, 90.0)) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = GlanceModifier.width(40.dp),
            style = TextStyle(color = cp(DIM), fontSize = 10.sp, fontWeight = FontWeight.Bold),
        )
        LinearProgressIndicator(
            progress = (pct / 100.0).toFloat().coerceIn(0f, 1f),
            modifier = GlanceModifier.defaultWeight().height(4.dp),
            color = cp(lvlColor(lvl)),
            backgroundColor = cp(TRACK),
        )
        Text(
            text = right,
            modifier = GlanceModifier.width(88.dp),
            style = TextStyle(color = cp(TEXT), fontSize = 11.sp, textAlign = TextAlign.End),
            maxLines = 1,
        )
    }
}
