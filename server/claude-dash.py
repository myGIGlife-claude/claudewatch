#!/usr/bin/env python3
"""
claude-dash - real-time text dashboard for a Claude Code server.
Zero dependencies (Python 3 stdlib only). Reads straight from /proc and /sys.

  claude-dash              live dashboard (q = quit, +/- = refresh speed)
  claude-dash --once       print one snapshot (for scripts / cron / ssh)
  claude-dash --ascii      plain ASCII bars (if your terminal shows boxes)
  claude-dash --json       machine-readable snapshot (used by the Android app)

Install:  sudo install -m 755 claude-dash.py /usr/local/bin/claude-dash
"""
import argparse, curses, json, os, shutil, socket, subprocess, sys, threading, time, urllib.request
from datetime import datetime

CLK = os.sysconf("SC_CLK_TCK")
PAGE = os.sysconf("SC_PAGE_SIZE")
NCPU = os.cpu_count() or 1
HOME = os.path.expanduser("~")
FULL, EMPTY = "█", "░"
API_HOST = "api.anthropic.com"
USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
USAGE_CACHE = os.path.join(HOME, ".cache", "claude-dash-usage.json")
CLAUDE_DIR = os.environ.get("CLAUDE_CONFIG_DIR") or os.path.join(HOME, ".claude")
MCP_HINTS = ("node", "npx", "uvx", "uv", "python", "python3", "bun", "deno", "docker")


# ---------------- helpers ----------------
def read(path, default=""):
    try:
        with open(path) as f:
            return f.read()
    except Exception:
        return default


def fb(n):
    """Format bytes."""
    n = float(n)
    for u in ("B", "K", "M", "G", "T"):
        if abs(n) < 1024 or u == "T":
            return f"{n:.0f}{u}" if u in ("B", "K") else f"{n:.1f}{u}"
        n /= 1024


def fdur(sec):
    sec = int(sec)
    d, r = divmod(sec, 86400)
    h, r = divmod(r, 3600)
    m, _ = divmod(r, 60)
    return f"{d}d{h}h" if d else (f"{h}h{m}m" if h else f"{m}m")


def lvl(v, warn, crit):
    return "crit" if v >= crit else "warn" if v >= warn else "ok"


def run(cmd, timeout=5):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout
    except Exception:
        return ""


# ---------------- collectors ----------------
def cpu_times():
    out = {}
    for line in read("/proc/stat").splitlines():
        if line.startswith("cpu"):
            p = line.split()
            out[p[0]] = [int(x) for x in p[1:9]]  # user nice sys idle iowait irq softirq steal
    return out


def meminfo():
    m = {}
    for line in read("/proc/meminfo").splitlines():
        k, v = line.split(":", 1)
        m[k] = int(v.split()[0]) * 1024
    return m


def swaps():
    zram, disk = [0, 0], [0, 0]
    for line in read("/proc/swaps").splitlines()[1:]:
        p = line.split()
        size, used = int(p[2]) * 1024, int(p[3]) * 1024
        tgt = zram if "zram" in p[0] else disk
        tgt[0] += size
        tgt[1] += used
    orig = compr = 0
    try:
        for d in os.listdir("/sys/block"):
            if d.startswith("zram"):
                s = read(f"/sys/block/{d}/mm_stat").split()
                if s:
                    orig += int(s[0])
                    compr += int(s[2])  # mem_used_total
    except Exception:
        pass
    return zram, disk, (orig / compr if compr else 0), compr


def psi():
    out = {}
    for r in ("cpu", "memory", "io"):
        txt = read(f"/proc/pressure/{r}")
        for line in txt.splitlines():
            kind, *vals = line.split()
            kv = dict(v.split("=") for v in vals)
            out[f"{r}_{kind}"] = float(kv.get("avg10", 0))
    return out


