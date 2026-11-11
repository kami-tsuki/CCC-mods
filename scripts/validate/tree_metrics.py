import argparse
import json
import re
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tree_checks

ROOT = Path(__file__).resolve().parents[2]
TREES = ROOT / "KamiClaims/src/main/resources/assets/kami_claims/research/trees"
THRESHOLDS = {
    "depth_max": 40,
    "requires_max": 6,
    "deposit_count_max": 64,
    "item_reuse_max": 20,
}
LEVEL_RANGE = (1, 250)
BAND = 25
TIME_UNITS = {"d": 86400, "h": 3600, "m": 60, "s": 1}
HEADLINE = ["nodes", "categories", "depth", "max_width", "roots", "leaves", "cross_tree_edges", "total_cost", "total_serial_time", "critical_path_time"]


def parse_time(text):
    return sum(int(n) * TIME_UNITS[u] for n, u in re.findall(r"(\d+)([dhms])", str(text)))


def fmt_time(seconds):
    d, rem = divmod(int(seconds), 86400)
    h, rem = divmod(rem, 3600)
    return (f"{d}d" if d else "") + (f"{h}h" if h or d else "") + f"{rem // 60}m"


def dist(values):
    ordered = sorted(values)
    if not ordered:
        return {"median": 0, "p90": 0, "max": 0}
    return {"median": statistics.median(ordered), "p90": ordered[min(len(ordered) - 1, int(0.9 * len(ordered)))], "max": ordered[-1]}


def band_of(level):
    return (level - 1) // BAND * BAND + 1 if LEVEL_RANGE[0] <= level <= LEVEL_RANGE[1] else 0


def band_label(start):
    return f"{start}-{start + BAND - 1}" if start else "out-of-range"


def flat_refs(requires):
    for req in requires:
        if req.get("type") == "node":
            yield req["id"]
        elif "of" in req:
            yield from flat_refs(req["of"])


def load(folder):
    nodes = {}
    for path in sorted(folder.glob("*.json")):
        tree = json.loads(path.read_text(encoding="utf8"))
        for node in tree.get("nodes", []):
            nodes[f"{path.stem}:{node['id']}"] = {**node, "tree": path.stem}
    return nodes


def build_graph(nodes):
    trees = {n["tree"] for n in nodes.values()}
    parents = {k: [] for k in nodes}
    unresolved = []
    for key, node in nodes.items():
        for ref in dict.fromkeys(flat_refs(node.get("requires", []))):
            head, _, tail = ref.partition(":")
            target = ref if tail and head in trees else f"{node['tree']}:{ref}"
            if target in nodes:
                parents[key].append(target)
            else:
                unresolved.append((key, ref))
    return parents, unresolved


def topo_order(parents):
    children = defaultdict(list)
    pending = {k: len(v) for k, v in parents.items()}
    for key, reqs in parents.items():
        for r in reqs:
            children[r].append(key)
    queue = [k for k, n in pending.items() if n == 0]
    order = []
    while queue:
        key = queue.pop()
        order.append(key)
        for child in children[key]:
            pending[child] -= 1
            if pending[child] == 0:
                queue.append(child)
    done = set(order)
    return order, children, [k for k in parents if k not in done]


def longest(order, parents, weight):
    best = {}
    for key in order:
        best[key] = weight(key) + max((best[p] for p in parents[key]), default=0)
    return best


def redundant_edges(order, parents):
    index = {k: i for i, k in enumerate(order)}
    ancestors = {}
    for key in order:
        mask = 0
        for p in parents[key]:
            mask |= ancestors[p] | (1 << index[p])
        ancestors[key] = mask
    return [(k, p) for k in order for p in parents[k] if any(q != p and ancestors[q] >> index[p] & 1 for q in parents[k])]


def deposit_items(task):
    if task.get("type") != "deposit":
        return []
    return [task["item"]] if "item" in task else list(task.get("items", []))


def band_stats(members, field, convert=lambda v: v):
    groups = defaultdict(list)
    for node in members:
        groups[band_of(node.get("level", 0))].append(convert(node.get(field, 0)))
    return {band_label(b): dist(v) for b, v in sorted(groups.items())}


