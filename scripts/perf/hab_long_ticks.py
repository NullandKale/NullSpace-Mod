#!/usr/bin/env python3
"""What the server thread was doing during the longest ticks of hab generation.

The profile run (see hab_flamegraph.py) prints PROFILE longticks lines, one per hab: the start time and length of every server tick over 60 ms.
This takes the recording's samples that fall inside each of those ticks, and for every tick over a limit lists the frames that held most of it, then
totals them over all the long ticks of each hab size.

    python scripts/perf/hab_long_ticks.py --jfr build/hab-profile/final/hab.jfr --log <work>/test.log --jfr-tool <jdk>/bin/jfr.exe [--over 100]
"""
import argparse
import collections
import datetime
import re
import subprocess
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hab_flamegraph as h  # noqa: E402

# Frames worth naming, matched against "Class.method"; the first match on a stack, outermost first, names the sample.
CAUSES = [
    ("hab block placement", "NaturalHabs$Active.lambda$step"),
    ("hab prepare (plan to block map)", "NaturalHabs$Active.prepare"),
    ("hab finish (entities, loot, doors)", "NaturalHabs$Active.finish"),
    ("hab finish (entities, loot, doors)", "NaturalHabs$Active.lambda$finish"),
    ("journal begin / capture before-state", "BuildJournal.begin"),
    ("journal begin / capture before-state", "BuildJournal$Job.include"),
    ("journal commit", "BuildJournal$Job.commit"),
    ("journal step: store snapshots and diff", "BuildJournal$Job.capture"),
    ("journal step: store snapshots and diff", "WorldStore.snapshot"),
    ("seal scan", "SealScan.step"),
    ("world save", "MinecraftServer.saveEverything"),
    ("registered hab systems (power/air/devices)", "ShipSystems.tick"),
    ("vanilla chunk ticking", "ServerChunkCache.tick"),
    ("vanilla entity ticking", "ServerLevel.tickNonPassenger"),
    ("vanilla chunk loading / generation", "ServerChunkCache.getChunk"),
    ("vanilla chunk loading / generation", "Level.getChunk"),
]


def parse_ticks(log):
    sizes, out = [], collections.defaultdict(list)
    warm = set()
    pending = None
    for line in open(log, encoding="utf-8", errors="replace"):
        m = h.START.search(line)
        if m:
            if m.group(3):
                warm.add(m.group(4))
            continue
        m = re.search(r"PROFILE longticks (\w+) (\w+) ?(.*)", line)
        if m:
            pending = (m.group(1), m.group(2), [tuple(int(x) for x in t.split(":")) for t in m.group(3).strip().split(",") if t])
            continue
        m = h.END.search(line)
        if m:
            if pending and m.group(3) == "built" and m.group(4) not in warm:
                out[pending[0]] += pending[2]
            pending = None
    return out


def samples(jfr, tool, lo, hi):
    proc = subprocess.Popen([tool, "print", "--events", "jdk.ExecutionSample", "--stack-depth", "96", jfr], stdout=subprocess.PIPE, text=True, errors="replace")
    rows, when, thread, frames = [], None, None, None
    for line in proc.stdout:
        m = h.TIME.search(line)
        if m:
            a, b, c, ms, y, mo, d = map(int, m.groups())
            when = datetime.datetime(y, mo, d, a, b, c, ms * 1000).timestamp() * 1000
            continue
        m = h.THREAD.search(line)
        if m:
            thread = m.group(1)
            continue
        if "stackTrace = [" in line:
            frames = []
            continue
        if frames is not None:
            m = h.FRAME.match(line)
            if m:
                frames.append(m.group(1))
                continue
            if line.strip() == "...":
                continue
            if line.strip().startswith("]"):
                if thread == "Server thread" and lo <= when <= hi:
                    rows.append((when, list(reversed(frames))))
                frames = None
    proc.wait()
    return rows


def label(frame):
    parts = frame.split("(")[0].split(".")
    return ".".join(parts[-2:])


def cause(frames):
    names = [label(f) for f in frames]
    for text, pattern in CAUSES:
        if any(pattern in n for n in names):
            return text
    return "other (" + (names[-1] if names else "?") + ")"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jfr", required=True)
    ap.add_argument("--log", required=True)
    ap.add_argument("--jfr-tool", default="jfr")
    ap.add_argument("--over", type=int, default=100, help="only ticks longer than this many ms")
    a = ap.parse_args()
    ticks = parse_ticks(a.log)
    every = [t for v in ticks.values() for t in v]
    if not every:
        sys.exit("no long ticks in the log")
    rows = samples(a.jfr, a.jfr_tool, min(s for s, _ in every) - 50, max(s + d for s, d in every) + 50)
    for size in ("COMPACT", "STANDARD", "LARGE"):
        mine = sorted((t for t in ticks.get(size, []) if t[1] >= a.over), key=lambda t: -t[1])
        if not mine:
            print("== %s: no ticks over %d ms" % (size, a.over))
            continue
        total = collections.Counter()
        biggest = []
        for start, length in mine:
            inside = [fr for t, fr in rows if start <= t <= start + length]
            mix = collections.Counter(cause(fr) for fr in inside)
            total.update(mix)
            biggest.append((length, mix))
        covered = sum(total.values()) * h.PERIOD_MS
        print("== %s: %d ticks over %d ms (sum %.1f s, %.1f s of it sampled)" % (size, len(mine), a.over, sum(l for _, l in mine) / 1000, covered / 1000))
        print("   by cause, over all of them: " + ", ".join("%s %d ms" % (k, v * h.PERIOD_MS) for k, v in total.most_common(8)))
        for length, mix in biggest[:5]:
            print("   %5d ms tick: " % length + ", ".join("%s %d ms" % (k, v * h.PERIOD_MS) for k, v in mix.most_common(4)))


if __name__ == "__main__":
    main()