def diskio():
    rd = wr = 0
    for line in read("/proc/diskstats").splitlines():
        p = line.split()
        name = p[2]
        if name.startswith(("loop", "ram", "zram", "dm-", "sr")):
            continue
        if not os.path.exists(f"/sys/block/{name}"):  # skip partitions
            continue
        rd += int(p[5]) * 512
        wr += int(p[9]) * 512
    return rd, wr


def netio():
    rx = tx = 0
    for line in read("/proc/net/dev").splitlines()[2:]:
        iface, data = line.split(":", 1)
        if iface.strip() == "lo":
            continue
        d = data.split()
        rx += int(d[0])
        tx += int(d[8])
    return rx, tx


def scan_procs():
    procs = {}
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        s = read(f"/proc/{pid}/stat")
        if not s:
            continue
        r = s.rfind(")")
        comm = s[s.find("(") + 1 : r]
        f = s[r + 2 :].split()
        try:
            procs[int(pid)] = {
                "comm": comm,
                "state": f[0],
                "ppid": int(f[1]),
                "ticks": int(f[11]) + int(f[12]),
                "start": int(f[19]),
                "rss": int(f[21]) * PAGE,
            }
        except (IndexError, ValueError):
            pass
    return procs


def is_claude(pid, p):
    if p["comm"] == "claude":
        return True
    if p["comm"] in ("node", "bun"):
        cmd = read(f"/proc/{pid}/cmdline").replace("\0", " ")
        return "claude-code" in cmd or "/bin/claude" in cmd
    return False


def inotify_usage():
    watches = inst = 0
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        try:
            fds = os.listdir(f"/proc/{pid}/fd")
        except Exception:
            continue
        for fd in fds:
            try:
                if os.readlink(f"/proc/{pid}/fd/{fd}") == "anon_inode:inotify":
                    inst += 1
                    watches += read(f"/proc/{pid}/fdinfo/{fd}").count("inotify wd")
            except Exception:
                pass
    return watches, inst


def _epoch(iso):
    try:
        return int(datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp())
    except Exception:
        return None


def parse_usage(d):
    """Maps the usage endpoint's reply to {session, week, week_opus: {pct, resets}}."""
    out = {}
    for key, name in (("five_hour", "session"), ("seven_day", "week"), ("seven_day_opus", "week_opus")):
        v = d.get(key)
        if isinstance(v, dict) and v.get("utilization") is not None:
            out[name] = {"pct": round(float(v["utilization"]), 1), "resets": _epoch(v.get("resets_at") or "")}
    return out or {"error": "No usage data for this account"}


def claude_usage(max_age=60):
    """Plan usage and reset times from the endpoint Claude Code's /usage uses (undocumented).
    Uses the OAuth token Claude Code keeps in ~/.claude; the token is only sent to Anthropic.
    Cached for max_age seconds so a phone polling every 5s doesn't hammer the API."""
    # ponytail: undocumented endpoint; if Anthropic changes it this degrades to an error string, not a crash
    try:
        with open(USAGE_CACHE) as f:
            c = json.load(f)
        if time.time() - c["at"] < max_age:
            return c["usage"]
    except Exception:
        pass
    try:
        with open(os.path.join(CLAUDE_DIR, ".credentials.json")) as f:
            tok = json.load(f)["claudeAiOauth"]["accessToken"]
    except Exception:
        return {"error": "Claude Code isn't logged in on this server"}
    req = urllib.request.Request(USAGE_URL, headers={
        "Authorization": f"Bearer {tok}", "anthropic-beta": "oauth-2025-04-20", "User-Agent": "claude-dash"})
    try:
        with urllib.request.urlopen(req, timeout=6) as r:
            u = parse_usage(json.load(r))
    except urllib.error.HTTPError as e:
        u = {"error": "Login expired - run claude on the server once" if e.code == 401 else f"Usage API error {e.code}"}
    except Exception:
        u = {"error": "Couldn't reach the usage API"}
    try:
        os.makedirs(os.path.dirname(USAGE_CACHE), exist_ok=True)
        with open(USAGE_CACHE, "w") as f:
            json.dump({"at": time.time(), "usage": u}, f)
    except Exception:
        pass
    return u


