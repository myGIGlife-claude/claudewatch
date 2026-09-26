"""Run: python3 server/test_claude_dash.py"""
import importlib.util, os

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
print("ok")
