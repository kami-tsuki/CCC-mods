import fnmatch
import importlib.util
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OLD_UNLOCKS = ROOT / "scripts/validate/old_unlocks.json"
STONE_MOVED = 11
ACCEPTED_DROPPED = {"kami_js:tacz_c/fluid_bullet_6851fury*", "tacz_c:bullet_6851fury*", "tacz_c:case_6851fury_cutting", "tfmg:crafting/materials/lithium_blade", "create:crafting/appliances/cardboard_*"}
MODEL = ROOT / "scripts/validate/balance_model.json"
TREES_DIR = ROOT / "KamiClaims/src/main/resources/assets/kami_claims/research/trees"
LANG_DIR = ROOT / "KamiClaims/src/main/resources/assets/kami_claims/lang"
STONE_FAMILY = re.compile(r"^(stonecutter|masonry_.+)$")
BIG_ALLOWED = {"technology:shafts_cogs", "technology:industrial_gears"}
BUFF_FAMILIES = ["instant_health", "regeneration", "speed", "haste", "resistance", "jump_boost", "strength", "absorption", "health_boost", "luck"]
GAP_RUN_MAX = {"stone": 0, "early_industry": 0, "steel_chemistry": 0, "advanced": 1, "late": 2, "endgame": 40}
DELTA_MAX = {"stone": 10, "early_industry": 30, "steel_chemistry": 65, "advanced": 105, "late": 195, "endgame": 340}
DEPOSIT_CAPS = [(10, 4), (30, 8), (75, 16), (150, 32), (400, 64)]
LIMITS = {
    "item_reuse_max": 20, "item_reuse_tree_max": 8, "buffs_nodes_range": (40, 50), "total_nodes_range": (550, 650), "alpha_range": (260, 330), "alpha_window": 50,
    "deposit_share_max": 0.4, "task_types_min": 8, "month_ratio_range": (1.5, 2.0), "serial_days_range": (80, 130), "unlocks_hard_max": 60,
    "icon_per_category_max": 3, "cost_tolerance": 0.25, "time_tolerance": 0.5, "depth_max": 40,
}
FOOD_LEVEL_MAX = 10
FOOD_COST_MAX = 0
FOOD_HUB = "technology:food_kitchen"
FOOD_EXTRA = {"technology:food_create": {"technology:mechanical_press"}}
NUMBERED = re.compile(r"(.+)_(\d+)$")
TIME_UNITS = {"d": 86400, "h": 3600, "m": 60, "s": 1}


def parse_time(text):
    return sum(int(n) * TIME_UNITS[u] for n, u in re.findall(r"(\d+)([dhms])", str(text)))