# ---------------- slow checks (background thread) ----------------
class SlowChecks(threading.Thread):
    """Things too expensive to run every tick: network probe, systemd, inotify."""

    def __init__(self, every=15):
        super().__init__(daemon=True)
        self.every = every
        self.d = {"api": None, "dns_ms": None, "tcp_ms": None, "earlyoom": "?",
                  "oom_kills": None, "failed": None, "inotify": (0, 0),
                  "version": "", "usage": None, "ready": False}

    def probe_api(self):
        try:
            t0 = time.perf_counter()
            fam, typ, proto, _, addr = socket.getaddrinfo(API_HOST, 443, proto=socket.IPPROTO_TCP)[0]
            t1 = time.perf_counter()
            with socket.socket(fam, typ, proto) as s:
                s.settimeout(5)
                s.connect(addr)
            t2 = time.perf_counter()
            return True, (t1 - t0) * 1000, (t2 - t1) * 1000
        except Exception:
            return False, None, None

    def check_once(self, usage=True):
        if not self.d["version"]:
            claude_bin = shutil.which("claude") or os.path.join(HOME, ".local/bin/claude")
            if os.path.exists(claude_bin):
                self.d["version"] = run([claude_bin, "--version"]).strip().split(" ")[0]
        ok, dns, tcp = self.probe_api()
        self.d.update(api=ok, dns_ms=dns, tcp_ms=tcp)
        self.d["earlyoom"] = run(["systemctl", "is-active", "earlyoom"]).strip() or "?"
        failed = run(["systemctl", "--failed", "--no-legend", "--plain"])
        self.d["failed"] = len([l for l in failed.splitlines() if l.strip()])
        j = run(["journalctl", "-u", "earlyoom", "--since", "-24h", "--no-pager", "-q"], timeout=8)
        self.d["oom_kills"] = j.count("sending SIG") if j or self.d["oom_kills"] is None else self.d["oom_kills"]
        self.d["inotify"] = inotify_usage()
        if usage:
            self.d["usage"] = claude_usage()
        self.d["ready"] = True

    def run(self):
        while True:
            self.check_once()
            time.sleep(self.every)


