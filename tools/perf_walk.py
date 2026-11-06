#!/usr/bin/env python3
"""Scripted remote-control walkthroughs with frame timing, recompositions and request counts
(performance plan R0 ③–⑤).

Each scenario cold-starts zmby on home (or starts from where the previous step left it), runs a
fixed key sequence and reports, for the measured part only:

- frames from `dumpsys gfxinfo` (reset right before): total, janky %, p50/p90/p99, frames over 33 ms;
- recompositions per traced block (debug builds log running totals; see Diagnostics.kt);
- back-to-home timings (`… ms after back` lines) and JankStats long-frame lines;
- the mock requests in that span, when --mock is given (marks for tools/request_count.py).

Keys go through a single long-lived input path, never one `adb shell input` per key (each starts a
Java process that competes with the app, see the emulator guide):

- `--input monkey` (default): `monkey --port` on the device, one process for the whole run; works on
  emulators without a window, which have no writable input device;
- `--input /dev/input/eventN`: `sendevent` to a writable virtual input device (the Z9X 8K's
  `Android Virtual input`); the whole sequence runs in one `adb shell`.

Only use mock servers on emulators; the output carries screen names, counts and timings, no server
data. Frame numbers from emulators are only relative; conclusions need the box and a release build.

    python3 -I tools/perf_walk.py --serial emulator-5582 --mock http://127.0.0.1:8096 --out /tmp/walk
    python3 -I tools/perf_walk.py --serial <box-ip>:5555 --input /dev/input/event8 --settle 12000 --player-guard <box-ip> --out /tmp/box.json
    python3 -I tools/perf_walk.py --list
    python3 -I tools/perf_walk.py --shots /tmp/walk keys DOWN DOWN CENTER   # send keys, save the screen
"""
import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.request

PKG = "app.zmby"
ACTIVITY = "app.zmby/.MainActivity"
# Android key codes (monkey) and Linux input codes (sendevent).
ANDROID = {"UP": 19, "DOWN": 20, "LEFT": 21, "RIGHT": 22, "CENTER": 23, "BACK": 4, "MENU": 82}
# BACK is KEY_ESC: the Z9X 8K's virtual input device has no KEY_BACK, and Generic.kcm falls back from
# ESCAPE to BACK (KEY_BACK there is silently dropped).
LINUX = {"UP": 103, "DOWN": 108, "LEFT": 105, "RIGHT": 106, "CENTER": 28, "BACK": 1, "MENU": 139}


def steps(spec):
    """'DOWN RIGHT*6 LEFT*6 wait:2000' → [('key','DOWN'), …, ('wait',2000)]; keys are 550 ms apart unless @ms."""
    out = []
    for tok in spec.split():
        if tok.startswith("wait:"):
            out.append(("wait", int(tok[5:])))
            continue
        gap = 550
        if "@" in tok:
            tok, g = tok.split("@")
            gap = int(g)
        name, _, n = tok.partition("*")
        for _ in range(int(n or 1)):
            out.append(("key", name.upper(), gap))
    return out


