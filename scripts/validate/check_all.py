import argparse
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def trees_numbers(data):
    overall = data["metrics"]["overall"]
    failing = sum(1 for c in data["checks"] if not c["pass"])
    return f"nodes={overall['nodes']} depth={overall['depth']} failing_targets={failing}"


def refs_numbers(data):
    s = data["summary"]
    return f"refs={s['unique_refs']} missing={s['missing']} unknown_ns={s['unknown_namespaces']}"


def lang_numbers(data):
    unused = sum(m["unused"]["count"] for m in data["mods"].values())
    return f"violations={data['violations']} unused_keys={unused}"


def code_numbers(data):
    t = data["totals"]
    return f"comments={t['comments']} dead={t['dead']} dups={t['dup_clusters']} big={t['big_files']} long_fn={t['long_functions']} bangs={t['bangs']} colors={t['colors']}"


def recipes_numbers(data):
    return f"missing_producer_edges={len(data['edges'])}"


CHECKS = {
    "trees": ("tree_metrics.py", trees_numbers),
    "recipes": ("tree_recipes.py", recipes_numbers),
    "refs": ("refs_check.py", refs_numbers),
    "lang": ("lang_check.py", lang_numbers),
    "code": ("code_health.py", code_numbers),
}


def run(name):
    script, numbers = CHECKS[name]
    proc = subprocess.run([sys.executable, "-I", str(HERE / script), "--json"], capture_output=True, text=True, encoding="utf8")
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return {"check": name, "status": "ERROR", "numbers": (proc.stderr.strip().splitlines() or ["no output"])[-1]}
    return {"check": name, "status": "PASS" if data["ok"] else "FAIL", "numbers": numbers(data)}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Run the validation checks and print one summary table.")
    parser.add_argument("--only", action="append", choices=sorted(CHECKS), help="run only this check (repeatable)")
    parser.add_argument("--json", action="store_true", help="print the summary as json")
    args = parser.parse_args(argv)
    results = [run(name) for name in (args.only or CHECKS)]
    ok = all(r["status"] == "PASS" for r in results)
    if args.json:
        print(json.dumps({"ok": ok, "results": results}, indent=1))
    else:
        for r in results:
            print(f"{r['check']:8}{r['status']:7}{r['numbers']}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