def load_sim():
    spec = importlib.util.spec_from_file_location("balance_sim", Path(__file__).resolve().with_name("balance_sim.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_lock():
    spec = importlib.util.spec_from_file_location("tree_lock", Path(__file__).resolve().with_name("tree_lock.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def deposit_items(task):
    if task.get("type") != "deposit":
        return []
    return [task["item"]] if "item" in task else list(task.get("items", []))


def unlock_union(nodes):
    data, caps, owners = defaultdict(set), Counter(), defaultdict(list)
    for key, node in nodes.items():
        for u in node.get("unlocks", []):
            t = u["type"]
            if t == "capacity":
                caps[u["key"]] += u["add"]
            elif t in ("buff_points", "loan_slots", "safe_chunks"):
                caps[t] += u["add"]
            elif t == "buff":
                data["buff"].add(u["effect"])
            elif t == "recipes":
                data["recipes"].add(json.dumps(u, sort_keys=True))
            elif t in ("recipe", "output", "block", "mod", "loan", "feature"):
                data[t].add(u["id"])
                owners[(t, u["id"])].append(key)
    return data, caps, owners


def covered(selector, selectors):
    return any("*" in other and fnmatch.fnmatchcase(selector, other) for other in selectors)


def extra_checks(nodes, parents, depths):
    sim = load_sim()
    model = json.loads(MODEL.read_text(encoding="utf8"))
    t = LIMITS
    rows, notes = [], []

    def add(name, value, ok, limit=""):
        rows.append({"name": name, "value": value, "limit": limit, "pass": bool(ok)})

    keys = list(nodes)

    def is_food(k):
        return nodes[k]["tree"] == "technology" and nodes[k].get("category") == "food"
    level = {k: nodes[k].get("level", 0) for k in keys}
    era = {k: nodes[k].get("era", "") for k in keys}
    present = set(level.values())
    for e in model["eras"]:
        run = best = 0
        for lv in range(e["from"], min(e["to"], 250) + 1):
            run = 0 if lv in present else run + 1
            best = max(best, run)
        add(f"1 empty-level run in era {e['id']}", best, best <= GAP_RUN_MAX[e["id"]], GAP_RUN_MAX[e["id"]])
    food = [k for k in keys if is_food(k)]
    food_late = [k for k in food if level[k] > FOOD_LEVEL_MAX]
    food_deep = [k for k in food if set(parents[k]) != ({FOOD_HUB} | FOOD_EXTRA.get(k, set()) if k != FOOD_HUB else set())]
    food_heavy = [k for k in food if nodes[k].get("cost", 0) > FOOD_COST_MAX or nodes[k].get("tasks") or era[k] != "stone" or nodes[k].get("kind") != ("milestone" if k == FOOD_HUB else "detail")]
    add("18 food nodes above level 10", len(food_late), not food_late, 0)
    add("18 food nodes not requiring exactly the kitchen hub", len(food_deep), not food_deep, 0)
    add("18 food nodes with tasks, cost, wrong era or kind", len(food_heavy), not food_heavy, 0)
    add("18 food nodes found", len(food), bool(food), ">= 1")
    window = t["alpha_window"]
    alpha = sum(1 for lv in level.values() if lv <= window)
    add("2 alpha-visible nodes at country level 25", alpha, t["alpha_range"][0] <= alpha <= t["alpha_range"][1], str(t["alpha_range"]))
    notes.append(f"2 alpha-visible at level 1: {sum(1 for lv in level.values() if lv <= 26)}, at 45: {sum(1 for lv in level.values() if lv <= 70)}")
    total_budget = sum(e["nodes"] for e in model["eras"])
    counts = Counter(era.values())
    for e in model["eras"]:
        want = e["nodes"] * len(keys) / total_budget
        add(f"3 era node count {e['id']}", counts[e["id"]], abs(counts[e["id"]] - want) <= 0.15 * want, f"{want:.0f} +-15%")
    add("3 total nodes", len(keys), t["total_nodes_range"][0] <= len(keys) <= t["total_nodes_range"][1], str(t["total_nodes_range"]))
    buffs = sum(1 for n in nodes.values() if n["tree"] == "buffs")
    add("3 buffs nodes", buffs, t["buffs_nodes_range"][0] <= buffs <= t["buffs_nodes_range"][1], str(t["buffs_nodes_range"]))
    trees = sorted({n["tree"] for n in nodes.values()})
    tree_depth = {tr: max(depths[k] for k in keys if nodes[k]["tree"] == tr) for tr in trees}
    for tr in trees:
        add(f"4 depth of tree {tr}", tree_depth[tr], tree_depth[tr] <= t["depth_max"], t["depth_max"])
    big = Counter(n["tree"] for n in nodes.values()).most_common(3)
    layer = Counter(depths[k] for k in keys)
    notes.append("4 layer width (informational, 12-20 per layer is not reachable with 485 nodes over 24 layers): " + ", ".join(f"{tr} mean {n / tree_depth[tr]:.1f}" for tr, n in big) + f"; widest layer {max(layer.values())}")
    entries = [(k, task) for k in keys for task in nodes[k].get("tasks", [])]
    kinds = Counter(task["type"] for _, task in entries)
    share = kinds["deposit"] / max(1, len(entries))
    add("5 deposit share of task entries", round(share, 3), share <= t["deposit_share_max"], t["deposit_share_max"])
    add("5 task types in use", len(kinds), len(kinds) >= t["task_types_min"], f">= {t['task_types_min']}")
    weak = [k for k in keys if nodes[k].get("kind") == "milestone" and era[k] in ("stone", "early_industry", "steel_chemistry") and nodes[k]["tree"] != "buffs" and not is_food(k) and nodes[k].get("tasks") and all(x["type"] == "deposit" for x in nodes[k].get("tasks", []))]
    add("5 era 1-3 milestones with only deposit tasks (buffs excluded)", len(weak), not weak, 0)
    if weak:
        notes.append("5 deposit-only milestones: " + ", ".join(weak[:15]))
    users, tree_users, over_cap = defaultdict(set), defaultdict(lambda: defaultdict(set)), []
    for k, task in entries:
        for i in deposit_items(task):
            users[i].add(k)
            tree_users[nodes[k]["tree"]][i].add(k)
        if task["type"] == "deposit" and task.get("count", 0) > next(c for lv, c in DEPOSIT_CAPS if level[k] <= lv):
            over_cap.append(k)
    worst = max((len(v) for v in users.values()), default=0)
    add("6 most nodes sharing one item", worst, worst <= t["item_reuse_max"], t["item_reuse_max"])
    worst_tree = max((len(v) for tu in tree_users.values() for v in tu.values()), default=0)
    add("6 most nodes sharing one item in a tree", worst_tree, worst_tree <= t["item_reuse_tree_max"], t["item_reuse_tree_max"])
    add("6 deposit counts above the level band cap", len(over_cap), not over_cap, 0)
    bad_cost, bad_detail = [], []
    for k in keys:
        n = nodes[k]
        if is_food(k):
            continue
        buff = n["tree"] == "buffs" and not str(n.get("category", "")).startswith("invest")
        base = sim.price(model, level[k], "milestone")
        if n.get("kind") == "milestone":
            want = sim.price(model, level[k], "buff" if buff else "milestone")
            if abs(n["cost"] - want) > t["cost_tolerance"] * max(want, 1) + 1:
                bad_cost.append(k)
        elif not 0.15 * base - 1 <= n["cost"] <= 0.5 * base + 1:
            bad_detail.append(k)
    add("7 milestone cost outside +-25% of price(level)", len(bad_cost), not bad_cost, 0)
    add("7 detail cost outside [0.15, 0.5] of milestone price", len(bad_detail), not bad_detail, 0)
    if bad_cost:
        notes.append(f"7 cost outliers: {bad_cost[:6]}")
    alpha_cost = sum(n["cost"] for k, n in nodes.items() if level[k] <= window)
    month = sum(sim.interp(model["inflow_per_day"], sim.interp(model["level_by_day"], day)) * model["research_share"] for day in range(30))
    ratio = alpha_cost / month
    add("7 alpha cost / month inflow", round(ratio, 2), t["month_ratio_range"][0] <= ratio <= t["month_ratio_range"][1], str(t["month_ratio_range"]))
    bad_time = []
    for k in keys:
        if is_food(k):
            continue
        hours = sim.interp(model["time_hours"], level[k]) * (1.0 if nodes[k].get("kind") == "milestone" else 0.5)
        if abs(parse_time(nodes[k].get("time", "0m")) / 3600 - hours) > t["time_tolerance"] * hours + 0.02:
            bad_time.append(k)
    add("8 time outside +-50% of table", len(bad_time), not bad_time, 0)
    days = sum(parse_time(n.get("time", "0m")) for n in nodes.values()) / 86400
    add("8 serial time days", round(days, 1), t["serial_days_range"][0] <= days <= t["serial_days_range"][1], str(t["serial_days_range"]))
    if OLD_UNLOCKS.exists():
        old = json.loads(OLD_UNLOCKS.read_text(encoding="utf8"))
        new, caps, owners = unlock_union(nodes)
        lost = {k: sorted(x for x in set(old[k]) - new[k] if x not in ACCEPTED_DROPPED and not covered(x, new[k])) for k in ("recipe", "output", "block", "mod", "loan", "feature", "buff", "recipes") if k in old}
        lost = {k: v for k, v in lost.items() if v}
        add("9 old unlock selectors lost", sum(len(v) for v in lost.values()), not lost, 0)
        for k, v in lost.items():
            notes.append(f"9 lost {k}: {v[:6]}")
        notes.append("9 selectors added by new nodes: " + str({k: len(new[k] - set(old[k])) for k in new if k in old and new[k] - set(old[k])}))
        bad_caps = {k: (v, caps.get(k, 0)) for k, v in old["capacity_sums"].items() if caps.get(k, 0) != v}
        add("9 capacity sums changed", len(bad_caps), not bad_caps, 0)
        if bad_caps:
            notes.append(f"9 capacity diffs old/new: {bad_caps}")
        dupes = [i for (tp, i), o in owners.items() if tp == "recipe" and len(o) > 1]
        add("9 recipe ids in more than one node (old had duplicates too)", len(dupes), len(dupes) <= old.get("recipe_duplicates", 0), old.get("recipe_duplicates", 0))
        stone = sum(1 for n in nodes.values() for u in n.get("unlocks", []) if u["type"] == "recipe" and STONE_FAMILY.match(n["id"]))
        add("9 stonecutter family recipe unlocks (old 2203)", stone, stone == 2203 - STONE_MOVED, 2203 - STONE_MOVED)
    else:
        add("9 old_unlocks.json present", 0, False, 1)
    inv = defaultdict(list)
    for k, n in nodes.items():
        if n["tree"] == "buffs" and str(n.get("category", "")).startswith("invest"):
            inv[n["category"]].append(k)
    bad_buff = []
    for ks in inv.values():
        ks.sort(key=lambda x: level[x])
        bad_buff += [b for a, b in zip(ks, ks[1:]) if level[b] <= level[a] or a not in parents[b]]
    effect_seen = Counter()
    for n in nodes.values():
        if n["tree"] == "buffs":
            for u in n.get("unlocks", []):
                if u["type"] == "buff":
                    effect_seen[(u["effect"], u.get("amplifier", 0))] += 1
    dup_effect = [e for e, c in effect_seen.items() if c > 1]
    ratios = [f for f in BUFF_FAMILIES if nodes.get(f"buffs:{f}_1") and nodes.get(f"buffs:{f}_2") and nodes[f"buffs:{f}_2"]["cost"] > 20 * nodes[f"buffs:{f}_1"]["cost"]]
    add("10 invest steps not increasing or not chained", len(bad_buff), not bad_buff, 0)
    add("10 buff tier 2 above 20x tier 1", len(ratios), not ratios, 0)
    add("10 buff effect+amplifier used twice", len(dup_effect), not dup_effect, 0)
    bad_delta = [(c, p) for c in keys for p in parents[c] if level[c] - level[p] > DELTA_MAX.get(era[c], 60)]
    add("11 edges with level delta over the era limit", len(bad_delta), not bad_delta, 0)
    if bad_delta:
        notes.append(f"11 worst deltas: {sorted(((level[c] - level[p], c, p) for c, p in bad_delta), reverse=True)[:4]}")
    big_nodes = [k for k in keys if len(nodes[k].get("unlocks", [])) > t["unlocks_hard_max"] and not STONE_FAMILY.match(nodes[k]["id"]) and k not in BIG_ALLOWED]
    empty = [k for k in keys if not nodes[k].get("unlocks") and not nodes[k].get("tasks")]
    add("12 nodes over 60 unlocks outside stonecutter family", len(big_nodes), not big_nodes, 0)
    add("12 nodes with no unlocks and no tasks", len(empty), not empty, 0)
    big_listed = [k for k in keys if len(nodes[k].get("unlocks", [])) > t["unlocks_hard_max"] and k in BIG_ALLOWED]
    if big_nodes or big_listed:
        notes.append(f"12 big nodes: {[(k, len(nodes[k]['unlocks'])) for k in big_nodes[:8] + big_listed]}")
    lang = {c: json.loads((LANG_DIR / f"{c}.json").read_text(encoding="utf8")) for c in ("en_us", "de_de")}
    missing = []
    for k in keys:
        tree, nid = nodes[k]["tree"], nodes[k]["id"]
        m = NUMBERED.match(nid)
        bases = [f"kami_claims.research.node.{tree}.{nid}"] + ([f"kami_claims.research.node.{tree}.{m.group(1)}"] if m else [])
        for c, d in lang.items():
            if not any(b in d for b in bases):
                missing.append(f"{c} {k}")
            elif tree != "buffs" and not any(b + ".desc" in d for b in bases):
                missing.append(f"{c} {k} desc")
    add("13 nodes without lang title or description", len(missing), not missing, 0)
    if missing:
        notes.append(f"13 missing: {missing[:6]}")
    valid = {f"{n['tree']}.{n['id']}" for n in nodes.values()} | {f"{n['tree']}.{NUMBERED.match(n['id']).group(1)}" for n in nodes.values() if NUMBERED.match(n["id"])}
    prefix = "kami_claims.research.node."
    stale = sorted(k for k in lang["en_us"] if k.startswith(prefix) and k[len(prefix):].split(".")[0] in trees and re.sub(r"\.desc$", "", k[len(prefix):]) not in valid)
    add("13 lang keys of removed ids", len(stale), not stale, 0)
    if stale:
        notes.append(f"13 stale: {stale[:6]}")
    nocat = {c for _, c in {(n["tree"], n["category"]) for n in nodes.values()} if not all(f"kami_claims.research.category.{c}" in d for d in lang.values())}
    add("13 categories without title", len(nocat), not nocat, 0)
    per_icon = Counter((n["category"], n.get("icon")) for n in nodes.values())
    shared = [i for i, c in per_icon.items() if c > t["icon_per_category_max"]]
    if shared:
        notes.append(f"14 shared icons: {shared[:6]}")
    add("14 icons shared by more than 3 nodes of a category", len(shared), not shared, 0)
    versions = [p.name for p in sorted(TREES_DIR.glob("*.json")) if not isinstance(json.loads(p.read_text(encoding="utf8")).get("version"), int) or json.loads(p.read_text(encoding="utf8"))["version"] < 1]
    add("17 tree files without version >= 1", len(versions), not versions, 0)
    lock = load_lock()
    locked = lock.violations(nodes, parents)
    add("16 self_locked_tasks", len(locked), not locked, 0)
    mods = lock.mods_dir()
    recipe_locked = lock.recipe_violations(nodes, parents, mods) if mods else []
    add("16 self_locked_recipe_tasks" + ("" if mods else " (skipped: no mods folder)"), len(recipe_locked), not recipe_locked, 0)
    if recipe_locked:
        notes.append("16 self_locked_recipe_tasks: " + str([(v["node"], v["type"], v["subject"]) for v in recipe_locked[:6]]))
    if locked:
        notes.append("16 self_locked_tasks: " + str([(v["node"], v["type"], v["subject"], v["by"][:2]) for v in locked[:6]]))
    hidden_bad = [k for k, n in nodes.items() for r in n.get("requires", []) if r.get("hidden") and n["tree"] != "buffs"]
    add("15 hidden edges outside the buffs tree", len(hidden_bad), not hidden_bad, 0)
    return rows, notes
