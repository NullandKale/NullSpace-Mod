"""Compares two runs of scripts/test/performance-test.ps1, step by step.

    python scripts/test/compare-performance.py <run A folder> <run B folder>

A run folder holds one folder per case (for example move-40-run1) with a result.json inside. For every scenario,
ship count and step the tool prints the two values side by side with the change. Several repeats of a case are
reduced to their median. Timing is evidence, not a verdict: nothing here passes or fails.
"""
import json
import re
import statistics
import sys
from pathlib import Path


def load(folder: Path):
    if not folder.is_dir():
        raise ValueError(f"Missing run directory: {folder}")
    receipt = json.loads((folder / "result.json").read_text(encoding="utf-8-sig"))
    if receipt.get("status") != "passed":
        raise ValueError(f"Run did not complete: {folder}")
    cases = {}
    for result in sorted(folder.glob("*/result.json")):
        match = re.match(r"(?P<scenario>.+)-(?P<ships>\d+)-run\d+$", result.parent.name)
        if match:
            run = json.loads(result.read_text(encoding="utf-8-sig"))
            if run.get("schemaVersion", 1) < 2:
                raise ValueError(f"Legacy simulated-time accounting is not comparable: {result}")
            cases.setdefault((match["scenario"], int(match["ships"])), []).append(run)
    if not cases:
        raise ValueError(f"Run has no completed cases: {folder}")
    durations = tuple(receipt.get(key) for key in ("warmupSeconds", "stepSeconds", "flatSeconds", "idleSeconds"))
    versions = {(run["schemaVersion"], run["fixtureVersion"]) for runs in cases.values() for run in runs}
    return cases, (durations, versions)


def median(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def step_values(runs, name, getter):
    return median([getter(step) for run in runs for step in run["steps"] if step["step"] == name])


def get(path):
    def getter(step):
        value = step
        for key in path:
            value = value.get(key) if isinstance(value, dict) else None
        return value
    return getter


def fmt(value):
    return "-" if value is None else f"{value:,.1f}"


def change(a, b):
    if a is None or b is None or a == 0:
        return ""
    return f"{(b - a) / a * 100:+.0f}%"


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    try:
        a, signature_a = load(Path(sys.argv[1]))
        b, signature_b = load(Path(sys.argv[2]))
        if signature_a != signature_b:
            raise ValueError("Fixture versions or phase durations differ; run matching cases before comparing")
    except (OSError, ValueError, KeyError) as error:
        print(f"Cannot compare: {error}", file=sys.stderr)
        return 2
    metrics = [("ops/s", get(["operationsPerSecond"])), ("op ms p95", get(["operationMs", "p95"])), ("op ms max", get(["operationMs", "max"])),
               ("tick ms p95", get(["tickMs", "p95"])), ("tick ms max", get(["tickMs", "max"])), ("ticks > 50 ms", get(["ticksOver50ms"]))]
    for key in sorted(set(a) | set(b)):
        runs_a, runs_b = a.get(key, []), b.get(key, [])
        template = (runs_a or runs_b)[0]
        print(f"\n## {key[0]} with {key[1]} ships")
        world_a = median([r["world"].get("journals") for r in runs_a])
        world_b = median([r["world"].get("journals") for r in runs_b])
        print(f"journal files left on disk: {fmt(world_a)} -> {fmt(world_b)}    store on disk (bytes): "
              f"{fmt(median([r['world'].get('storeBytesOnDisk') for r in runs_a]))} -> {fmt(median([r['world'].get('storeBytesOnDisk') for r in runs_b]))}")
        print(f"{'step':<14}" + "".join(f"{name:<34}" for name, _ in metrics))
        names = dict.fromkeys(step["step"] for run in runs_a + runs_b for step in run["steps"])
        for name in names:
            cells = []
            for _, getter in metrics:
                va, vb = step_values(runs_a, name, getter), step_values(runs_b, name, getter)
                cells.append(f"{fmt(va)} -> {fmt(vb)} {change(va, vb)}".ljust(34))
            print(f"{name:<14}" + "".join(cells))
    return 0


if __name__ == "__main__":
    sys.exit(main())
