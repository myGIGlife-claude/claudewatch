package dev.vpsdash

import org.json.JSONObject
import java.util.Locale

data class Session(
    val pid: Int, val cpu: Double, val rss: Long, val childRss: Long,
    val mcp: Int, val age: Long, val cwd: String,
)

data class Alert(val level: String, val msg: String)

data class Stats(
    val host: String, val ts: Long, val uptime: Long, val load: List<Double>,
    val cpuBusy: Double, val cpuUsr: Double, val cpuSys: Double, val cpuIowait: Double, val cpuSteal: Double,
    val cores: List<Double>,
    val memTotal: Long, val memUsed: Long, val memAvail: Long,
    val zramTotal: Long, val zramUsed: Long, val zramRatio: Double,
    val swapTotal: Long, val swapUsed: Long,
    val psiCpu: Double, val psiMem: Double, val psiIo: Double,
    val diskTotal: Long, val diskUsed: Long, val diskRead: Double, val diskWrite: Double,
    val netRx: Double, val netTx: Double,
    val apiOk: Boolean?, val apiMs: Double?,
    val version: String, val sessions: List<Session>,
    val procs: Int, val zombies: Int, val fds: Int, val inotify: Int, val inotifyMax: Int,
    val earlyoom: String, val oomKills: Int?, val failedUnits: Int?,
    val alerts: List<Alert>,
) {
    val memPct get() = pct(memUsed, memTotal)
    val zramPct get() = pct(zramUsed, zramTotal)
    val swapPct get() = pct(swapUsed, swapTotal)
    val diskPct get() = pct(diskUsed, diskTotal)
    val claudeRss get() = sessions.sumOf { it.rss + it.childRss }

    /** "ok" | "warn" | "crit" */
    val health: String
        get() = when {
            apiOk == false || alerts.any { it.level == "crit" } -> "crit"
            alerts.isNotEmpty() -> "warn"
            else -> "ok"
        }

    companion object {
        fun parseOrNull(text: String?): Stats? = text?.let { runCatching { parse(it) }.getOrNull() }

        fun parse(text: String): Stats {
            val j = JSONObject(text)
            val cpu = j.getJSONObject("cpu")
            val mem = j.getJSONObject("mem")
            val zram = j.getJSONObject("zram")
            val swap = j.getJSONObject("swap")
            val psi = j.getJSONObject("psi")
            val disk = j.getJSONObject("disk")
            val net = j.getJSONObject("net")
            val api = j.getJSONObject("api")
            val claude = j.getJSONObject("claude")
            val sys = j.getJSONObject("sys")
            val loadA = j.getJSONArray("load")
            val coresA = cpu.getJSONArray("cores")
            val sessA = claude.getJSONArray("sessions")
            val alertA = j.getJSONArray("alerts")

            return Stats(
                host = j.getString("host"), ts = j.getLong("ts"), uptime = j.getLong("uptime"),
                load = List(loadA.length()) { loadA.getDouble(it) },
                cpuBusy = cpu.getDouble("busy"), cpuUsr = cpu.getDouble("usr"), cpuSys = cpu.getDouble("sys"),
                cpuIowait = cpu.getDouble("iowait"), cpuSteal = cpu.getDouble("steal"),
                cores = List(coresA.length()) { coresA.getDouble(it) },
                memTotal = mem.getLong("total"), memUsed = mem.getLong("used"), memAvail = mem.getLong("avail"),
                zramTotal = zram.getLong("total"), zramUsed = zram.getLong("used"), zramRatio = zram.getDouble("ratio"),
                swapTotal = swap.getLong("total"), swapUsed = swap.getLong("used"),
                psiCpu = psi.getDouble("cpu"), psiMem = psi.getDouble("mem"), psiIo = psi.getDouble("io"),
                diskTotal = disk.getLong("total"), diskUsed = disk.getLong("used"),
                diskRead = disk.getDouble("read"), diskWrite = disk.getDouble("write"),
                netRx = net.getDouble("rx"), netTx = net.getDouble("tx"),
                apiOk = if (api.isNull("ok")) null else api.getBoolean("ok"),
                apiMs = api.dOrNull("tcp_ms"),
                version = claude.optString("version", ""),
                sessions = List(sessA.length()) {
                    val s = sessA.getJSONObject(it)
                    Session(
                        s.getInt("pid"), s.getDouble("cpu"), s.getLong("rss"), s.getLong("child_rss"),
                        s.getInt("mcp"), s.getLong("age"), s.getString("cwd"),
                    )
                },
                procs = sys.getInt("procs"), zombies = sys.getInt("zombies"), fds = sys.getInt("fds"),
                inotify = sys.getInt("inotify"), inotifyMax = sys.getInt("inotify_max"),
                earlyoom = sys.optString("earlyoom", "?"),
                oomKills = sys.iOrNull("oom_kills"), failedUnits = sys.iOrNull("failed_units"),
                alerts = List(alertA.length()) {
                    val a = alertA.getJSONObject(it)
                    Alert(a.getString("level"), a.getString("msg"))
                },
            )
        }
    }
}

private fun JSONObject.dOrNull(k: String) = if (!has(k) || isNull(k)) null else getDouble(k)
private fun JSONObject.iOrNull(k: String) = if (!has(k) || isNull(k)) null else getInt(k)

fun pct(a: Long, b: Long) = if (b > 0) 100.0 * a / b else 0.0

fun level(v: Double, warn: Double, crit: Double) = when {
    v >= crit -> "crit"
    v >= warn -> "warn"
    else -> "ok"
}

/** Most severe of several levels ("crit" > "warn" > "ok"). */
fun worst(vararg levels: String): String =
    levels.maxByOrNull { when (it) { "crit" -> 2; "warn" -> 1; else -> 0 } } ?: "ok"

fun fmtBytes(n: Number): String {
    var v = n.toDouble()
    for (u in listOf("B", "K", "M", "G", "T")) {
        if (kotlin.math.abs(v) < 1024 || u == "T") {
            return if (u == "B" || u == "K") String.format(Locale.US, "%.0f%s", v, u)
            else String.format(Locale.US, "%.1f%s", v, u)
        }
        v /= 1024
    }
    return "?"
}

fun fmtDur(sec: Long): String {
    val d = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60
    return when {
        d > 0 -> "${d}d${h}h"
        h > 0 -> "${h}h${m}m"
        else -> "${m}m"
    }
}

fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
