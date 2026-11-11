import argparse
import importlib.util
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TREES = ROOT / "KamiClaims/src/main/resources/assets/kami_claims/research/trees"
GROUPS = ROOT / "docs/tree-groups.txt"
OUT_MAP = ROOT / "docs/tree-mapping.tsv"
OUT_NODES = ROOT / "docs/tree-nodes.tsv"
REQUIRES_MAX = 4
DEPTH_CAP = 24
TRIM = {"corner", "long", "short", "tiled"}
TASKS = {
    "basics": ("craft", "place"), "power": ("place", "craft"), "transmission": ("craft", "place"), "processing": ("process", "craft"),
    "logistics": ("craft", "place"), "fluids": ("place", "craft"), "brass": ("craft", "process"), "steel": ("place", "structure"),
    "petrochem": ("process", "place"), "chemistry": ("process", "craft"), "sulfuric": ("process", "place"), "pharma": ("process", "craft"),
    "engines": ("craft", "place"), "electronics": ("craft", "process"), "electricity": ("place", "craft"), "nuclear": ("structure", "process"),
    "space": ("craft", "visit"), "vanilla_tech": ("craft", "place"),
    "stone_age": ("mine", "craft"), "metallurgy": ("smelt", "place"), "zinc": ("smelt", "process"), "lead": ("smelt", "process"),
    "copper": ("smelt", "process"), "iron": ("smelt", "process"), "gold": ("smelt", "process"), "tin": ("smelt", "process"),
    "nickel": ("smelt", "process"), "silver": ("smelt", "process"), "brass_metal": ("craft", "process"), "netherite": ("smelt", "kill"),
    "cast_iron": ("process", "smelt"), "alloys": ("smelt", "process"), "light_metals": ("process", "mine"), "rare_metals": ("process", "mine"),
    "resonant": ("process", "craft"), "advanced_netherite": ("craft", "kill"), "refractory": ("process", "smelt"), "off_world": ("visit", "mine"),
    "prospecting": ("craft", "visit"),
    "melee": ("craft", "kill"), "archery": ("craft", "kill"), "alchemy": ("craft", "process"), "explosives": ("craft", "place"),
    "firearms": ("craft", "kill"), "ammo": ("craft", "process"), "attachments": ("craft", "process"), "artillery": ("place", "craft"),
    "munitions": ("craft", "process"),
    "armor": ("craft", "smelt"), "tools": ("craft", "break_item"), "special": ("craft", "process"), "backpacks": ("craft", "trade"),
    "storage": ("craft", "place"), "food": ("craft", "smelt"),
    "view": ("deposit", "deposit"), "healing": ("craft", "deposit"), "combat": ("kill", "deposit"), "movement": ("visit", "deposit"),
    "work": ("mine", "deposit"), "utility": ("enchant", "deposit"), "finance": ("trade_value", "taxes"), "invest_treasury": ("taxes", "trade_value"),
    "invest_land": ("place", "deposit"), "invest_citizens": ("trade", "deposit"), "invest_provinces": ("structure", "trade_value"),
}
DEPOSIT_COUNT = [(10, 4), (30, 8), (75, 16), (150, 32), (400, 64)]


def load_module(name):
    path = Path(__file__).resolve().with_name(name + ".py")
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def old_nodes(folder):
    nodes = {}
    for path in sorted(folder.glob("*.json")):
        for node in json.loads(path.read_text(encoding="utf8")).get("nodes", []):
            nodes[node["id"]] = {**node, "tree": path.stem}
    return nodes


def flat_refs(requires):
    for req in requires:
        if req.get("type") == "node":
            yield req["id"].partition(":")[2] or req["id"]
        elif "of" in req:
            yield from flat_refs(req["of"])


def stone_filter(spec):
    namespaces, _, rest = spec.partition(":")
    if not rest or rest == "*":
        return [(ns, None) for ns in namespaces.split(",")]
    return [(namespaces, rest.split(","))]


def stone_group(recipe_id, filters):
    namespace, _, path = recipe_id.partition(":")
    head = re.split(r"[_/]", path)[0]
    for name, specs in filters.items():
        if any(ns == namespace and heads is not None and head in heads for ns, heads in specs):
            return name
    for name, specs in filters.items():
        if any(ns == namespace and heads is None for ns, heads in specs):
            return name
    return next((name for name, specs in filters.items() if any(ns == "*" for ns, _ in specs)), None)


def parse_groups(path, old):
    rows = []
    for line in path.read_text(encoding="utf8").splitlines()[1:]:
        if not line.strip():
            continue
        tree, new_id, category, kind, olds, override = (line.split("|") + [""])[:6]
        rows.append({"tree": tree, "id": new_id, "category": category, "kind": kind, "olds": olds.split(), "override": int(override) if override else None})
    return rows


