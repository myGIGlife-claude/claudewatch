package dev.vpsdash

import android.content.Context

data class ServerConfig(val host: String, val port: Int, val user: String)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("vpsdash", Context.MODE_PRIVATE)

    fun config(c: Context): ServerConfig? {
        val p = sp(c)
        val host = p.getString("host", null) ?: return null
        val user = p.getString("user", null) ?: return null
        return ServerConfig(host, p.getInt("port", 22), user)
    }

    fun saveConfig(c: Context, cfg: ServerConfig) {
        val old = config(c)
        val e = sp(c).edit()
            .putString("host", cfg.host)
            .putInt("port", cfg.port)
            .putString("user", cfg.user)
        // New server => forget the old server's host key fingerprint
        if (old == null || old.host != cfg.host || old.port != cfg.port) e.remove("fingerprint")
        e.apply()
    }

    fun fingerprint(c: Context): String? = sp(c).getString("fingerprint", null)
    fun setFingerprint(c: Context, fp: String?) {
        sp(c).edit().apply { if (fp == null) remove("fingerprint") else putString("fingerprint", fp) }.apply()
    }

    fun saveResult(c: Context, json: String?, error: String?) {
        val e = sp(c).edit()
        if (json != null) e.putString("json", json).putLong("time", System.currentTimeMillis())
        if (error != null) e.putString("error", error) else e.remove("error")
        e.apply()
    }

    fun lastJson(c: Context): String? = sp(c).getString("json", null)
    fun lastTime(c: Context): Long = sp(c).getLong("time", 0L)
    fun lastError(c: Context): String? = sp(c).getString("error", null)
}
