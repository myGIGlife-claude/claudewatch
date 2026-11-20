package dev.vpsdash

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    private val golden = javaClass.getResource("/golden.json")!!.readText()

    private fun mutate(f: (JSONObject) -> Unit) = JSONObject(golden).also(f).toString()

    @Test fun parsesGolden() {
        val s = Stats.parse(golden)
        assertEquals("test-host", s.host)
        assertEquals(3, s.load.size)
        assertTrue(s.cores.isNotEmpty())
        assertEquals(2, s.sessions.size)
        assertEquals("~/projects/demo", s.sessions[0].cwd)
        assertEquals("2.0.0", s.version)
        assertNull(s.oomKills)
        assertEquals(1, s.alerts.size)
        assertEquals("warn", s.health)
        assertEquals(41.5, s.usage!!.session!!.pct, 0.0)
        assertEquals(1790758800L, s.usage!!.week!!.resets)
        assertNull(s.usage!!.weekOpus)
        assertNull(s.usage!!.error)
        assertEquals(s.claudeRss, s.sessions.sumOf { it.rss + it.childRss })
    }

    @Test fun nullUsageIsOk() {
        val s = Stats.parseOrNull(mutate { it.put("usage", JSONObject.NULL) })!!
        assertNull(s.usage)
    }

    @Test fun usageError() {
        val u = Stats.parseOrNull(mutate { it.put("usage", JSONObject().put("error", "Usage check timed out")) })!!.usage!!
        assertEquals("Usage check timed out", u.error)
        assertNull(u.session)
    }

    @Test fun emptyLists() {
        val s = Stats.parseOrNull(mutate {
            it.getJSONObject("cpu").put("cores", JSONArray())
            it.getJSONObject("claude").put("sessions", JSONArray())
            it.put("alerts", JSONArray())
        })!!
        assertTrue(s.cores.isEmpty() && s.sessions.isEmpty() && s.alerts.isEmpty())
        assertEquals("ok", s.health)
    }

    @Test fun unknownCountsAndApi() {
        val s = Stats.parseOrNull(mutate {
            it.getJSONObject("sys").put("failed_units", JSONObject.NULL)
            it.getJSONObject("api").put("ok", JSONObject.NULL)
        })!!
        assertNull(s.failedUnits)
        assertNull(s.apiOk)
    }

    @Test fun missingKeyOrGarbageIsNull() {
        assertNull(Stats.parseOrNull(mutate { it.remove("mem") }))
        assertNull(Stats.parseOrNull(mutate { it.remove("sys") }))
        assertNull(Stats.parseOrNull("not json"))
        assertNull(Stats.parseOrNull(null))
        assertNotNull(Stats.parseOrNull(golden))
    }

    @Test fun apiDownIsCrit() {
        assertEquals("crit", Stats.parse(mutate { it.getJSONObject("api").put("ok", false) }).health)
    }

    @Test fun levelBoundaries() {
        assertEquals("ok", level(69.9, 70.0, 90.0))
        assertEquals("warn", level(70.0, 70.0, 90.0))
        assertEquals("warn", level(89.9, 70.0, 90.0))
        assertEquals("crit", level(90.0, 70.0, 90.0))
        assertEquals("crit", worst("ok", "crit", "warn"))
        assertEquals("ok", worst())
    }

    @Test fun pctGuardsZero() {
        assertEquals(0.0, pct(5, 0), 0.0)
        assertEquals(25.0, pct(50, 200), 0.0)
    }

    @Test fun bytes() {
        assertEquals("0B", fmtBytes(0))
        assertEquals("1023B", fmtBytes(1023))
        assertEquals("1K", fmtBytes(1024))
        assertEquals("1.0M", fmtBytes(1024 * 1024))
        assertEquals("1.5G", fmtBytes(1536L * 1024 * 1024))
        assertEquals("2048.0T", fmtBytes(2048L * 1024 * 1024 * 1024 * 1024))
    }

    @Test fun durations() {
        assertEquals("0m", fmtDur(59))
        assertEquals("1m", fmtDur(60))
        assertEquals("1h0m", fmtDur(3600))
        assertEquals("1d1h", fmtDur(86400 + 3600))
    }

    @Test fun until() {
        val now = System.currentTimeMillis() / 1000
        assertEquals("now", fmtUntil(now - 5))
        assertEquals("now", fmtUntil(now))
        assertEquals("3h0m", fmtUntil(now + 3 * 3600 + 30))
        assertEquals("1d0h", fmtUntil(now + 86400 + 30))
    }
}