# ---------------- sampler ----------------
class Sampler:
    def __init__(self):
        self.t = time.time()
        self.cpu = cpu_times()
        self.dio = diskio()
        self.nio = netio()
        self.ticks = {}

    def sample(self):
        now = time.time()
        dt = max(now - self.t, 0.001)
        s = {}

        # CPU
        cur = cpu_times()
        def pct(name):
            a, b = self.cpu.get(name), cur.get(name)
            if not a or not b:
                return 0, 0, 0, 0, 0
            d = [y - x for x, y in zip(a, b)]
            tot = sum(d) or 1
            idle = d[3] + d[4]
            return (100 * (tot - idle) / tot, 100 * (d[0] + d[1]) / tot,
                    100 * (d[2] + d[5] + d[6]) / tot, 100 * d[4] / tot, 100 * d[7] / tot)
        s["cpu"] = pct("cpu")
        s["cores"] = [pct(f"cpu{i}")[0] for i in range(NCPU)]
        self.cpu = cur

        # Memory / swap / pressure
        s["mem"] = meminfo()
        s["zram"], s["dswap"], s["zratio"], s["zcompr"] = swaps()
        s["psi"] = psi()
        s["load"] = [float(x) for x in read("/proc/loadavg").split()[:3]]
        s["uptime"] = float(read("/proc/uptime").split()[0])

        # Disk
        st = os.statvfs("/")
        s["disk"] = (st.f_blocks * st.f_frsize, (st.f_blocks - st.f_bfree) * st.f_frsize)
        d = diskio()
        s["dio"] = ((d[0] - self.dio[0]) / dt, (d[1] - self.dio[1]) / dt)
        self.dio = d

        # Network
        n = netio()
        s["nio"] = ((n[0] - self.nio[0]) / dt, (n[1] - self.nio[1]) / dt)
        self.nio = n

        # Kernel tables
        fnr = read("/proc/sys/fs/file-nr").split()
        s["files"] = (int(fnr[0]), int(fnr[2])) if fnr else (0, 1)
        s["inotify_max"] = int(read("/proc/sys/fs/inotify/max_user_watches", "0") or 0)

        # Processes
        procs = scan_procs()
        cpu_pct = {}
        for pid, p in procs.items():
            prev = self.ticks.get(pid)
            cpu_pct[pid] = 100 * (p["ticks"] - prev) / CLK / dt if prev is not None else 0.0
        self.ticks = {pid: p["ticks"] for pid, p in procs.items()}
        s["zombies"] = sum(1 for p in procs.values() if p["state"] == "Z")
        s["nprocs"] = len(procs)

        children = {}
        for pid, p in procs.items():
            children.setdefault(p["ppid"], []).append(pid)

        claude_pids = {pid for pid, p in procs.items() if is_claude(pid, p)}
        roots = []
        for pid in claude_pids:
            a, nested = procs[pid]["ppid"], False
            while a > 1 and a in procs:
                if a in claude_pids:
                    nested = True
                    break
                a = procs[a]["ppid"]
            if not nested:
                roots.append(pid)

        grouped, sessions = set(), []
        for root in sorted(roots):
            desc, stack = [], list(children.get(root, []))
            while stack:
                c = stack.pop()
                desc.append(c)
                stack.extend(children.get(c, []))
            grouped.add(root)
            grouped.update(desc)
            mcp = [c for c in desc if procs[c]["comm"] in MCP_HINTS]
            try:
                cwd = os.readlink(f"/proc/{root}/cwd").replace(HOME, "~")
            except Exception:
                cwd = "?"
            sessions.append({
                "pid": root,
                "cpu": cpu_pct[root] + sum(cpu_pct[c] for c in desc),
                "rss": procs[root]["rss"],
                "child_rss": sum(procs[c]["rss"] for c in desc),
                "nchild": len(desc),
                "nmcp": len(mcp),
                "age": s["uptime"] - procs[root]["start"] / CLK,
                "cwd": cwd,
            })
        s["sessions"] = sessions

        others = [(p["rss"], cpu_pct[pid], p["comm"], pid) for pid, p in procs.items()
                  if pid not in grouped and p["rss"] > 0]
        s["top"] = sorted(others, reverse=True)[:5]

        self.t = now
        return s


