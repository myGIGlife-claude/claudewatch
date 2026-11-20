package dev.vpsdash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsTest {
    private lateinit var ctx: Context
    private fun sp() = ctx.getSharedPreferences("vpsdash", Context.MODE_PRIVATE)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        sp().edit().clear().commit()
    }

    @Test fun migratesFlatKeys() {
        sp().edit().putString("host", "h").putInt("port", 2222).putString("user", "u")
            .putString("fingerprint", "SHA256:x").putString("json", "{}").putLong("time", 5L).commit()
        val l = Prefs.servers(ctx)
        assertEquals(listOf(ServerConfig("default", "h", 2222, "u")), l)
        assertEquals("SHA256:x", Prefs.fingerprint(ctx, "default"))
        assertEquals("{}", Prefs.lastJson(ctx, "default"))
        assertEquals(5L, Prefs.lastTime(ctx, "default"))
        assertFalse(sp().contains("host"))
        assertEquals(l, Prefs.servers(ctx)) // idempotent
    }

    @Test fun hostChangeDropsPinAndCache() {
        val a = ServerConfig("a", "h", 22, "u")
        Prefs.saveServer(ctx, a)
        Prefs.setFingerprint(ctx, "a", "fp")
        Prefs.saveResult(ctx, "a", "{}", "err")
        Prefs.saveServer(ctx, a.copy(name = "renamed")) // same address: keep everything
        assertEquals("fp", Prefs.fingerprint(ctx, "a"))
        assertEquals("{}", Prefs.lastJson(ctx, "a"))
        Prefs.saveServer(ctx, a.copy(port = 2200))
        assertNull(Prefs.fingerprint(ctx, "a"))
        assertNull(Prefs.lastJson(ctx, "a"))
        assertNull(Prefs.lastError(ctx, "a"))
        assertEquals(0L, Prefs.lastTime(ctx, "a"))
    }

    @Test fun deleteRemovesServerStateAndWidgets() {
        Prefs.saveServer(ctx, ServerConfig("a", "h", 22, "u"))
        Prefs.saveServer(ctx, ServerConfig("b", "h2", 22, "u"))
        Prefs.setWidgetServer(ctx, 7, "a")
        Prefs.saveResult(ctx, "a", "{}", null)
        Prefs.deleteServer(ctx, "a")
        assertEquals(listOf("b"), Prefs.servers(ctx).map { it.id })
        assertNull(Prefs.lastJson(ctx, "a"))
        assertNull(Prefs.widgetServer(ctx, 7))
        assertEquals("b", Prefs.server(ctx, "a")?.id) // falls back to the first server
    }
}