# name: (start, prep, measured, tail ms, what it shows). start: 'cold' (force-stop, launch, wait for home)
# or 'warm' (continue from the previous scenario's screen). Paths follow the --multi mock's data on a
# store filled by a first run: the hero's 详情 has initial focus (the hero rotates, so it is never
# opened); 在看 lists 漫长的季节, 奥本海默, 沙丘2, 长篇番剧; Menu opens the side bar on 首页
# (then 在看, 电影, 剧集, 收藏, 待看, 我的).
SCENARIOS = {
    "home-cold": ("cold", "", "wait:25000", 0, "requests of a cold start with a filled store, idle on home"),
    "home-scroll": ("cold", "wait:10000", "DOWN RIGHT*3 LEFT*3 DOWN RIGHT*6 LEFT*6 DOWN RIGHT*6 LEFT*6 UP*3", 1500, "rows after the refresh settled"),
    "home-refresh-move": ("cold", "", "DOWN@350 RIGHT*3@350 LEFT*3@350 DOWN@350 RIGHT*8@350 LEFT*8@350 UP*2@350", 1500, "moving while entering home refreshes"),
    "detail-open": ("cold", "wait:8000 DOWN RIGHT*2", "CENTER wait:8000", 0, "opening 沙丘2 from 在看"),
    "detail-scroll": ("warm", "", "DOWN*8 UP*10", 1500, "detail sections down and up (accordion)"),
    "detail-back": ("warm", "", "BACK wait:4000", 0, "back to home: first frame, focus restore, then 3 s"),
    "detail-reopen": ("warm", "", "CENTER wait:8000", 0, "the same detail again (caches)"),
    "series-open": ("cold", "wait:8000 DOWN", "CENTER wait:8000", 0, "opening 漫长的季节 from 在看"),
    "series-move": ("warm", "DOWN wait:1500", "RIGHT*6@800 LEFT*6@800 wait:3000", 0, "moving along the episode row"),
    "series-back": ("warm", "", "BACK wait:4000", 0, "back to home from the series page"),
    "search": ("cold", "wait:3000 UP CENTER wait:2000", "DOWN*3 CENTER RIGHT*4 UP CENTER wait:5000", 0, "typing s q on the pinyin keyboard"),
    "browse": ("cold", "wait:3000 MENU wait:800 DOWN*2 CENTER wait:4000", "DOWN*6 RIGHT*3 DOWN*8 UP*14", 1500, "电影 grid down past a page and back"),
    "watching": ("cold", "wait:3000 MENU wait:800 DOWN CENTER wait:3000", "RIGHT*3 DOWN LEFT*3", 1000, "the 在看 page"),
    "me": ("cold", "wait:3000 MENU wait:800 DOWN*6 CENTER wait:4000", "DOWN*6 UP*6", 1000, "我的"),
    "home-idle": ("cold", "wait:10000", "wait:60000", 0, "requests while idle on home (hero rotation)"),
}
ORDER = list(SCENARIOS)

# Z9X 8K with its own servers (not mock data): detail and series pages are reached from the first
# 电影 / 剧集 grid posters, never by OK on 在看 (which may start playback). Run with `box-` names.
BOX = {
    "box-home-scroll": ("cold", "", "DOWN RIGHT*3 LEFT*3 DOWN RIGHT*6 LEFT*6 DOWN RIGHT*6 LEFT*6 UP*3", 1500, "home rows after launch"),
    "box-home-refresh-move": ("cold", "wait:-1", "DOWN@350 RIGHT*3@350 LEFT*3@350 DOWN@350 RIGHT*8@350 LEFT*8@350 UP*2@350", 1500, "moving right after launch"),
    "box-detail-open": ("cold", "MENU wait:800 DOWN*2 CENTER wait:5000 DOWN wait:1000 RIGHT*3", "CENTER wait:8000", 0, "a film from the 电影 grid"),
    "box-detail-scroll": ("warm", "", "DOWN*8 UP*10", 1500, "detail sections down and up (accordion)"),
    "box-detail-back": ("warm", "", "BACK wait:4000", 0, "back to the grid"),
    # The 剧集 tab may start on 排序 (OK would open the sort panel): Down first, onto the grid.
    "box-series-open": ("cold", "MENU wait:800 DOWN*3 CENTER wait:5000 DOWN wait:1000", "CENTER wait:8000", 0, "a series from the 剧集 grid"),
    "box-series-idle": ("warm", "wait:3000", "wait:5000", 0, "the series page left alone (frames drawn while idle)"),
    "box-series-move": ("warm", "DOWN wait:1500", "RIGHT*6@800 LEFT*6@800 wait:3000", 0, "moving along the episode row"),
    "box-series-scroll": ("warm", "UP wait:1000", "DOWN*6 UP*8", 1500, "series sections down and up"),
    "box-series-back": ("warm", "", "BACK wait:4000", 0, "back to the grid from the series page"),
    "box-search": ("cold", "UP CENTER wait:2000", "DOWN*3 CENTER RIGHT*4 UP CENTER wait:5000", 0, "typing s q on the pinyin keyboard"),
    "box-browse": ("cold", "MENU wait:800 DOWN*2 CENTER wait:5000", "DOWN*6 RIGHT*3 DOWN*8 UP*14", 1500, "电影 grid down past a page and back"),
    "box-watching": ("cold", "MENU wait:800 DOWN CENTER wait:3000", "RIGHT*3 DOWN LEFT*3", 1000, "the 在看 page"),
    "box-me": ("cold", "MENU wait:800 DOWN*6 CENTER wait:4000", "DOWN*6 UP*6", 1000, "我的"),
}
SCENARIOS.update(BOX)


