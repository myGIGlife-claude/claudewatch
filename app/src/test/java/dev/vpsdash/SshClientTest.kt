package dev.vpsdash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class SshClientTest {
    private fun f(m: String?) = SshClient.friendly(RuntimeException(m))

    @Test fun mapsKnownErrors() {
        assertTrue(f("Auth fail for methods 'publickey'").startsWith("Key rejected"))
        assertTrue(f("java.net.SocketTimeoutException: connect timed out").startsWith("Connection timed out"))
        assertTrue(f("timeout: socket is not established").startsWith("Connection timed out"))
        assertTrue(f("java.net.ConnectException: Connection refused").startsWith("Connection refused"))
        assertEquals("Can't resolve host name", SshClient.friendly(UnknownHostException("x")))
        assertEquals("Can't resolve host name", f("java.net.UnknownHostException: x"))
    }

    @Test fun passesThroughOthers() {
        assertEquals("weird", f("weird"))
        assertEquals("RuntimeException", SshClient.friendly(RuntimeException()))
        val h = HostKeyChangedException("a", "b")
        assertEquals(h.message, SshClient.friendly(h))
        val k = KeyLostException()
        assertEquals(k.message, SshClient.friendly(k))
        assertTrue(k.message!!.contains(KeyManager.KEY_LOST_MARK))
    }

    @Test fun notInstalledHint() {
        val hint = "claude-dash isn't installed at /usr/local/bin on the VPS"
        assertEquals(hint, SshClient.badReply("bash: /usr/local/bin/claude-dash: command not found"))
        assertEquals(hint, SshClient.badReply("bash: line 1: /usr/local/bin/claude-dash: No such file or directory"))
        assertEquals("Unexpected reply from server: oops", SshClient.badReply("oops"))
    }
}
