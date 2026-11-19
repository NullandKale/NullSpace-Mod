#!/usr/bin/env python3
"""Flame graphs of natural hab generation, one per hab size, from a Java Flight Recorder file.

The profile run (a GameTest that builds natural habs one at a time and prints PROFILE start/end lines with epoch milliseconds, under
-XX:StartFlightRecording) gives two files: the recording and the test's log. This reads both: each built hab's window in the log picks its
samples out of the recording, and the samples of habs of one size are merged into one SVG flame graph (open it in anything that shows an image; hover a box for its
sample count).

    python scripts/perf/hab_flamegraph.py --jfr build/hab-profile/hab.jfr --log <work>/test.log --jfr-tool <jdk>/bin/jfr.exe --out build/hab-profile

Wall time of a hab is the span between its start and end lines. A sample is one Java thread running Java code at a 10 ms tick, so sample counts
times 10 ms is CPU time on that thread; a thread that is waiting (a chunk load, a lock) is not sampled.
"""
import argparse
import collections
import datetime
import html
import json
import re
import subprocess
import sys
import zlib

PERIOD_MS = 10
START = re.compile(r"PROFILE start (\d+) (\w+)( warmup)? cell (-?\d+)")
END = re.compile(r"PROFILE end (\d+) (\w+) (\w+) cell (-?\d+)")
TIME = re.compile(r"startTime = (\d+):(\d+):(\d+)\.(\d+) \((\d+)-(\d+)-(\d+)\)")
THREAD = re.compile(r'sampledThread = "([^"]*)"')
FRAME = re.compile(r"^\s+(\S.*?)\s+line:\s*(-?\d+)\s*$")


def windows(log_path):
    """The built habs in the log: (size, start ms, end ms, warmup)."""
    started, out = {}, []
    for line in open(log_path, encoding="utf-8", errors="replace"):
        m = START.search(line)
        if m:
            started[m.group(4)] = (int(m.group(1)), m.group(2), bool(m.group(3)))
            continue
        m = END.search(line)
        if m and m.group(4) in started:
            begin, size, warm = started.pop(m.group(4))
            if m.group(3) == "built":
                out.append((size, begin, int(m.group(1)), warm))
    return out


def tick_stats(log_path):
    """Server tick lengths while each built hab was generated, by size, from the test's PROFILE tickus lines (microseconds per tick)."""
    out = collections.defaultdict(list)
    warm = {}
    pending = None
    for line in open(log_path, encoding="utf-8", errors="replace"):
        m = START.search(line)
        if m:
            warm[m.group(4)] = bool(m.group(3))
            current = m.group(4)
            continue
        m = re.search(r"PROFILE tickus (\w+) (\w+) ([\d,]*)", line)
        if m:
            pending = (m.group(1), m.group(2), [int(v) for v in m.group(3).split(",") if v])
            continue
        m = END.search(line)
        if m and pending and m.group(3) == "built" and not warm.get(m.group(4), False):
            out[pending[0]] += pending[2]
        pending = None if m else pending
    stats = {}
    for size, values in out.items():
        values.sort()
        n = len(values)
        pick = lambda q: values[min(n - 1, int(q * n))] / 1000
        stats[size] = {"ticks": n, "p50_ms": pick(0.5), "p95_ms": pick(0.95), "p99_ms": pick(0.99), "max_ms": values[-1] / 1000,
                       "over50": sum(1 for v in values if v > 50_000), "over100": sum(1 for v in values if v > 100_000), "over200": sum(1 for v in values if v > 200_000)}
    return stats


def thread_group(name):
    name = re.sub(r"\d+", "N", name)
    return name


def label(frame):
    method = frame.split("(")[0]
    parts = method.split(".")
    return ".".join(parts[-2:]) if len(parts) > 1 else method


def package_of(frame):
    method = frame.split("(")[0]
    return method.rsplit(".", 2)[0] if method.count(".") >= 2 else method