# ---------------- health rules ----------------
def health(s, slow):
    a = []
    m = s["mem"]
    avail = 100 * m["MemAvailable"] / m["MemTotal"]
    if avail < 15:
        a.append((lvl(-avail, -15, -8), f"Low RAM: {avail:.0f}% available"))
    p = s["psi"]
    if p.get("memory_some", 0) >= 5:
        a.append((lvl(p["memory_some"], 5, 20), f"Memory pressure {p['memory_some']:.1f}% (processes stalling)"))
    if p.get("io_some", 0) >= 20:
        a.append((lvl(p["io_some"], 20, 50), f"I/O pressure {p['io_some']:.1f}%"))
    if p.get("cpu_some", 0) >= 50:
        a.append((lvl(p["cpu_some"], 50, 80), f"CPU contention {p['cpu_some']:.1f}%"))
    steal = s["cpu"][4]
    if steal >= 10:
        a.append((lvl(steal, 10, 25), f"CPU steal {steal:.0f}% - noisy neighbour on host"))
    total, used = s["disk"]
    dp = 100 * used / total if total else 0
    if dp >= 80:
        a.append((lvl(dp, 80, 90), f"Disk {dp:.0f}% full"))
    if s["dswap"][1] > 512 * 1024**2:
        a.append(("warn", f"Disk swap in use ({fb(s['dswap'][1])}) - zram overflowed"))
    fo, fm = s["files"]
    if fm and fo / fm > 0.8:
        a.append(("warn", "Open file handles above 80%"))
    if s["zombies"] > 20:
        a.append(("warn", f"{s['zombies']} zombie processes"))
    if slow["ready"]:
        if slow["api"] is False:
            a.append(("crit", f"Cannot reach {API_HOST}"))
        elif slow["tcp_ms"] and slow["tcp_ms"] > 300:
            a.append(("warn", f"Slow API connect: {slow['tcp_ms']:.0f}ms"))
        if slow["earlyoom"] != "active":
            a.append(("warn", f"earlyoom is {slow['earlyoom']}"))
        if slow["oom_kills"]:
            a.append(("warn", f"earlyoom killed {slow['oom_kills']} process(es) in 24h"))
        if slow["failed"]:
            a.append(("warn", f"{slow['failed']} failed systemd unit(s)"))
        w, _ = slow["inotify"]
        if s["inotify_max"] and w / s["inotify_max"] > 0.8:
            a.append(("warn", "inotify watches above 80%"))
    return a


# ---------------- layout (shared by curses + --once) ----------------
def bar(label, pct, width, extra="", style=None):
    pct = max(0.0, min(100.0, pct))
    style = style or lvl(pct, 70, 90)
    tail = f" {pct:5.1f}% {extra:<17}"
    bw = max(8, width - 6 - len(tail) - 2)
    n = int(round(bw * pct / 100))
    return [(f"{label:<6}", "head"), ("[", "dim"), (FULL * n, style),
            (EMPTY * (bw - n), "dim"), ("]", "dim"), (tail, "norm")]