def expand(rows, old):
    problems = []
    owner = defaultdict(list)
    stone = {}
    for row in rows:
        expanded = []
        for token in row["olds"]:
            if token.startswith("INVEST:"):
                _, key, span = token.split(":")
                a, b = map(int, span.split("-"))
                expanded += [f"{key}_{i}" for i in range(a, b + 1)]
            elif token.startswith("stonecutter#"):
                stone[row["id"]] = []
                for part in token.split("#", 1)[1].split(";"):
                    stone[row["id"]] += stone_filter(part)
                expanded.append("stonecutter")
                owner.setdefault("stonecutter", []).append(row["id"]) if row["id"] == "stonecutter" else None
            else:
                expanded.append(token)
        row["expanded"] = [t for t in expanded if t != "NEW"]
        row["fresh"] = "NEW" in expanded
        for t in row["expanded"]:
            if t not in old:
                problems.append(f"unknown old id {t} in {row['id']}")
            elif t != "stonecutter":
                owner[t].append(row["id"])
    for old_id in old:
        if old_id != "stonecutter" and len(owner[old_id]) != 1:
            problems.append(f"old id {old_id} mapped {len(owner[old_id])} times {owner[old_id]}")
    if "stonecutter" in old:
        recipes = [u["id"] for u in old["stonecutter"].get("unlocks", []) if u.get("type") == "recipe"]
        counts = Counter(stone_group(r, stone) for r in recipes)
        if None in counts:
            problems.append(f"stonecutter recipes without group: {counts[None]}")
        problems.append("stonecutter split " + json.dumps({k: v for k, v in counts.items() if k}))
    new_ids = Counter(r["id"] for r in rows)
    problems += [f"duplicate new id {k}" for k, v in new_ids.items() if v > 1]
    return owner, stone, problems


def reduce_edges(parents, order):
    reach = {}
    for key in order:
        mask = set()
        for p in parents[key]:
            mask |= reach[p] | {p}
        reach[key] = mask
    return {k: [p for p in parents[k] if not any(q != p and p in reach[q] for q in parents[k])] for k in order}


def topo(parents):
    pending = {k: len(v) for k, v in parents.items()}
    kids = defaultdict(list)
    for k, v in parents.items():
        for p in v:
            kids[p].append(k)
    queue = sorted(k for k, n in pending.items() if n == 0)
    order = []
    while queue:
        k = queue.pop(0)
        order.append(k)
        for c in sorted(kids[k]):
            pending[c] -= 1
            if pending[c] == 0:
                queue.append(c)
    return order, [k for k in parents if k not in set(order)]


def find_cycles(parents, nodes, limit=8):
    cycles = []
    color = {}

    def visit(k, path):
        if len(cycles) >= limit:
            return
        color[k] = 1
        path.append(k)
        for p in parents[k]:
            if p not in nodes:
                continue
            if color.get(p) == 1:
                cycles.append(path[path.index(p):] + [p])
            elif p not in color:
                visit(p, path)
        path.pop()
        color[k] = 2

    for k in sorted(nodes):
        if k not in color:
            visit(k, [])
    return cycles


def build(rows, old, owner, model, sim):
    by_id = {r["id"]: r for r in rows}
    for r in rows:
        levels = [old[t].get("level", 1) for t in r["expanded"]]
        r["base"] = r["override"] if r["override"] else (min(levels) if levels else 1)
        r["qid"] = f"{r['tree']}:{r['id']}"
    parents = {r["qid"]: [] for r in rows}
    for r in rows:
        seen = set()
        for t in r["expanded"]:
            for ref in flat_refs(old[t].get("requires", [])):
                for target in owner.get(ref, []):
                    q = f"{by_id[target]['tree']}:{target}"
                    if q != r["qid"] and q not in seen:
                        seen.add(q)
                        parents[r["qid"]].append(q)
    order, cyclic = topo(parents)
    problems = []
    if cyclic:
        problems.append(f"cycles after merge: {len(cyclic)} nodes")
        problems += [" -> ".join(c) for c in find_cycles(parents, set(cyclic))]
        return None, problems
    parents = reduce_edges(parents, order)
    qmap = {r["qid"]: r for r in rows}
    raw_depth = {}
    for k in order:
        raw_depth[k] = 1 + max((raw_depth[p] for p in parents[k]), default=0)
    depth = {}
    pruned = 0
    for k in order:
        ps = list(parents[k])
        while ps and 1 + max(depth[p] for p in ps) > DEPTH_CAP:
            deepest = max(ps, key=lambda p: depth[p])
            ps.remove(deepest)
            pruned += 1
            ps += [g for g in parents[deepest] if g not in ps]
        parents[k] = ps
        depth[k] = 1 + max((depth[p] for p in ps), default=0)
    if pruned:
        problems.append(f"depth cap {DEPTH_CAP}: dropped {pruned} deepest requires edges (max raw depth {max(raw_depth.values())})")
    total = sum(e["nodes"] for e in model["eras"])
    ranked = sorted(rows, key=lambda r: (r["base"], raw_depth[r["qid"]], r["id"]))
    start = 0
    for e in model["eras"]:
        size = round(e["nodes"] * len(rows) / total)
        members = ranked[start:start + size] if e is not model["eras"][-1] else ranked[start:]
        start += size
        span = e["to"] - e["from"] + 1
        for i, r in enumerate(members):
            r["level"] = r["override"] or e["from"] + i * span // max(1, len(members))
    for k in order:
        r = qmap[k]
        r["level"] = max([r["level"]] + [qmap[p]["level"] for p in parents[k]])
    trimmed = 0
    for k in order:
        ps = parents[k]
        if len(ps) > REQUIRES_MAX:
            trimmed += 1
            ps.sort(key=lambda p: (-qmap[p]["level"], -depth[p], p))
            parents[k] = ps[:REQUIRES_MAX]
    if trimmed:
        problems.append(f"requires trimmed to {REQUIRES_MAX} on {trimmed} nodes")
    for r in rows:
        r["requires"] = parents[r["qid"]]
        r["depth"] = depth[r["qid"]]
        r["era"] = sim.era_of(model, r["level"])
        r["cost"] = sim.price(model, r["level"], "buff" if r["tree"] == "buffs" and not r["category"].startswith("invest") else ("milestone" if r["kind"] == "m" else "detail"))
        hours = sim.interp(model["time_hours"], r["level"]) * (1.0 if r["kind"] == "m" else 0.5)
        r["time"] = f"{int(hours)}h{round(hours % 1 * 60)}m" if hours >= 1 else f"{max(1, round(hours * 60))}m"
    return rows, problems