def samples(jfr, tool, wins):
    """Streams the recording's execution samples and keeps those that fall inside a hab's window: {size: [(thread, [outermost..leaf])]}."""
    proc = subprocess.Popen([tool, "print", "--events", "jdk.ExecutionSample", "--stack-depth", "96", jfr], stdout=subprocess.PIPE, text=True, errors="replace")
    kept = collections.defaultdict(list)
    when = thread = None
    frames = None
    total = 0
    for line in proc.stdout:
        m = TIME.search(line)
        if m:
            h, mi, s, ms, y, mo, d = map(int, m.groups())
            when = datetime.datetime(y, mo, d, h, mi, s, ms * 1000).timestamp() * 1000
            continue
        m = THREAD.search(line)
        if m:
            thread = m.group(1)
            continue
        if "stackTrace = [" in line:
            frames = []
            continue
        if frames is not None:
            m = FRAME.match(line)
            if m:
                frames.append(m.group(1))
                continue
            if line.strip().startswith("]") or line.strip() == "...":
                if line.strip() == "...":
                    continue
                total += 1
                for size, begin, end, warm in wins:
                    if not warm and begin <= when <= end:
                        kept[size].append((thread, list(reversed(frames))))
                        break
                frames = None
    proc.wait()
    return kept, total


class Node:
    __slots__ = ("name", "count", "kids")

    def __init__(self, name):
        self.name, self.count, self.kids = name, 0, {}


def tree(stack):
    root = Node("all")
    for thread, frames in stack:
        root.count += 1
        node = root
        for name in [thread_group(thread)] + [label(f) for f in frames]:
            node = node.kids.setdefault(name, Node(name))
            node.count += 1
    return root


def colour(name):
    low = name.lower()
    if "nullspace" in low:
        base = (230, 90, 40)
    elif low.startswith(("net.minecraft", "minecraft", "net.minecraft.")):
        base = (80, 140, 220)
    elif low.startswith(("java.", "jdk.", "sun.")):
        base = (140, 140, 150)
    else:
        base = (90, 170, 110)
    jitter = zlib.crc32(name.encode()) % 40 - 20
    return "rgb(%d,%d,%d)" % tuple(max(0, min(255, c + jitter)) for c in base)


def svg(root, title, path, labels):
    width, row, pad = 1800, 17, 10
    depth = 0
    stack = [(root, 1)]
    while stack:
        n, d = stack.pop()
        depth = max(depth, d)
        stack.extend((k, d + 1) for k in n.kids.values())
    height = depth * row + 70
    out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" font-family="monospace" font-size="11">' % (width, height)]
    out.append('<rect width="100%" height="100%" fill="#f6f4ee"/>')
    out.append('<text x="%d" y="22" font-size="15" font-weight="bold">%s</text>' % (pad, html.escape(title)))
    out.append('<text x="%d" y="40" fill="#555">%d samples = %.1f s of running Java code (10 ms each); orange = NullSpace, blue = Minecraft, grey = JDK, green = libraries</text>' % (pad, root.count, root.count * PERIOD_MS / 1000))
    scale = (width - 2 * pad) / max(root.count, 1)

    def draw(node, x, level):
        w = node.count * scale
        if w < 0.4:
            return
        y = height - (level + 1) * row - 8
        ms = node.count * PERIOD_MS
        tip = "%s: %d samples, %d ms, %.1f%% of all" % (node.name, node.count, ms, 100 * node.count / root.count)
        out.append('<g><title>%s</title><rect x="%.2f" y="%d" width="%.2f" height="%d" fill="%s" stroke="#f6f4ee" stroke-width="0.5"/>' % (html.escape(tip), x, y, w, row - 1, colour(node.name)))
        if w > 46:
            text = node.name if len(node.name) * 6.4 < w - 4 else node.name[: max(int((w - 4) / 6.4) - 1, 1)] + "~"
            out.append('<text x="%.2f" y="%d" fill="#111">%s</text>' % (x + 2, y + 12, html.escape(text)))
        out.append("</g>")
        cursor = x
        for kid in sorted(node.kids.values(), key=lambda k: k.name):
            draw(kid, cursor, level + 1)
            cursor += kid.count * scale

    draw(root, pad, 0)
    out.append("</svg>")
    open(path, "w", encoding="utf-8").write("\n".join(out))