def metrics_for(g, scope):
    nodes, parents, children = g["nodes"], g["parents"], g["children"]
    keys = [k for k in nodes if k in scope]
    members = [nodes[k] for k in keys]
    edges = [(k, p) for k in keys for p in parents[k]]
    level = {k: n.get("level", 0) for k, n in nodes.items()}
    deltas = [level[k] - level[p] for k, p in edges]
    redundant = [e for e in g["redundant"] if e[0] in scope]
    levels = Counter(level[k] for k in keys)
    gaps = [lv for lv in range(LEVEL_RANGE[0], LEVEL_RANGE[1] + 1) if lv not in levels]
    item_users = defaultdict(set)
    task_types = Counter()
    deposit_counts = []
    by_requires = defaultdict(list)
    series = defaultdict(list)
    for k in keys:
        for task in nodes[k].get("tasks", []):
            task_types[task["type"]] += 1
            for item in deposit_items(task):
                item_users[item].add(k)
            if task.get("type") == "deposit":
                deposit_counts.append(task.get("count", 0))
        if parents[k]:
            by_requires[frozenset(parents[k])].append(k)
        m = re.match(r"(.+)_(\d+)$", nodes[k]["id"])
        if m:
            series[(nodes[k]["tree"], m.group(1))].append(k)
    unlocks = [len(n.get("unlocks", [])) for n in members]
    times = [parse_time(n.get("time", "0m")) for n in members]
    layers = Counter(g["depths"].get(k, 0) for k in keys)
    hubs = sorted(((len(children[k]), k) for k in keys), reverse=True)
    requires_counts = [len(n.get("requires", [])) for n in members]
    over_limit = {i: len(u) for i, u in item_users.items() if len(u) > THRESHOLDS["item_reuse_max"]}
    return {
        "nodes": len(keys),
        "categories": len({(n["tree"], n.get("category")) for n in members}),
        "depth": max((g["depths"].get(k, 0) for k in keys), default=0),
        "max_width": max(layers.values(), default=0),
        "roots": sum(1 for k in keys if not parents[k]),
        "leaves": sum(1 for k in keys if not children[k]),
        "nodes_over_3_requires": sum(1 for c in requires_counts if c > 3),
        "nodes_over_requires_max": sum(1 for c in requires_counts if c > THRESHOLDS["requires_max"]),
        "cross_tree_edges": sum(1 for k, p in edges if nodes[k]["tree"] != nodes[p]["tree"]),
        "cycle_nodes": sorted(k for k in g["cyclic"] if k in scope),
        "unresolved_refs": sum(1 for k, _ in g["unresolved"] if k in scope),
        "requires_higher_level": sum(1 for d in deltas if d < 0),
        "redundant_edges": [f"{a} -> {b}" for a, b in redundant],
        "linear_links": sum(1 for k in keys if len(parents[k]) == 1 and len(children[parents[k][0]]) == 1),
        "hubs": [{"node": k, "children": n} for n, k in hubs[:5]],
        "hubs_over_10": sum(1 for n, _ in hubs if n > 10),
        "level_bands": {band_label(b): c for b, c in sorted(Counter(band_of(level[k]) for k in keys).items())},
        "level_gaps": gaps,
        "level_delta": dist(deltas) | {"min": min(deltas, default=0)},
        "cost_by_band": band_stats(members, "cost"),
        "time_by_band": {b: {k: fmt_time(v) for k, v in s.items()} for b, s in band_stats(members, "time", parse_time).items()},
        "total_cost": sum(n.get("cost", 0) for n in members),
        "zero_cost_nodes": sum(1 for n in members if not n.get("cost", 0)),
        "total_serial_time": fmt_time(sum(times)),
        "critical_path_time": fmt_time(max((g["time_best"][k] for k in keys if k in g["time_best"]), default=0)),
        "task_types": dict(task_types.most_common()),
        "max_deposit_count": max(deposit_counts, default=0),
        "top_items": [{"item": i, "nodes": len(u)} for i, u in sorted(item_users.items(), key=lambda x: -len(x[1]))[:10]],
        "items_over_limit": over_limit,
        "nodes_with_0_or_1_unlocks": sum(1 for c in unlocks if c <= 1),
        "nodes_over_8_unlocks": sum(1 for c in unlocks if c > 8),
        "identical_requires_groups": sum(1 for v in by_requires.values() if len(v) > 1),
        "largest_identical_group": max((len(v) for v in by_requires.values()), default=0),
        "numbered_series": sum(1 for v in series.values() if len(v) > 1),
        "numbered_series_nodes": sum(len(v) for v in series.values() if len(v) > 1),
    }


