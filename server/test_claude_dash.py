"""Run: python3 server/test_claude_dash.py"""
import importlib.util, json, os

spec = importlib.util.spec_from_file_location("cd", os.path.join(os.path.dirname(__file__), "claude-dash.py"))
cd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cd)

u = cd.parse_usage({
    "five_hour": {"utilization": 62.0, "resets_at": "2026-09-26T15:00:00.123456+00:00"},
    "seven_day": {"utilization": 30, "resets_at": "2026-09-30T09:00:00Z"},
    "seven_day_opus": None,
})
assert u == {"session": {"pct": 62.0, "resets": 1790434800}, "week": {"pct": 30.0, "resets": 1790758800}}, u
assert "error" in cd.parse_usage({})

# ---- parse_usage / _epoch edge cases ----
assert cd.parse_usage({"five_hour": None, "seven_day": None}) == {"error": "No usage data for this account"}
assert cd.parse_usage({"five_hour": {"utilization": None}}) == {"error": "No usage data for this account"}
assert cd.parse_usage({"five_hour": {"utilization": 5}}) == {"session": {"pct": 5.0, "resets": None}}
z = "2026-09-30T09:00:00Z"
assert cd._epoch(z) == cd._epoch("2026-09-30T09:00:00+00:00") == 1790758800
for frac in (".5", ".123", ".123456", ".123456789", ".1234567890123"):
    assert cd._epoch("2026-09-30T09:00:00" + frac + "Z") == 1790758800, frac
for bad in ("", "nope", None, 5):
    assert cd._epoch(bad) is None, bad
try:
    cd.parse_usage({"five_hour": {"utilization": "abc"}})
    raise SystemExit("expected ValueError")
except ValueError:
    pass  # claude_usage turns this into a "format" error

# ---- /proc parsing with fixtures ----
orig_read = cd.read
cd.read = lambda path, default="": {
    "/proc/stat": "cpu  1 2 3 4 5 6 7 8 0 0\ncpu0 1 2 3 4 5 6 7 8 0 0\ncpu2 1 1 1 1 1 1 1 1 0 0\nintr 5\n",
    "/proc/meminfo": "Cached:  10 kB\nMemFree: 5 kB\n",
}.get(path, default)
ct = cd.cpu_times()
assert set(ct) == {"cpu", "cpu0", "cpu2"} and ct["cpu0"] == [1, 2, 3, 4, 5, 6, 7, 8], ct
mi = cd.meminfo()
assert mi["MemTotal"] == 0 and mi["MemAvailable"] == 5 * 1024  # missing keys don't raise
cd.read = orig_read

# ---- Sampler: per-session cpu is a real delta on the first sample, cores follow /proc/stat keys ----
sm = cd.Sampler()
assert sm.ticks, "ticks should be seeded"
s = sm.sample()
assert len(s["cores"]) == len([k for k in cd.cpu_times() if k != "cpu"])
assert s["dio"][0] >= 0 and s["nio"][1] >= 0
sm.nio = (10**15, 10**15)  # counter went backwards
assert sm.sample()["nio"] == (0.0, 0.0)

# ---- to_json: keys Stats.kt requires, JSON round trip ----
slow = {"api": True, "dns_ms": 1.0, "tcp_ms": 2.0, "earlyoom": "not installed", "oom_kills": None, "failed": None,
        "inotify": (0, 0), "version": "x", "usage": {"error": "Usage check timed out"}, "ready": True}
j = json.loads(json.dumps(cd.to_json(s, slow)))
for k in ("host", "ts", "uptime", "load", "cpu", "mem", "zram", "swap", "psi", "disk", "net", "api", "claude", "sys",
          "usage", "alerts"):
    assert k in j, k
for k in ("busy", "usr", "sys", "iowait", "steal", "cores"):
    assert k in j["cpu"], k
assert j["sys"]["oom_kills"] is None and j["sys"]["failed_units"] is None
assert not any("earlyoom" in a["msg"] for a in j["alerts"])  # no permanent yellow pill without earlyoom
print("ok")