def build(s, slow, width, interval):
    L = []
    host = socket.gethostname()
    ld = s["load"]
    L.append([(" CLAUDE-DASH ", "title"), (f" {host}  up {fdur(s['uptime'])}  ", "norm"),
              (f"load {ld[0]:.2f} {ld[1]:.2f} {ld[2]:.2f}", lvl(ld[0] / NCPU * 100, 80, 120)),
              (f"  {time.strftime('%H:%M:%S')}", "dim")])

    # Health
    alerts = health(s, slow)
    if alerts:
        for st, msg in alerts[:5]:
            L.append([("✗ " if st == "crit" else "! ", st), (msg, st)])
    else:
        L.append([("✓ Healthy", "ok"), ("  - no issues detected", "dim")])
    L.append([])

    # CPU
    busy, usr, sysp, iow, steal = s["cpu"]
    L.append(bar("CPU", busy, width))
    L.append([("      usr ", "dim"), (f"{usr:.0f}%", "norm"), ("  sys ", "dim"), (f"{sysp:.0f}%", "norm"),
              ("  iowait ", "dim"), (f"{iow:.0f}%", lvl(iow, 10, 25)),
              ("  steal ", "dim"), (f"{steal:.0f}%", lvl(steal, 5, 15))])
    row = [("      ", "")]
    for i, c in enumerate(s["cores"]):
        row.append((f"c{i}:", "dim"))
        row.append((f"{c:3.0f}% ", lvl(c, 70, 90)))
        if len("".join(t for t, _ in row)) > width - 10:
            L.append(row)
            row = [("      ", "")]
    if len(row) > 1:
        L.append(row)

    # Memory
    m = s["mem"]
    tot, av = m["MemTotal"], m["MemAvailable"]
    L.append(bar("MEM", 100 * (tot - av) / tot, width, f"{fb(tot - av)}/{fb(tot)}"))
    cache = m.get("Cached", 0) + m.get("Buffers", 0) + m.get("SReclaimable", 0)
    L.append([("      avail ", "dim"), (fb(av), "norm"), ("  cache ", "dim"), (fb(cache), "norm"),
              ("  dirty ", "dim"), (fb(m.get("Dirty", 0)), "norm")])
    zt, zu = s["zram"]
    if zt:
        L.append(bar("ZRAM", 100 * zu / zt, width, f"{fb(zu)} ({s['zratio']:.1f}x)", lvl(100 * zu / zt, 60, 85)))
    dt_, du = s["dswap"]
    if dt_:
        L.append(bar("SWAP", 100 * du / dt_, width, fb(du), lvl(100 * du / dt_, 10, 40)))

    # Pressure
    p = s["psi"]
    if p:
        L.append([("PSI   ", "head"), ("cpu ", "dim"), (f"{p.get('cpu_some', 0):.1f}", lvl(p.get("cpu_some", 0), 30, 60)),
                  ("  mem ", "dim"), (f"{p.get('memory_some', 0):.1f}", lvl(p.get("memory_some", 0), 2, 10)),
                  ("  io ", "dim"), (f"{p.get('io_some', 0):.1f}", lvl(p.get("io_some", 0), 10, 30)),
                  ("  (% time stalled, 10s)", "dim")])

    # Disk + net
    t, u = s["disk"]
    L.append(bar("DISK", 100 * u / t if t else 0, width, f"{fb(u)}/{fb(t)}", lvl(100 * u / t if t else 0, 80, 90)))
    r, w = s["dio"]
    L.append([("      read ", "dim"), (f"{fb(r)}/s", "norm"), ("  write ", "dim"), (f"{fb(w)}/s", "norm")])
    rx, tx = s["nio"]
    api = []
    if slow["ready"]:
        if slow["api"]:
            api = [("  API ", "dim"), (f"{slow['tcp_ms']:.0f}ms", lvl(slow["tcp_ms"], 150, 300)),
                   (f" (dns {slow['dns_ms']:.0f})", "dim")]
        else:
            api = [("  API ", "dim"), ("DOWN", "crit")]
    else:
        api = [("  API ", "dim"), ("checking…", "dim")]
    L.append([("NET   ", "head"), ("↓ ", "dim"), (f"{fb(rx)}/s", "norm"), ("  ↑ ", "dim"), (f"{fb(tx)}/s", "norm")] + api)
    L.append([])

    # Plan usage
    u = slow.get("usage") or {}
    if u.get("error"):
        L.append([("USAGE ", "head"), (u["error"], "dim")])
    elif u:
        row = [("USAGE ", "head")]
        for k, name in (("session", "5h"), ("week", "week"), ("week_opus", "opus")):
            if k in u:
                x = u[k]
                when = time.strftime("%a %H:%M", time.localtime(x["resets"])) if x.get("resets") else "?"
                row += [(f"{name} ", "dim"), (f"{x['pct']:.0f}%", lvl(x["pct"], 70, 90)), (f" resets {when}   ", "dim")]
        L.append(row)
    L.append([])

    # Claude sessions
    ss = s["sessions"]
    tot_rss = sum(x["rss"] + x["child_rss"] for x in ss)
    L.append([("CLAUDE ", "title"), (f" {len(ss)} running", "ok" if ss else "dim"),
              (f"  {slow['version']}" if slow["version"] else "", "dim"),
              (f"  total {fb(tot_rss)}", "norm")])
    if ss:
        L.append([("  PID     CPU   RSS  +KIDS(MCP)      AGE  DIR", "dim")])
        for x in ss:
            kids = f"{fb(x['child_rss'])}({x['nmcp']})"
            L.append([(f"  {x['pid']:<7}", "norm"),
                      (f"{x['cpu']:4.0f}%", lvl(x["cpu"], 150, 300)),
                      (f" {fb(x['rss']):>5}", lvl(x["rss"] / 1024**3, 1.5, 3)),
                      (f"  {kids:<11}", "norm"),
                      (f"{fdur(x['age']):>6}", "dim"),
                      (f"  {x['cwd']}", "norm")])
    else:
        L.append([("  no Claude Code processes found", "dim")])
    L.append([])

    # System tables
    fo, fm = s["files"]
    w_, inst = slow["inotify"]
    eo = slow["earlyoom"]
    L.append([("SYS   ", "head"), ("procs ", "dim"), (str(s["nprocs"]), "norm"),
              ("  zombies ", "dim"), (str(s["zombies"]), lvl(s["zombies"], 5, 20)),
              ("  fds ", "dim"), (f"{fo:,}", "norm"),
              ("  inotify ", "dim"), (f"{w_:,}/{s['inotify_max']:,}" if slow["ready"] else "…", "norm")])
    L.append([("      ", ""), ("earlyoom ", "dim"), (eo, "ok" if eo == "active" else "warn"),
              ("  kills 24h ", "dim"), (str(slow["oom_kills"]) if slow["oom_kills"] is not None else "?",
                                        "warn" if slow["oom_kills"] else "ok"),
              ("  failed units ", "dim"), (str(slow["failed"]) if slow["failed"] is not None else "?",
                                           "warn" if slow["failed"] else "ok")])
    L.append([])

    # Top other processes
    L.append([("TOP MEMORY (outside Claude)", "title")])
    for rss, cpu, comm, pid in s["top"]:
        L.append([(f"  {fb(rss):>6} ", lvl(rss / 1024**3, 1, 2)), (f"{cpu:4.0f}% ", lvl(cpu, 80, 150)),
                  (f"{comm} ", "norm"), (f"[{pid}]", "dim")])
    L.append([])
    L.append([(f"q quit   +/- refresh ({interval:g}s)", "dim")])
    return L


