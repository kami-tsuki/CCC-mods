import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
RESEARCH = ROOT / "KamiClaims/src/main/resources/assets/kami_claims/research"
TREES = RESEARCH / "trees"
TAG_FORMS = {
    "ingots": ["{m}_ingot"], "plates": ["{m}_plate", "{m}_sheet"], "nuggets": ["{m}_nugget"], "rods": ["{m}_rod"], "storage_blocks": ["{m}_block"],
    "raw_materials": ["raw_{m}"], "dusts": ["{m}_dust", "{m}_powder"], "gears": ["{m}_gear"], "wires": ["{m}_wire"],
}


def glob_re(pattern):
    return re.compile("^" + ".*".join(re.escape(p) for p in pattern.split("*")) + "$")


def concrete(spec):
    return "*" not in spec and not spec.startswith("#")


def tag_paths(tag):
    body = tag.split(":", 1)[-1]
    kind, _, metal = body.partition("/")
    return [f.format(m=metal) for f in TAG_FORMS.get(kind, [])] if metal else []


def baseline_ids():
    path = RESEARCH / "groups" / "basics.json"
    if not path.exists():
        return set()
    return {u["id"] for u in json.loads(path.read_text(encoding="utf8")).get("unlocks", []) if u.get("type") in ("output", "block", "recipe")}


class Gates:
    def __init__(self, nodes):
        self.exact = defaultdict(lambda: defaultdict(set))
        self.globs = defaultdict(list)
        self.mods = defaultdict(set)
        self.base = baseline_ids()
        for key, node in nodes.items():
            for u in node.get("unlocks", []):
                t = u["type"]
                if t in ("output", "block", "recipe"):
                    spec, kind = u["id"], ("block" if t == "block" else "recipe" if t == "recipe" else "output")
                elif t == "recipes" and u.get("output"):
                    spec, kind = u["output"], "output"
                elif t == "mod":
                    self.mods[u["id"]].add(key)
                    continue
                else:
                    continue
                if spec in self.base and kind != "recipe":
                    continue
                if concrete(spec) or spec.startswith("#"):
                    self.exact[kind][spec].add(key)
                else:
                    self.globs[kind].append((glob_re(spec), key))

    def gaters(self, subject, kinds):
        found = set()
        if subject in self.base and "block" not in kinds:
            return found
        specs = [subject]
        if subject.startswith("#"):
            ns = subject.split(":", 1)[0].lstrip("#")
            specs += [f"{ns}:{p}" for p in tag_paths(subject)] + [f"minecraft:{p}" for p in tag_paths(subject)] + [f"create:{p}" for p in tag_paths(subject)]
        for kind in kinds:
            for spec in specs:
                found |= self.exact[kind].get(spec, set())
            if not subject.startswith("#"):
                found |= {key for rx, key in self.globs[kind] if rx.match(subject)}
        if not subject.startswith("#"):
            found |= self.mods.get(subject.split(":", 1)[0], set())
        return found


def subject_of(task):
    t = task["type"]
    if t in ("craft", "smelt", "process"):
        return [task["output"]] if task.get("output") else [], ("output", "recipe")
    if t == "deposit":
        return ([task["item"]] if "item" in task else list(task.get("items", []))), ("output",)
    if t == "place":
        return [task["block"]], ("block", "output")
    return [], ()


def ancestors(parents):
    memo = {}

    def walk(k):
        if k in memo:
            return memo[k]
        memo[k] = set()
        out = set()
        for p in parents.get(k, []):
            out |= {p} | walk(p)
        memo[k] = out
        return out

    for k in parents:
        walk(k)
    return memo


def locked(gates, anc, key, subject, kinds):
    return sorted(g for g in gates.gaters(subject, kinds) if g == key or g not in anc[key])


def violations(nodes, parents):
    gates = Gates(nodes)
    anc = ancestors(parents)
    out = []
    for key, node in nodes.items():
        for i, task in enumerate(node.get("tasks", [])):
            subjects, kinds = subject_of(task)
            for s in subjects:
                by = locked(gates, anc, key, s, kinds)
                if by:
                    out.append({"node": key, "index": i, "type": task["type"], "subject": s, "by": by})
    return out