class PlayerGuard:
    """Box runs: polls the Zidoo player's 9529 status from this machine (no process on the box) and, if a
    stray OK started playback, stops it at once and ends the run. Walkthroughs must never play."""

    def __init__(self, dev, host):
        import threading
        self.dev, self.url, self.tripped = dev, f"http://{host}:9529/ZidooVideoPlay/getPlayStatus", False
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def _loop(self):
        while not self.stop_event.wait(0.7):
            try:
                body = json.loads(urllib.request.urlopen(self.url, timeout=2).read())
            except (OSError, ValueError):
                continue
            if body.get("status") == 200 and "video" in body:
                self.tripped = True
                subprocess.run(["adb", "-s", self.dev.serial, "shell", "input", "keyevent", "86"], capture_output=True, timeout=20)
                print("!! playback started: stopped it (MEDIA_STOP); aborting", file=sys.stderr, flush=True)
                os._exit(3)

    def close(self):
        self.stop_event.set()


class Device:
    def __init__(self, serial, input_dev, settle=0):
        self.serial = serial
        self.input = input_dev
        self.monkey = None
        # The Z9X 8K's logcat is empty: wait this long after launch instead of for `home shown`.
        self.settle = settle
        self.launches = []

    def adb(self, *args, check=True, capture=True, timeout=60):
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=capture, text=True, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip()}")
        return r.stdout

    def shell(self, cmd, **kw):
        return self.adb("shell", cmd, **kw)

    # ---- input
    def _monkey(self):
        if self.monkey is None:
            self.shell("pkill -f com.android.commands.monkey", check=False)
            self.adb("forward", "tcp:1080", "tcp:1080")
            self.monkey_proc = subprocess.Popen(["adb", "-s", self.serial, "shell", "monkey", "--port", "1080"],
                                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            # adb forward accepts the connection before monkey listens and then closes it: only an
            # OK answer means monkey is there.
            for _ in range(50):
                sock = None
                try:
                    sock = socket.create_connection(("127.0.0.1", 1080), timeout=2)
                    sock.sendall(b"getvar build.version.sdk\n")
                    if sock.recv(64).startswith(b"OK"):
                        self.monkey = sock
                        break
                except OSError:
                    pass
                if sock is not None:
                    sock.close()
                time.sleep(0.3)
            if self.monkey is None:
                raise RuntimeError("monkey --port did not start")
        return self.monkey

    def run(self, seq):
        """Sends [steps]; returns when the last key and wait are done."""
        if not seq:
            return
        if self.input == "monkey":
            m = self._monkey()
            for st in seq:
                if st[0] == "wait":
                    time.sleep(st[1] / 1000)
                    continue
                code = ANDROID[st[1]]
                for action in ("down", "up"):
                    m.sendall(f"key {action} {code}\n".encode())
                    if not m.recv(64).startswith(b"OK"):  # monkey answers OK to each command
                        raise RuntimeError(f"monkey refused key {action} {code}")
                time.sleep(st[2] / 1000)
        else:
            dev = self.input
            lines = []
            for st in seq:
                if st[0] == "wait":
                    lines.append(f"sleep {st[1] / 1000:.3f}")
                    continue
                c = LINUX[st[1]]
                lines += [f"sendevent {dev} 1 {c} 1", f"sendevent {dev} 0 0 0", f"sendevent {dev} 1 {c} 0", f"sendevent {dev} 0 0 0", f"sleep {st[2] / 1000:.3f}"]
            self.shell("; ".join(lines), timeout=600)

    def close(self):
        if self.monkey is not None:
            try:
                self.monkey.sendall(b"quit\n")
            except OSError:
                pass
            self.monkey.close()
            self.monkey_proc.terminate()

    # ---- app state
    def cold_start(self, timeout=30):
        self.shell(f"am force-stop {PKG}")
        self.adb("logcat", "-c")
        out = self.shell(f"am start -W -n {ACTIVITY}")
        m = re.search(r"TotalTime: (\d+)", out)
        if m:
            self.launches.append(int(m.group(1)))
        if self.settle:
            time.sleep(self.settle / 1000)
            return
        end = time.time() + timeout
        while time.time() < end:
            if "home shown" in self.adb("logcat", "-d", "-s", "zmby.Perf:*", check=False):
                return
            time.sleep(0.3)
        raise RuntimeError("home did not show")

    def screenshot(self, path):
        """The screen as PNG (uiautomator does not report Compose focus reliably; look at the picture)."""
        with open(path, "wb") as fh:
            fh.write(subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout)

    def gfx_reset(self):
        self.shell(f"dumpsys gfxinfo {PKG} reset", check=False)

    def gfx(self):
        out = self.shell(f"dumpsys gfxinfo {PKG}", check=False)
        def num(pat, cast=float):
            m = re.search(pat, out)
            return cast(m.group(1)) if m else None
        hist = {int(a): int(b) for a, b in re.findall(r"(\d+)ms=(\d+)", out.split("HISTOGRAM:")[1].split("\n")[0])} if "HISTOGRAM:" in out else {}
        total = num(r"Total frames rendered: (\d+)", int) or 0
        return {
            "frames": total,
            "janky_pct": num(r"Janky frames: \d+ \(([\d.]+)%\)"),
            "p50": num(r"50th percentile: (\d+)ms", int),
            "p90": num(r"90th percentile: (\d+)ms", int),
            "p99": num(r"99th percentile: (\d+)ms", int),
            "over33": sum(n for ms, n in hist.items() if ms > 33),
        }

    def phases(self):
        """Per-phase p50 / p90 / max (ms) of the last frames (framestats keeps about 120): where a long frame went.

        anim = input and animation callbacks (Compose recomposition runs here), layout = measure and
        layout, record = draw recording on the UI thread, render = RenderThread issuing the frame,
        total = intended vsync to frame completed.
        """
        out = self.shell(f"dumpsys gfxinfo {PKG} framestats", check=False)
        if "---PROFILEDATA---" not in out:
            return {}
        block = out.split("---PROFILEDATA---")[1]
        lines = [l for l in block.strip().splitlines() if l and not l.startswith("---")]
        head = lines[0].rstrip(",").split(",")
        col = {name: i for i, name in enumerate(head)}
        spans = {"anim": ("HandleInputStart", "PerformTraversalsStart"), "layout": ("PerformTraversalsStart", "DrawStart"),
                 "record": ("DrawStart", "SyncQueued"), "render": ("SyncStart", "FrameCompleted"), "total": ("IntendedVsync", "FrameCompleted")}
        values = {k: [] for k in spans}
        for row in lines[1:]:
            v = row.rstrip(",").split(",")
            if len(v) < len(head) or v[col["Flags"]] != "0":
                continue
            for k, (a, b) in spans.items():
                x, y = int(v[col[a]]), int(v[col[b]])
                if x > 0 and y >= x:
                    values[k].append((y - x) / 1e6)
        def stat(xs):
            if not xs:
                return None
            xs = sorted(xs)
            return [round(xs[len(xs) // 2], 1), round(xs[min(len(xs) - 1, int(len(xs) * 0.9))], 1), round(xs[-1], 1)]
        return {k: stat(v) for k, v in values.items()}

    def perf_log(self):
        return self.adb("logcat", "-d", "-v", "brief", "-s", "zmby.Perf:*", check=False).splitlines()


def totals(lines):
    """The last `recomposition totals:` line as {block: n}."""
    last = {}
    for ln in lines:
        if "recomposition totals:" in ln:
            last = {k: int(v) for k, v in re.findall(r"(\S+)=(\d+)", ln.split("recomposition totals:")[1])}
    return last


def mark(mock, name):
    if mock:
        try:
            urllib.request.urlopen(f"{mock}/_mock/mark?name={name}", timeout=3).read()
        except OSError as e:
            print(f"  (mock mark failed: {e})", file=sys.stderr)


def run_scenario(dev, name, mock, shots=None):
    start, prep, measured, tail, _ = SCENARIOS[name]
    mark(mock, f"{name}.prep")
    # `wait:-1` first: start right after launch, without the --settle wait (moving while home refreshes).
    early = prep.startswith("wait:-1")
    if early:
        prep = prep[len("wait:-1"):].strip()
    if start == "cold":
        settle, dev.settle = dev.settle, (1500 if early and dev.settle else dev.settle)
        try:
            dev.cold_start()
        finally:
            dev.settle = settle
    dev.run(steps(prep))
    time.sleep(1.2)  # the totals line before the measured part
    before = totals(dev.perf_log())
    log_from = len(dev.perf_log())
    mark(mock, name)
    dev.gfx_reset()
    dev.run(steps(measured))
    time.sleep(tail / 1000)
    frames = dev.gfx()
    frames["phases"] = dev.phases()
    time.sleep(1.2)
    log = dev.perf_log()
    after = totals(log)
    recomposed = {k: v - before.get(k, 0) for k, v in after.items() if v - before.get(k, 0) > 0}
    events = [ln.split(": ", 1)[-1] for ln in log[log_from:] if "after back" in ln or "frames over" in ln]
    if shots:
        dev.screenshot(f"{shots}/{name}.png")
    return {"frames": frames, "recomposed": dict(sorted(recomposed.items(), key=lambda x: -x[1])), "events": events}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", default="emulator-5582")
    ap.add_argument("--input", default="monkey", help="monkey, or a writable /dev/input/eventN for sendevent")
    ap.add_argument("--mock", help="mock base URL for scenario marks, e.g. http://127.0.0.1:8096")
    ap.add_argument("--out", help="write results as JSON to this file")
    ap.add_argument("--shots", help="save a screenshot at the end of each scenario in this directory")
    ap.add_argument("--settle", type=int, default=0, help="ms to wait after a cold launch instead of the `home shown` log line (devices without logcat)")
    ap.add_argument("--player-guard", metavar="HOST", help="Zidoo box address: stop and abort if playback starts (box runs)")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("names", nargs="*", help="scenarios (default: all in order), or `keys K1 K2 …`")
    a = ap.parse_args()
    if a.list:
        for n in SCENARIOS:
            print(f"{n:20s} {SCENARIOS[n][4]}")
        return 0
    if a.shots:
        os.makedirs(a.shots, exist_ok=True)
    dev = Device(a.serial, a.input, a.settle)
    guard = PlayerGuard(dev, a.player_guard) if a.player_guard else None
    try:
        if a.names[:1] == ["keys"]:
            dev.run(steps(" ".join(a.names[1:])))
            if a.shots:
                time.sleep(0.8)
                dev.screenshot(f"{a.shots}/keys.png")
            return 0
        results = {}
        for name in a.names or [n for n in ORDER if not n.startswith("box-")]:
            r = run_scenario(dev, name, a.mock, a.shots)
            results[name] = r
            f = r["frames"]
            print(f"== {name}: {f['frames']} frames, janky {f['janky_pct']}%, p50/p90/p99 {f['p50']}/{f['p90']}/{f['p99']} ms, >33 ms {f['over33']}")
            print("   recomposed: " + (" ".join(f"{k}={v}" for k, v in r["recomposed"].items()) or "-"))
            if f.get("phases"):
                print("   phases p50/p90/max ms: " + "  ".join(f"{k} {'/'.join(map(str, v))}" for k, v in f["phases"].items() if v))
            for e in r["events"]:
                print("   " + e)
        if dev.launches:
            print(f"am start -W TotalTime (ms): {dev.launches}")
            results["_launches"] = dev.launches
        if a.out:
            with open(a.out, "w", encoding="utf-8") as fh:
                json.dump(results, fh, ensure_ascii=False, indent=1)
    finally:
        dev.close()
        if guard:
            guard.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