# ---------------- renderers ----------------
def run_curses(stdscr, interval):
    curses.curs_set(0)
    curses.use_default_colors()
    colors = {"ok": curses.COLOR_GREEN, "warn": curses.COLOR_YELLOW, "crit": curses.COLOR_RED,
              "head": curses.COLOR_CYAN, "title": curses.COLOR_MAGENTA, "dim": curses.COLOR_BLUE}
    attr = {"norm": curses.A_NORMAL, "": curses.A_NORMAL}
    for i, (k, c) in enumerate(colors.items(), start=1):
        curses.init_pair(i, c, -1)
        attr[k] = curses.color_pair(i) | (curses.A_BOLD if k in ("crit", "title", "head") else 0)
    attr["dim"] = curses.color_pair(list(colors).index("dim") + 1)

    slow = SlowChecks()
    slow.start()
    sampler = Sampler()
    stdscr.timeout(100)
    time.sleep(0.5)
    next_t = 0
    s = None
    while True:
        if time.time() >= next_t:
            s = sampler.sample()
            next_t = time.time() + interval
            h, w = stdscr.getmaxyx()
            stdscr.erase()
            for y, line in enumerate(build(s, slow.d, w - 1, interval)):
                if y >= h - 1:
                    break
                x = 0
                for text, st in line:
                    if x >= w - 1:
                        break
                    try:
                        stdscr.addnstr(y, x, text, w - 1 - x, attr.get(st, 0))
                    except curses.error:
                        pass
                    x += len(text)
            stdscr.refresh()
        k = stdscr.getch()
        if k in (ord("q"), ord("Q"), 27):
            return
        if k in (ord("+"), ord("=")):
            interval = min(10, interval + 0.5); next_t = 0
        if k in (ord("-"), ord("_")):
            interval = max(0.5, interval - 0.5); next_t = 0
        if k == curses.KEY_RESIZE:
            next_t = 0