def collect(folder):
    nodes = load(folder)
    parents, unresolved = build_graph(nodes)
    order, children, cyclic = topo_order(parents)
    g = {
        "nodes": nodes,
        "parents": parents,
        "children": children,
        "unresolved": unresolved,
        "cyclic": cyclic,
        "depths": longest(order, parents, lambda k: 1),
        "time_best": longest(order, parents, lambda k: parse_time(nodes[k].get("time", "0m"))),
        "redundant": redundant_edges(order, parents),
    }
    overall = metrics_for(g, set(nodes))
    overall["extra_rows"], overall["extra_notes"] = tree_checks.extra_checks(nodes, parents, g["depths"])
    overall["unresolved_list"] = [f"{k} -> {r}" for k, r in unresolved]
    trees = {t: metrics_for(g, {k for k, n in nodes.items() if n["tree"] == t}) for t in sorted({n["tree"] for n in nodes.values()})}
    return {"overall": overall, "trees": trees}


def evaluate(result):
    o, t = result["overall"], THRESHOLDS
    rows = [
        ("depth", o["depth"], t["depth_max"]),
        (f"nodes with more than {t['requires_max']} requires", o["nodes_over_requires_max"], 0),
        ("max deposit count", o["max_deposit_count"], t["deposit_count_max"]),
        ("most nodes sharing one item", max(o["items_over_limit"].values(), default=0), t["item_reuse_max"]),
        ("cycle nodes", len(o["cycle_nodes"]), 0),
        ("unresolved refs", o["unresolved_refs"], 0),
        ("requires to higher level", o["requires_higher_level"], 0),
        ("redundant edges", len(o["redundant_edges"]), 0),
    ]
    return [{"name": n, "value": v, "limit": lim, "pass": v <= lim} for n, v, lim in rows] + o["extra_rows"]


def render(result):
    width = lambda h: max(len(h), 8) + 2
    out = ["scope".ljust(12) + "".join(h.rjust(width(h)) for h in HEADLINE)]
    for name, m in [*result["trees"].items(), ("OVERALL", result["overall"])]:
        out.append(name.ljust(12) + "".join(str(m[h]).rjust(width(h)) for h in HEADLINE))
    out.append("")
    for key, value in result["overall"].items():
        if key in HEADLINE or key in ("unresolved_list", "extra_rows", "extra_notes"):
            continue
        text = str(value) if isinstance(value, (int, str)) else json.dumps(value, ensure_ascii=False)
        out.append(f"{key}: {text[:300]}{'...' if len(text) > 300 else ''}")
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(description="Research tree metrics and threshold checks.")
    parser.add_argument("--trees", type=Path, default=TREES, help="folder with research tree json files")
    parser.add_argument("--json", action="store_true", help="print raw metrics and check results as json")
    parser.add_argument("--ignore-targets", action="store_true", help="print metrics only and always exit 0")
    args = parser.parse_args(argv)
    result = collect(args.trees)
    checks = evaluate(result)
    ok = args.ignore_targets or all(c["pass"] for c in checks)
    if args.json:
        print(json.dumps({"ok": ok, "metrics": result, "checks": checks}, ensure_ascii=False, indent=1))
    else:
        print("\n".join(render(result)))
        if not args.ignore_targets:
            for note in result["overall"]["extra_notes"]:
                print("note", note)
            print("\ntargets")
            for c in checks:
                print(f"{'PASS' if c['pass'] else 'FAIL'}  {c['name']}: {c['value']} (limit {c['limit']})")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
