#!/usr/bin/env python3
"""A before and after comparison page of hab generation profiles.

Each run is a folder made by hab_flamegraph.py (it holds phases.json and the three hab-<size>.svg graphs). This writes one page with, for each hab size,
a table of what the server thread spent in each phase in every run, and the first and last runs' flame graphs one under the other.

    python scripts/perf/hab_compare.py --out build/hab-profile/index.html \\
        --run "Before=build/hab-profile" --run "Fewer writes=build/hab-profile/after" --run "All three=build/hab-profile/final"

Graph widths are each run's own samples, so compare the table's seconds, not the boxes' widths; hover a box for its samples.
"""
import argparse
import html
import json
import os


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="append", required=True, help='"label=folder", first is the baseline')
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    runs = []
    for spec in a.run:
        label, folder = spec.split("=", 1)
        runs.append((label, folder, json.load(open(os.path.join(folder, "phases.json"), encoding="utf-8"))))
    base = os.path.dirname(os.path.abspath(a.out))
    sections = []
    for size in ("COMPACT", "STANDARD", "LARGE"):
        if not all(size in r[2] for r in runs):
            continue
        head = "".join("<th>%s</th>" % html.escape(label) for label, _, _ in runs) + "<th>Change</th>"
        rows = []

        def row(name, values, unit="s", bold=False, digits=1):
            first, last = values[0], values[-1]
            change = "" if not first else "%+.0f%%" % (100 * (last - first) / first)
            cells = "".join("<td>%.*f %s</td>" % (digits, v, unit) for v in values)
            style = ' class="strong"' if bold else ""
            indent = ' style="padding-left:28px"' if name.startswith("  ") else ""
            rows.append("<tr%s><td%s>%s</td>%s<td>%s</td></tr>" % (style, indent, html.escape(name.strip()), cells, change))

        row("Wall time per hab (mean)", [sum(r[2][size]["wall_each_s"]) / max(len(r[2][size]["wall_each_s"]), 1) for r in runs], bold=True)
        for name in runs[0][2][size]["phases"]:
            values = [r[2][size]["phases"].get(name, 0) for r in runs]
            row(name, values, bold=name.startswith("Server thread"))
        if all(r[2][size].get("ticks") for r in runs):
            rows.append('<tr><td colspan="%d" class="band">Length of each server tick while a hab was built (50 ms is the limit)</td></tr>' % (len(runs) + 2))
            for label, key, unit, digits in (("Median tick", "p50_ms", "ms", 1), ("95th percentile tick", "p95_ms", "ms", 1), ("99th percentile tick", "p99_ms", "ms", 1), ("Longest tick", "max_ms", "ms", 0), ("Ticks over 50 ms", "over50", "", 0), ("Ticks over 100 ms", "over100", "", 0), ("Ticks over 200 ms", "over200", "", 0)):
                row(label, [r[2][size]["ticks"][key] for r in runs], unit, bold=key == "max_ms", digits=digits)
        graphs = []
        for label, folder, _ in (runs[0], runs[-1]):
            rel = os.path.relpath(os.path.join(folder, "hab-%s.svg" % size.lower()), base).replace("\\", "/")
            graphs.append('<h3>%s</h3><div class="scroll"><object type="image/svg+xml" data="%s" width="1800"></object></div>' % (html.escape(label), rel))
        habs = runs[0][2][size]["habs"]
        sections.append('<section><h2>%s</h2><p class="note">%d habs per run; seconds are summed over all of them, on the server thread, and a phase includes what it calls.</p><table><tr><th>Phase</th>%s</tr>%s</table>%s</section>' % (size.title(), habs, head, "".join(rows), "".join(graphs)))
    page = '''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Hab generation, before and after</title>
<style>
:root { --bg:#f6f4ee; --fg:#1b1b1b; --muted:#555; --card:#fff; --line:#d8d4c8; --good:#1a7f37; }
@media (prefers-color-scheme: dark) { :root { --bg:#17181a; --fg:#e8e6df; --muted:#a09d94; --card:#1f2124; --line:#33363a; --good:#56d364; } }
body { margin:0; padding:16px; background:var(--bg); color:var(--fg); font:15px/1.5 system-ui, sans-serif; }
h1 { margin:0 0 4px; font-size:22px; }
h2 { font-size:18px; margin:28px 0 4px; }
h3 { font-size:14px; margin:16px 0 6px; color:var(--muted); }
p.note { margin:0 0 10px; color:var(--muted); max-width:75ch; }
table { border-collapse:collapse; background:var(--card); border:1px solid var(--line); margin-bottom:8px; }
th, td { padding:4px 10px; border-bottom:1px solid var(--line); text-align:right; font-variant-numeric:tabular-nums; }
th:first-child, td:first-child { text-align:left; }
tr.strong td { font-weight:600; }
td.band { text-align:left; background:var(--bg); color:var(--muted); font-size:13px; padding-top:10px; }
.scroll { overflow-x:auto; background:var(--card); border:1px solid var(--line); border-radius:6px; }
object { display:block; }
</style>
</head>
<body>
<h1>Natural hab generation, before and after</h1>
<p class="note">Java Flight Recorder profiles of natural habs built one at a time on Terra, two per size after a warm-up. The tables are the server thread's time in each phase; a negative change is faster. The graphs below each table are the first and last runs.</p>
''' + "\n".join(sections) + '''
</body>
</html>
'''
    open(a.out, "w", encoding="utf-8").write(page)
    print("wrote", a.out)


if __name__ == "__main__":
    main()