def run_once(width):
    slow = SlowChecks()
    slow.start()
    sampler = Sampler()
    t0 = time.time()
    while not slow.d["ready"] and time.time() - t0 < 12:
        time.sleep(0.2)
    time.sleep(max(0, 1 - (time.time() - t0)))
    s = sampler.sample()
    for line in build(s, slow.d, width, 0)[:-1]:
        print("".join(t for t, _ in line))


def to_json(s, slow):
    r = lambda x: None if x is None else round(x, 1)
    m = s["mem"]
    busy, usr, sysp, iow, steal = s["cpu"]
    p = s["psi"]
    return {
        "host": socket.gethostname(), "ts": int(time.time()), "uptime": int(s["uptime"]),
        "load": s["load"],
        "cpu": {"busy": r(busy), "usr": r(usr), "sys": r(sysp), "iowait": r(iow), "steal": r(steal),
                "cores": [r(c) for c in s["cores"]]},
        "mem": {"total": m["MemTotal"], "avail": m["MemAvailable"], "used": m["MemTotal"] - m["MemAvailable"]},
        "zram": {"total": s["zram"][0], "used": s["zram"][1], "ratio": round(s["zratio"], 2)},
        "swap": {"total": s["dswap"][0], "used": s["dswap"][1]},
        "psi": {"cpu": p.get("cpu_some", 0), "mem": p.get("memory_some", 0), "io": p.get("io_some", 0)},
        "disk": {"total": s["disk"][0], "used": s["disk"][1], "read": r(s["dio"][0]), "write": r(s["dio"][1])},
        "net": {"rx": r(s["nio"][0]), "tx": r(s["nio"][1])},
        "api": {"ok": slow["api"], "tcp_ms": r(slow["tcp_ms"]), "dns_ms": r(slow["dns_ms"])},
        "claude": {"version": slow["version"], "sessions": [
            {"pid": x["pid"], "cpu": r(x["cpu"]), "rss": x["rss"], "child_rss": x["child_rss"],
             "mcp": x["nmcp"], "age": int(x["age"]), "cwd": x["cwd"]} for x in s["sessions"]]},
        "sys": {"procs": s["nprocs"], "zombies": s["zombies"], "fds": s["files"][0],
                "inotify": slow["inotify"][0], "inotify_max": s["inotify_max"],
                "earlyoom": slow["earlyoom"], "oom_kills": slow["oom_kills"], "failed_units": slow["failed"]},
        "usage": slow.get("usage"),
        "alerts": [{"level": l, "msg": msg} for l, msg in health(s, slow)],
    }


def run_json():
    t0 = time.time()
    slow = SlowChecks()
    t = threading.Thread(target=slow.check_once, args=(False,), daemon=True)
    t.start()
    tu = threading.Thread(target=lambda: slow.d.update(usage=claude_usage()), daemon=True)
    tu.start()
    sampler = Sampler()
    time.sleep(1)
    t.join(10)
    tu.join(max(0.0, 10 - (time.time() - t0)))
    print(json.dumps(to_json(sampler.sample(), slow.d), separators=(",", ":")))


def main():
    global FULL, EMPTY
    ap = argparse.ArgumentParser(description="Real-time dashboard for a Claude Code server")
    ap.add_argument("-i", "--interval", type=float, default=2.0, help="refresh seconds (default 2)")
    ap.add_argument("--once", action="store_true", help="print one snapshot and exit")
    ap.add_argument("--json", action="store_true", help="print one JSON snapshot and exit")
    ap.add_argument("--ascii", action="store_true", help="ASCII bars instead of block characters")
    a = ap.parse_args()
    if a.ascii:
        FULL, EMPTY = "#", "."
    if not os.path.exists("/proc/stat"):
        sys.exit("claude-dash needs Linux (/proc).")
    if a.json:
        run_json()
    elif a.once or not sys.stdout.isatty():
        run_once(shutil.get_terminal_size((80, 24)).columns)
    else:
        os.environ.setdefault("ESCDELAY", "25")
        curses.wrapper(run_curses, a.interval)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