def recipe_index(mods_dir):
    import zipfile
    out = {}
    for jar in sorted(Path(mods_dir).glob("*.jar")):
        try:
            z = zipfile.ZipFile(jar)
        except zipfile.BadZipFile:
            continue
        for name in z.namelist():
            parts = name.split("/")
            if len(parts) > 3 and parts[0] == "data" and parts[2] in ("recipe", "recipes") and name.endswith(".json"):
                try:
                    r = json.loads(z.read(name))
                except ValueError:
                    continue
                x = r.get("result", r.get("output")) if isinstance(r, dict) else None
                x = x[0] if isinstance(x, list) and x else x
                x = x.get("id", x.get("item")) if isinstance(x, dict) else x
                if isinstance(x, str):
                    out[f"{parts[1]}:{'/'.join(parts[3:])[:-5]}"] = x
    return out


def audit_index(mods_dir):
    sys.path.insert(0, str(HERE))
    import audit_model
    ra = audit_model.load_audit(mods_dir)
    pack = ra.load_pack()
    base = {rid: j for rid, j in pack["recipes"].items() if ra.condition_ok(j, pack["namespaces"])}
    changed, removed, tag_adds, _ = ra.kubejs(base, ra.resolve_tags(pack["tags"]["item"], {}))
    tags = ra.resolve_tags(pack["tags"]["item"], {k.split(":", 1)[1]: v for k, v in tag_adds.items() if k.startswith("item:")})
    merged = {rid: j for rid, j in base.items() if rid not in set(removed)}
    merged.update(changed)
    out = {}
    for rid, j in merged.items():
        if isinstance(j, dict) and "type" in j:
            outs = ra.recipe_facts(rid, j, tags)["outs"]
            if outs:
                out[rid] = outs[0]
    return out


def recipe_violations(nodes, parents, mods_dir):
    import fnmatch
    try:
        recipes = audit_index(mods_dir)
    except Exception as error:
        print("kubejs replay unavailable, jar recipes only:", error)
        recipes = recipe_index(mods_dir)
    by_out = defaultdict(list)
    for rid, item in recipes.items():
        by_out[item].append(rid)
    rids = list(recipes)
    rgate, ogate = defaultdict(set), defaultdict(set)
    for key, node in nodes.items():
        for u in node.get("unlocks", []):
            if u["type"] == "recipe":
                for rid in ([u["id"]] if "*" not in u["id"] else fnmatch.filter(rids, u["id"])):
                    rgate[rid].add(key)
            elif u["type"] in ("output", "block"):
                ogate[u["id"]].add(key)
    base = baseline_ids()
    anc = ancestors(parents)
    out = []
    for key, node in nodes.items():
        for i, task in enumerate(node.get("tasks", [])):
            item = task.get("output")
            if task["type"] not in ("craft", "smelt", "process") or not item or item in base or item not in by_out:
                continue
            ok = any(
                all(g in anc[key] for g in ogate.get(item, ())) and (not rgate.get(rid) or any(g in anc[key] for g in rgate[rid]))
                for rid in by_out[item]
            )
            if not ok:
                gates = sorted(set().union(*[rgate.get(r, set()) for r in by_out[item]]) | ogate.get(item, set()))
                out.append({"node": key, "index": i, "type": task["type"], "subject": item, "by": gates})
    return out


def mods_dir():
    import os
    path = Path(os.environ.get("KAMI_MODS_DIR", "E:/MC_Prism/Instances/1.21.1/minecraft/mods"))
    return path if path.is_dir() else None


def load_trees(folder):
    sys.path.insert(0, str(HERE))
    import tree_metrics
    nodes = tree_metrics.load(folder)
    parents, _ = tree_metrics.build_graph(nodes)
    return nodes, parents


def main(argv=None):
    parser = argparse.ArgumentParser(description="Find research tasks whose subject is gated by their own node or a non-prerequisite (item gates and recipe-id gates).")
    parser.add_argument("--trees", type=Path, default=TREES)
    parser.add_argument("--mods", type=Path, default=mods_dir(), help="mods folder with the recipe jars (env KAMI_MODS_DIR)")
    args = parser.parse_args(argv)
    nodes, parents = load_trees(args.trees)
    found = violations(nodes, parents)
    print("item gate violations", len(found), dict(Counter(v["type"] for v in found)))
    recipe = recipe_violations(nodes, parents, args.mods) if args.mods else []
    print("recipe gate violations", len(recipe) if args.mods else "skipped (no mods folder)")
    for v in (found + recipe)[:40]:
        print(v)
    return 1 if found or recipe else 0


if __name__ == "__main__":
    sys.exit(main())