def task_plan(r, index):
    cat = "brass_metal" if r["tree"] == "metallurgy" and r["category"] == "brass" else r["category"]
    a, b = TASKS.get(cat, ("craft", "deposit"))
    primary = a if index % 2 == 0 else b
    plan = [primary]
    if r["kind"] == "m" and primary != "deposit" and r["tree"] != "buffs":
        count = next(c for lv, c in DEPOSIT_COUNT if r["level"] <= lv)
        plan.append(f"deposit<={count}")
    return plan


def write_outputs(rows, old, owner, map_path, nodes_path):
    index = Counter()
    for r in sorted(rows, key=lambda r: (r["tree"], r["category"], r["level"], r["id"])):
        r["tasks"] = task_plan(r, index[(r["tree"], r["category"])])
        index[(r["tree"], r["category"])] += 1
    lines = ["old_tree\told_id\told_level\taction\tnew_tree\tnew_id"]
    for old_id, node in old.items():
        targets = owner.get(old_id, [])
        if old_id == "stonecutter":
            targets = [r["id"] for r in rows if any(t == "stonecutter" for t in r["expanded"])]
        by_id = {r["id"]: r for r in rows}
        for target in targets:
            r = by_id[target]
            merged = len(r["expanded"]) > 1 and old_id != "stonecutter"
            action = "split" if old_id == "stonecutter" else ("merged" if merged else ("moved" if r["tree"] != node["tree"] else "kept"))
            if action == "kept" and r["id"] != old_id:
                action = "renamed"
            lines.append(f"{node['tree']}\t{old_id}\t{node.get('level', 0)}\t{action}\t{r['tree']}\t{r['id']}")
    map_path.write_text("\n".join(lines) + "\n", encoding="utf8")
    out = ["tree\tid\tcategory\tkind\tlevel\tera\tdepth\tcost\ttime\trequires\ttasks\told_ids"]
    for r in sorted(rows, key=lambda r: (r["tree"], r["level"], r["id"])):
        olds = " ".join(r["expanded"]) if not r["fresh"] else "NEW"
        out.append("\t".join([r["tree"], r["id"], r["category"], r["kind"], str(r["level"]), r["era"], str(r["depth"]), str(r["cost"]), r["time"], " ".join(r["requires"]), " ".join(r["tasks"]), olds]))
    nodes_path.write_text("\n".join(out) + "\n", encoding="utf8")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Old to new research node mapping generator and checker.")
    parser.add_argument("--old", type=Path, default=TREES)
    parser.add_argument("--groups", type=Path, default=GROUPS)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)
    sim = load_module("balance_sim")
    model = json.loads(sim.MODEL.read_text(encoding="utf8"))
    old = old_nodes(args.old)
    rows = parse_groups(args.groups, old)
    owner, stone, problems = expand(rows, old)
    hard = [p for p in problems if not p.startswith("stonecutter split")]
    built, notes = build(rows, old, owner, model, sim) if not hard else (None, [])
    for p in problems + notes:
        print(p)
    if built is None:
        return 1
    print(f"old {len(old)} new {len(built)}")
    print("per tree", dict(Counter(r["tree"] for r in built)))
    print("per era", {e["id"]: sum(1 for r in built if r["era"] == e["id"]) for e in model["eras"]})
    print("kinds", dict(Counter(r["kind"] for r in built)))
    print("max depth", max(r["depth"] for r in built), "max requires", max(len(r["requires"]) for r in built))
    window = model["alpha_level_window"]
    print(f"visible at level<={window}:", sum(1 for r in built if r["level"] <= window))
    levels = {r["level"] for r in built}
    print("empty levels 1..250:", [lv for lv in range(1, 251) if lv not in levels])
    if args.write:
        write_outputs(built, old, owner, OUT_MAP, OUT_NODES)
        print("tasks", dict(Counter(t.split("<=")[0] for r in built for t in r["tasks"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
