package dev.vpsdash

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class HostKeyChangedException(val expected: String, val actual: String) :
    Exception("Server host key changed! Expected $expected but got $actual. If you rebuilt the VPS, tap 'Trust new host key'.")

object SshClient {
    private const val CMD = "/usr/local/bin/claude-dash --json"

    suspend fun fetch(ctx: Context, cfg: ServerConfig): String = withContext(Dispatchers.IO) {
        val jsch = JSch()
        jsch.addIdentity("vps-dash", KeyManager.privateKey(ctx), null, null)

        val session = jsch.getSession(cfg.user, cfg.host, cfg.port)
        // We verify the host key ourselves below (trust-on-first-use, then pinned).
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey")
        session.timeout = 20_000
        session.connect(15_000)
        try {
            val fp = session.hostKey.getFingerPrint(jsch)
            val pinned = Prefs.fingerprint(ctx, cfg.id)
            when {
                pinned == null -> Prefs.setFingerprint(ctx, cfg.id, fp)
                pinned != fp -> throw HostKeyChangedException(pinned, fp)
            }

            val ch = session.openChannel("exec") as ChannelExec
            ch.setCommand(CMD)
            val err = ByteArrayOutputStream()
            ch.setErrStream(err)
            val input = ch.inputStream
            ch.connect(10_000)
            val out = input.bufferedReader().use { it.readText() }
            ch.disconnect()

            if (!out.trimStart().startsWith("{")) {
                val msg = err.toString().ifBlank { out }.take(300)
                throw IllegalStateException(
                    if (msg.contains("not found")) "claude-dash isn't installed at /usr/local/bin on the VPS"
                    else "Unexpected reply from server: $msg"
                )
            }
            out
        } finally {
            session.disconnect()
        }
    }

    /** Turns JSch's terse errors into something readable. */
    fun friendly(t: Throwable): String {
        val m = t.message ?: t.javaClass.simpleName
        return when {
            t is HostKeyChangedException -> m
            m.contains("Auth fail", true) -> "Key rejected - make sure you ran the install command on the VPS as the same user"
            m.contains("timeout", true) || m.contains("timed out", true) -> "Connection timed out - check host/port and that the VPS is up"
            m.contains("UnknownHost", true) || t is java.net.UnknownHostException -> "Can't resolve host name"
            m.contains("refused", true) -> "Connection refused - is SSH running on that port?"
            else -> m
        }
    }
}
