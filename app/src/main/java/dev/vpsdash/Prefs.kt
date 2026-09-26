package dev.vpsdash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ServerConfig(val id: String, val host: String, val port: Int, val user: String, val name: String = "") {
    val label get() = name.ifBlank { host }

    companion object {
        fun newId() = UUID.randomUUID().toString().take(8)
    }
}

/** Per-server settings and cached results, keyed by server id. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("vpsdash", Context.MODE_PRIVATE)

    fun servers(c: Context): List<ServerConfig> {
        migrate(c)
        val a = JSONArray(sp(c).getString("servers", "[]"))
        return List(a.length()) {
            val o = a.getJSONObject(it)
            ServerConfig(o.getString("id"), o.getString("host"), o.getInt("port"), o.getString("user"), o.optString("name"))
        }
    }

    /** The server with this id, or the first one if it's gone (or id is null). */
    fun server(c: Context, id: String?): ServerConfig? = servers(c).let { l -> l.find { it.id == id } ?: l.firstOrNull() }

    fun saveServer(c: Context, cfg: ServerConfig) {
        val list = servers(c)
        val old = list.find { it.id == cfg.id }
        val next = if (old == null) list + cfg else list.map { if (it.id == cfg.id) cfg else it }
        val e = sp(c).edit().putString("servers", toJson(next))
        // New address => forget the old host key fingerprint
        if (old == null || old.host != cfg.host || old.port != cfg.port) e.remove("fp_${cfg.id}")
        e.apply()
    }

    fun deleteServer(c: Context, id: String) {
        val e = sp(c).edit().putString("servers", toJson(servers(c).filter { it.id != id }))
        listOf("fp_", "json_", "time_", "error_").forEach { e.remove(it + id) }
        e.apply()
    }

    private fun toJson(l: List<ServerConfig>) = JSONArray(l.map {
        JSONObject().put("id", it.id).put("host", it.host).put("port", it.port).put("user", it.user).put("name", it.name)
    }).toString()

    /** v1.0-1.5 stored a single server in flat keys; move it into the list. */
    private fun migrate(c: Context) {
        val p = sp(c)
        if (p.contains("servers") || !p.contains("host")) return
        val id = "default"
        val cfg = ServerConfig(id, p.getString("host", "")!!, p.getInt("port", 22), p.getString("user", "")!!)
        val e = p.edit().putString("servers", toJson(listOf(cfg)))
        p.getString("fingerprint", null)?.let { e.putString("fp_$id", it) }
        p.getString("json", null)?.let { e.putString("json_$id", it) }
        e.putLong("time_$id", p.getLong("time", 0L))
        listOf("host", "port", "user", "fingerprint", "json", "time", "error").forEach { e.remove(it) }
        e.commit()
    }

    fun fingerprint(c: Context, id: String): String? = sp(c).getString("fp_$id", null)
    fun setFingerprint(c: Context, id: String, fp: String?) {
        sp(c).edit().apply { if (fp == null) remove("fp_$id") else putString("fp_$id", fp) }.apply()
    }

    fun saveResult(c: Context, id: String, json: String?, error: String?) {
        val e = sp(c).edit()
        if (json != null) e.putString("json_$id", json).putLong("time_$id", System.currentTimeMillis())
        if (error != null) e.putString("error_$id", error) else e.remove("error_$id")
        e.apply()
    }

    fun lastJson(c: Context, id: String): String? = sp(c).getString("json_$id", null)
    fun lastTime(c: Context, id: String): Long = sp(c).getLong("time_$id", 0L)
    fun lastError(c: Context, id: String): String? = sp(c).getString("error_$id", null)

    fun widgetServer(c: Context, appWidgetId: Int): String? = sp(c).getString("widget_$appWidgetId", null)
    fun setWidgetServer(c: Context, appWidgetId: Int, serverId: String) {
        sp(c).edit().putString("widget_$appWidgetId", serverId).apply()
    }
}