def self_times(stack):
    own, inclusive = collections.Counter(), collections.Counter()
    for _, frames in stack:
        if not frames:
            continue
        own[label(frames[-1])] += 1
        for name in {label(f) for f in frames}:
            inclusive[name] += 1
    return own, inclusive


# What the server thread spends, as the time any of its samples had one of these frames on the stack (so a phase includes what it calls).
PHASES = [
    ("Natural hab build step", ("NaturalHabs$Active.step",)),
    ("Journal file writes", ("WorldStore.write(",)),
    ("  gzip compress", ("NbtIo.writeCompressed",)),
    ("  read-back verify", ("NbtIo.readCompressed",)),
    ("  deep compare", ("CompoundTag.equals",)),
    ("Journal: capture before-state (begin, include)", ("BuildJournal$Job.include", "BuildJournal.beginRegions", "BuildJournal.begin(")),
    ("Journal: capture store changes", ("BuildJournal$Job.capture",)),
    ("World save (saveEverything)", ("MinecraftServer.saveEverything",)),
    ("Store snapshots", ("snapshot(",)),
    ("Store file save", ("WorldStore.save(",)),
    ("Seal scan", ("SealScan.step",)),
]


def phases(stack):
    server = [frames for thread, frames in stack if thread == "Server thread"]
    out = {"Server thread, total running": len(server) * PERIOD_MS / 1000}
    for name, keys in PHASES:
        out[name] = sum(1 for frames in server if any(k in f for f in frames for k in keys)) * PERIOD_MS / 1000
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jfr", required=True)
    ap.add_argument("--log", required=True)
    ap.add_argument("--jfr-tool", default="jfr")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    wins = windows(a.log)
    if not wins:
        sys.exit("no built habs in the log")
    kept, total = samples(a.jfr, a.jfr_tool, wins)
    report = ["%d execution samples in the recording" % total, ""]
    data = {}
    ticks = tick_stats(a.log)
    for size in ("COMPACT", "STANDARD", "LARGE"):
        mine = [w for w in wins if w[0] == size and not w[3]]
        stack = kept.get(size, [])
        if not stack:
            continue
        wall = sum(e - b for _, b, e, _ in mine) / 1000
        data[size] = {"habs": len(mine), "wall_each_s": [(e - b) / 1000 for _, b, e, _ in mine], "samples_s": len(stack) * PERIOD_MS / 1000, "phases": phases(stack), "ticks": ticks.get(size)}
        root = tree(stack)
        svg(root, "Natural hab generation, %s (%d habs, %.1f s wall, merged)" % (size.lower(), len(mine), wall), "%s/hab-%s.svg" % (a.out, size.lower()), True)
        report.append("== %s: %d habs built, wall %s s each (%.1f s together), %d samples = %.1f s running Java" % (size, len(mine), ", ".join("%.1f" % ((e - b) / 1000) for _, b, e, _ in mine), wall, len(stack), len(stack) * PERIOD_MS / 1000))
        per_thread = collections.Counter(thread_group(t) for t, _ in stack)
        report.append("   by thread: " + ", ".join("%s %.1f s" % (t, n * PERIOD_MS / 1000) for t, n in per_thread.most_common(8)))
        own, inclusive = self_times(stack)
        report.append("   top self time:")
        report += ["     %6.2f s  %s" % (n * PERIOD_MS / 1000, name) for name, n in own.most_common(14)]
        report.append("   top inclusive (NullSpace and Minecraft entry points):")
        shown = [(name, n) for name, n in inclusive.most_common(400) if not name.startswith(("Thread.", "ThreadPoolExecutor", "ForkJoin", "CompletableFuture", "Executors", "FutureTask", "Runnable", "ThreadExecutor", "BlockableEventLoop"))][:16]
        report += ["     %6.2f s  %s" % (n * PERIOD_MS / 1000, name) for name, n in shown]
        report.append("")
    open("%s/summary.txt" % a.out, "w", encoding="utf-8").write("\n".join(report))
    json.dump(data, open("%s/phases.json" % a.out, "w", encoding="utf-8"), indent=1)
    print("\n".join(report))


if __name__ == "__main__":
    main()
