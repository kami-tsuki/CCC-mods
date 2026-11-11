import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import audit_model

NATURAL = re.compile(r"(_log$|_wood$|_stem$|_hyphae$|bamboo_block|mushroom$|fungus$|:egg$|:ice$|snowball|sea_pickle|scute|nautilus|upgrade_smithing_template|netherite_scrap|ancient_debris|:bauxite$|:galena|autunite|:raw_|heart$|heart_crystal|soul_heart|enderstone|:cinnamon$|palm_log|:blue_ice|:packed_ice|:snow|_sapling$|:sand$|:red_sand|:gravel|:clay|:ink_sac|:slime_ball|bone$|:lunar_|:mars_|:venus_|:mercury_|northstar:.*_(stone|sand|ice|ore)$|legendary_monsters:(enderitium_gem|enderitium_ingot))")
LABEL_SKIP = re.compile(r"((exposed|weathered|oxidized|waxed)_|honeycomb_block|_axe(\+[0-9]+)?$|_log|_wood|_stem|_hyphae|_planks|bamboo_block|brown_mushroom|red_mushroom|:egg$|:ice$|snowball|sea_pickle|turtle_scute|nautilus|netherite_upgrade|netherite_block|soul_heart|heart$|enderstone|:bauxite|galena|autunite|blaze_cake|:cinnamon$|palm_|cinnamon_)")


def default_mods():
    import os
    path = os.environ.get("KAMI_MODS_DIR")
    return Path(path) if path and Path(path).is_dir() else None


def obtainable(m, recipes, occ, produced, base_world, available):
    count, satisfied, work, have = {}, {}, [], set()
    for rid, f in recipes.items():
        ns = m.rnodes.get(rid)
        if ns and not ns & available:
            continue
        count[rid] = len(f["slots"])
        satisfied[rid] = [False] * len(f["slots"])
        if not f["slots"]:
            work.append(rid)

    def give(item):
        if item in have:
            return
        have.add(item)
        for rid, si in occ.get(item, ()):
            if rid in satisfied and not satisfied[rid][si]:
                satisfied[rid][si] = True
                count[rid] -= 1
                if count[rid] == 0:
                    work.append(rid)

    for item in list(base_world) + [i for i in occ if i not in produced]:
        give(item)
    while work:
        for out in recipes[work.pop()]["outs"]:
            give(out)
    return have


def collect(m):
    recipes = m.recipes
    items = set(m.by_out) | {x for f in recipes.values() for s in f["slots"] for x in s}
    base_world = set(m.world) | {i for i in items if NATURAL.search(i)}
    base_world |= {i for i in items if i.startswith("fruitsdelight:") and not re.search(r"slice|flesh|dried|jam|pie", i)}
    produced = set(m.by_out)
    occ = defaultdict(list)
    for rid, f in recipes.items():
        for si, s in enumerate(f["slots"]):
            for i in s:
                occ[i].append((rid, si))
    found = []
    for key in m.nodes:
        have = obtainable(m, recipes, occ, produced, base_world, m.closure(key, False) | {key})
        groups = defaultdict(list)
        for rid in m.sel[key]:
            if key in m.rnodes[rid] and rid in recipes:
                groups[tuple(recipes[rid]["outs"][:1])].append(rid)
        for first, rids in groups.items():
            if not first or first[0] in have:
                continue
            best = None
            for rid in sorted(rids):
                miss = [s for s in recipes[rid]["slots"] if not s & have]
                if best is None or len(miss) < len(best[1]):
                    best = (rid, miss)
            if best and best[1]:
                found.append((key, first[0], best[0], best[1]))
    return found


def edges(m, found):
    viol = defaultdict(dict)
    for key, out, rid, missing in found:
        for slot in missing:
            slot = sorted(slot)
            label = slot[0] if len(slot) <= 2 else f"{slot[0]}+{len(slot) - 1}"
            viol[label].setdefault(key, (out, slot))
    result = defaultdict(lambda: defaultdict(set))
    for label, nodes in viol.items():
        if LABEL_SKIP.search(label):
            continue
        for key, (out, slot) in nodes.items():
            if any(p in nodes for p in m.definite[key]):
                continue
            gaters = {g for i in slot for rid in m.by_out.get(i, ()) for g in m.rnodes.get(rid, ())}
            own = m.closure(key, False)
            ranked = sorted((g for g in gaters if g != key and g not in own and key not in m.closure(g, False)), key=lambda g: m.nodes[g]["level"])
            if ranked:
                result[key][ranked[0]].add(label)
    return result


def is_food(m, key):
    return key.startswith("technology:") and m.nodes[key].get("category") == "food"


def lines(m, result, tree):
    out = []
    for key, by in result.items():
        if tree and not key.startswith(tree + ":"):
            continue
        for gater, labels in by.items():
            out.append(f"{key}(L{m.nodes[key]['level']}) + requires {gater}(L{m.nodes[gater]['level']}) : {', '.join(sorted(labels)[:6])}")
    return sorted(out)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Report every (node, missing producer node) edge: a recipe a node unlocks needs an ingredient whose producing recipe is gated by a node outside its prerequisites. Slow (minutes).")
    parser.add_argument("--mods", type=Path, default=default_mods(), help="mods folder whose parent holds libraries, kubejs and tacz (default: the Test-Server copy, env KAMI_MODS_DIR)")
    parser.add_argument("--tree", help="report only nodes of this tree")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    _, m = audit_model.load_model(args.mods)
    result = edges(m, collect(m))
    food = {k: v for k, v in result.items() if is_food(m, k)}
    report = lines(m, {k: v for k, v in result.items() if k not in food}, args.tree)
    info = lines(m, food, args.tree)
    if args.json:
        print(json.dumps({"ok": not report, "edges": report, "food_informational": info}, ensure_ascii=False))
    else:
        print("missing producer edges", len(report))
        for line in report:
            print(line)
        print("food nodes (owner rule: level 10, flat, informational only)", len(info))
    return 1 if report else 0


if __name__ == "__main__":
    sys.exit(main())
