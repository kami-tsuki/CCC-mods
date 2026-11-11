import argparse
import json
import re
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import balance_sim as sim
import tree_lock
import tree_mapping as tm
import tree_metrics

TREES = ROOT / "KamiClaims/src/main/resources/assets/kami_claims/research/trees"
LANG = ROOT / "KamiClaims/src/main/resources/assets/kami_claims/lang"
NODES_TSV = ROOT / "docs/tree-nodes.tsv"
ID_MAP = ROOT / "docs/tree-id-map.json"
OLD_UNLOCKS = HERE / "old_unlocks.json"
MODS = Path("E:/MC_Prism/Instances/1.21.1/minecraft/mods")
TREE_ORDER = ["technology", "metallurgy", "military", "tools_armor", "buffs"]
TREE_TITLE = {"tools_armor": "Gear"}
TREE_VERSION = 1
CAP = [(10, 4), (30, 8), (75, 16), (150, 32), (400, 64)]
ADDITIVE = {"buff_points", "loan_slots", "safe_chunks"}
SMELTABLE = re.compile(r"ingot|nugget|brick|glass|steel|coke|charcoal|cooked|baked|dried|ash|salt|netherite_scrap|alloy|bronze|brass|invar|cast_iron|graphite")
PRODUCT = re.compile(r"ingot|powder|dust|plate|sheet|dough|flour|crushed|nugget|wire|rod|gear|concrete|plastic|oil|fuel|acid|compound|chip|board|circuit|brass|alloy|steel|sulfur|salt|cement|glass|pellet|rubber|resin|fiber|cable|coil|lens|capsule|pill|tablet|extract|lubricant|bucket|bottle|cylinder|vial|beaker")
KILL_POOL = [(15, ["minecraft:zombie", "minecraft:skeleton", "minecraft:spider", "minecraft:creeper"]), (40, ["minecraft:enderman", "minecraft:witch", "minecraft:blaze", "minecraft:wither_skeleton"]),
             (100, ["minecraft:piglin_brute", "minecraft:evoker", "minecraft:ravager", "minecraft:guardian"]), (200, ["minecraft:elder_guardian", "minecraft:ravager", "minecraft:warden"]),
             (400, ["minecraft:wither", "minecraft:warden", "minecraft:ender_dragon"])]
KILL_COUNT = [(15, 6), (40, 8), (100, 10), (200, 12), (400, 1)]
MINE_POOL = [(10, ["minecraft:stone", "minecraft:andesite", "minecraft:coal_ore", "minecraft:cobblestone"]), (30, ["minecraft:iron_ore", "minecraft:copper_ore", "create:zinc_ore", "minecraft:deepslate"]),
             (75, ["minecraft:gold_ore", "minecraft:redstone_ore", "minecraft:lapis_ore", "minecraft:deepslate_iron_ore"]), (150, ["minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:nether_quartz_ore", "minecraft:emerald_ore"]),
             (400, ["minecraft:ancient_debris", "minecraft:deepslate_diamond_ore", "minecraft:nether_gold_ore"])]
STRUCTURES = [(30, ["minecraft:village_plains", "minecraft:mineshaft", "minecraft:desert_pyramid"]), (80, ["minecraft:fortress", "minecraft:bastion_remnant", "minecraft:stronghold"]),
              (150, ["minecraft:ancient_city", "minecraft:monument", "minecraft:woodland_mansion"]), (400, ["minecraft:end_city", "minecraft:trial_chambers", "minecraft:ancient_city"])]
PLANETS = [(130, "northstar:moon"), (170, "northstar:mars"), (220, "northstar:venus"), (999, "northstar:mercury")]
METALS = ["iron", "copper", "zinc", "gold", "tin", "lead", "nickel", "silver", "brass", "steel", "aluminum", "tungsten", "titanium", "uranium", "lithium", "osmium"]
ICON_POOL = ["iron_block", "gold_block", "diamond_block", "emerald_block", "lapis_block", "redstone_block", "coal_block", "copper_block", "netherite_block", "amethyst_block", "quartz_block", "prismarine", "sea_lantern", "glowstone", "obsidian", "bricks", "stone_bricks", "deepslate_bricks", "nether_bricks", "end_stone_bricks", "terracotta", "cyan_terracotta", "smooth_stone", "polished_andesite", "polished_diorite", "polished_granite", "blackstone", "calcite", "tuff", "mud_bricks", "purpur_block", "sandstone"]
SPACE_CATS = {"space", "off_world", "prospecting"}
PROCESS_TYPES = {"processing": "create:milling", "logistics": "create:pressing", "fluids": "create:mixing", "brass": "create:pressing", "steel": "tfmg:*", "petrochem": "tfmg:*", "chemistry": "chemica:*",
                 "sulfuric": "sulfuricresonance:*", "pharma": "createbigpharma:*", "engines": "tfmg:*", "electronics": "tfmg:*", "nuclear": "createnuclear:*", "ammo": "create:pressing",
                 "attachments": "create:pressing", "munitions": "create:mixing", "special": "create:pressing", "alchemy": "create:mixing"}

FOOD = {
    "food_kitchen": (3, ["technology:workbench"], None, "farmersdelight:cooking_pot"),
    "food_farming": (4, ["food_kitchen"], [
        *[{"type": "block", "id": f"farmersdelight:{b}"} for b in ("rich_soil", "rich_soil_farmland", "organic_compost", "tomatoes", "budding_tomatoes", "cabbages", "onions", "rice")],
        *[{"type": "recipe", "id": g} for g in ("farmersdelight:*_crate", "farmersdelight:rice_bag", "farmersdelight:rice_bale", "farmersdelight:straw_bale")]], "farmersdelight:rich_soil"),
    "food_cooking_pot": (6, ["food_farming"], [{"type": "recipe", "id": f"farmersdelight:cooking/{n}"} for n in (
        "apple_cider", "baked_cod_stew", "beef_stew", "beetroot_soup", "bone_broth", "chicken_soup", "cooked_rice", "fish_stew", "hot_cocoa", "mushroom_stew", "onion_soup", "pumpkin_soup",
        "rabbit_stew", "tomato_sauce", "vegetable_soup", "dog_food")] + [{"type": "recipe", "id": "farmersdelight:cutting/*"}], "farmersdelight:tomato_sauce"),
    "food_cooking_pot_ii": (8, ["food_cooking_pot"], [{"type": "recipe", "id": f"farmersdelight:cooking/{n}"} for n in (
        "cabbage_rolls", "dumplings", "fried_rice", "glow_berry_custard", "mushroom_rice", "noodle_soup", "pasta_with_meatballs", "pasta_with_mutton_chop", "ratatouille", "squid_ink_pasta",
        "stuffed_pumpkin_block", "vegetable_noodles")] + [{"type": "recipe", "id": g} for g in ("farmersdelight:*sandwich", "farmersdelight:bacon_*", "farmersdelight:egg_*", "farmersdelight:*_roll", "farmersdelight:fried_egg*",
        "farmersdelight:cooked_bacon*", "farmersdelight:honey_glazed_ham_block", "farmersdelight:shepherds_pie_block", "farmersdelight:rice_roll_medley_block")], "farmersdelight:fried_rice"),
    "food_baking": (11, ["food_cooking_pot_ii"], [{"type": "recipe", "id": g} for g in (
        "farmersdelight:*pie*", "farmersdelight:cake*", "farmersdelight:*cookie", "farmersdelight:*cheesecake*", "farmersdelight:wheat_dough*", "farmersdelight:bread_from_*",
        "expandeddelight:*cookie", "expandeddelight:snickerdoodle", "expandeddelight:*cobbler*", "expandeddelight:*tart*", "expandeddelight:*sweet_roll")], "farmersdelight:apple_pie"),
    "food_fruit": (14, ["food_farming"], [{"type": "recipe", "id": "fruitsdelight:*"}], "fruitsdelight:blueberry"),
    "food_ocean": (16, ["food_cooking_pot_ii"], [{"type": "recipe", "id": g} for g in ("oceansdelight:*", "aquaculturedelight:*", "crabbersdelight:*")], "oceansdelight:guardian"),
    "food_barbecue": (18, ["food_cooking_pot_ii"], [{"type": "recipe", "id": "barbequesdelight:*"}], "barbequesdelight:grill"),
    "food_create": (20, ["food_baking", "technology:mechanical_press"], [{"type": "recipe", "id": "createfood:*"}], "create:sweet_roll"),
    "food_expanded": (24, ["food_baking"], [{"type": "recipe", "id": g} for g in ("expandeddelight:*", "moredelight:*")], "expandeddelight:juicer"),
    "food_extended": (28, ["food_expanded"], [{"type": "recipe", "id": "farmersdelight_extended:*"}], "farmersdelight_extended:cheeseburger"),
    "food_orchard": (36, ["food_fruit"], [{"type": "mod", "id": "dtfruitsdelight"}], "fruitsdelight:apple_jam"),
    "food_preserves": (45, ["food_expanded"], [{"type": "recipe", "id": "cratedelight:*"}], "cratedelight:apple_crate"),
    "food_gourmet": (60, ["food_extended", "food_ocean"], [{"type": "recipes", "recipeType": "farmersdelight:cooking"}], "farmersdelight:ratatouille"),
}
FOOD_TASKS = {
    "food_farming": [{"type": "smelt", "output": "minecraft:baked_potato", "count": 8}], "food_cooking_pot": [{"type": "smelt", "output": "minecraft:cooked_beef", "count": 8}],
    "food_cooking_pot_ii": [{"type": "craft", "output": "farmersdelight:bacon_sandwich", "count": 8}], "food_baking": [{"type": "smelt", "output": "minecraft:bread", "count": 8}],
    "food_fruit": [{"type": "craft", "output": "fruitsdelight:blueberry_jam", "count": 8}], "food_ocean": [{"type": "smelt", "output": "minecraft:cooked_cod", "count": 16}],
    "food_barbecue": [{"type": "craft", "output": "barbequesdelight:grill", "count": 4}], "food_create": [{"type": "craft", "output": "createfood:sweet_roll", "count": 8}],
    "food_expanded": [{"type": "smelt", "output": "expandeddelight:baked_sweet_potato", "count": 16}], "food_extended": [{"type": "craft", "output": "farmersdelight_extended:brownie_batter", "count": 16}],
    "food_orchard": [{"type": "smelt", "output": "minecraft:cooked_salmon", "count": 16}], "food_preserves": [{"type": "craft", "output": "cratedelight:apple_crate", "count": 16}],
    "food_gourmet": [{"type": "smelt", "output": "minecraft:cooked_porkchop", "count": 16}, {"type": "trade", "count": 20}],
}


def cap(level):
    return next(c for lv, c in CAP if level <= lv)


def pick(pool, level):
    return next(items for lv, items in pool if level <= lv)


def round2(n):
    if n < 100:
        return max(1, int(round(n)))
    digits = len(str(int(n))) - 2
    return int(round(n / 10 ** digits)) * 10 ** digits


def read_tsv(path):
    lines = path.read_text(encoding="utf8").splitlines()
    head = lines[0].split("\t")
    return [dict(zip(head, (line.split("\t") + [""] * len(head))[:len(head)])) for line in lines[1:]]


def concrete(spec):
    return bool(spec) and not spec.startswith("#") and "*" not in spec


def jar_check():
    report = []
    names = {}
    for jar in MODS.glob("*.jar"):
        try:
            with zipfile.ZipFile(jar) as zf:
                for n in zf.namelist():
                    m = re.match(r"data/([^/]+)/recipes?/(.+)\.json$", n)
                    if m:
                        names.setdefault(m.group(1), set()).add(m.group(2))
                    m = re.match(r"data/([^/]+)/", n)
                    if m:
                        names.setdefault("#ns:" + m.group(1), set()).add("")
        except zipfile.BadZipFile:
            pass
    from fnmatch import fnmatchcase
    for node, (_, _, unlocks, _) in FOOD.items():
        for u in unlocks or []:
            if u["type"] == "recipe":
                ns, _, glob = u["id"].partition(":")
                hits = sum(1 for p in names.get(ns, ()) if fnmatchcase(p, glob))
                report.append((node, u["id"], hits))
            elif u["type"] == "mod":
                report.append((node, "mod:" + u["id"], 1 if "#ns:" + u["id"] in names else 0))
            elif u["type"] == "block":
                report.append((node, u["id"], 1))
    return report


class RecipeIndex:
    def __init__(self):
        self.files = {}
        self.blockstates = set()
        self.zips = {}
        for jar in sorted(MODS.glob("*.jar")):
            try:
                zf = zipfile.ZipFile(jar)
            except zipfile.BadZipFile:
                continue
            self.zips[jar.name] = zf
            for n in zf.namelist():
                m = re.match(r"data/([^/]+)/recipes?/(.+)\.json$", n)
                if m:
                    self.files.setdefault(f"{m.group(1)}:{m.group(2)}", (jar.name, n))
                m = re.match(r"assets/([^/]+)/blockstates/(.+)\.json$", n)
                if m:
                    self.blockstates.add(f"{m.group(1)}:{m.group(2)}")

    def result(self, rid):
        hit = self.files.get(rid)
        if not hit:
            return None
        try:
            j = json.loads(self.zips[hit[0]].read(hit[1]))
        except (ValueError, KeyError):
            return None
        r = j.get("result", j.get("results"))
        if isinstance(r, list):
            r = r[0] if r else None
        if isinstance(r, dict):
            r = r.get("id") or r.get("item")
        return (j.get("type", ""), r) if isinstance(r, str) and ":" in r else None


class Builder:
    def __init__(self, old_dir):
        self.old = tm.old_nodes(old_dir)
        self.oldcats = {}
        for path in sorted(old_dir.glob("*.json")):
            self.oldcats[path.stem] = json.loads(path.read_text(encoding="utf8"))["categories"]
        self.model = json.loads(sim.MODEL.read_text(encoding="utf8"))
        self.rows = tm.parse_groups(tm.GROUPS, self.old)
        self.owner, self.stone, problems = tm.expand(self.rows, self.old)
        self.by_id = {r["id"]: r for r in self.rows}
        self.tsv = {(r["tree"], r["id"]): r for r in read_tsv(NODES_TSV)}
        self.deposit_use = Counter()
        self.deposit_tree = defaultdict(Counter)
        self.icons = Counter()
        self.cat_icons = Counter()
        self.index = RecipeIndex()

    def unlocks(self, row):
        out = {}
        for oid in row["expanded"]:
            for u in self.old[oid]["unlocks"]:
                if oid == "stonecutter":
                    if u["type"] == "recipe":
                        if tm.stone_group(u["id"], self.stone) != row["id"]:
                            continue
                    elif row["id"] != "stonecutter":
                        continue
                t = u["type"]
                if t == "capacity":
                    key = ("capacity", u["key"])
                    out[key] = {**out[key], "add": out[key]["add"] + u["add"]} if key in out else dict(u)
                elif t in ADDITIVE:
                    key = (t,)
                    out[key] = {**out[key], "add": out[key]["add"] + u["add"]} if key in out else dict(u)
                elif t == "buff":
                    key = ("buff", u["effect"])
                    if key not in out or u.get("amplifier", 0) > out[key].get("amplifier", 0):
                        out[key] = dict(u)
                else:
                    out.setdefault((t, json.dumps(u, sort_keys=True)), dict(u))
        return list(out.values())

    def old_tasks(self, row, kind):
        return [t for oid in row["expanded"] for t in self.old[oid].get("tasks", []) if t["type"] == kind]

    def deposit_items(self, row):
        items = []
        for t in self.old_tasks(row, "deposit"):
            items += [t["item"]] if "item" in t else t.get("items", [])
        return list(dict.fromkeys(items))

    def take_deposit(self, tree, candidates, level, count):
        pool = [f"#c:{kind}/{m}" for kind in ("ingots", "plates", "nuggets", "rods", "storage_blocks") for m in METALS[:6 if level <= 30 else 12 if level <= 100 else 16]]
        for strict, cands in ((True, candidates), (True, sorted(pool, key=lambda i: (self.deposit_use[i], i))), (False, candidates)):
            for item in cands:
                if not strict or (self.deposit_use[item] < 12 and self.deposit_tree[tree][item] < 6):
                    self.deposit_use[item] += 1
                    self.deposit_tree[tree][item] += 1
                    return {"type": "deposit", "item": item, "count": count}
        return None

    def tasks(self, row, tsv, unlocks, index):
        level = int(tsv["level"])
        tree = row["tree"]
        cat = row["category"]
        plan = tsv["tasks"].split()
        outs = [u["id"] for u in unlocks if u["type"] == "output" and concrete(u["id"])]
        blocks = [u["id"] for u in unlocks if u["type"] == "block" and concrete(u["id"])]
        found = [self.index.result(u["id"]) for u in unlocks if u["type"] == "recipe" and "*" not in u["id"]]
        found = [f for f in found if f]
        from_craft = [r for t, r in found if t.startswith("minecraft:crafting")]
        from_smelt = [r for t, r in found if t in ("minecraft:smelting", "minecraft:blasting", "minecraft:smoking")]
        from_machine = [r for t, r in found if not t.startswith("minecraft:")]
        from_block = [r for t, r in found if r in self.index.blockstates]
        smelts = [u["output"] for u in unlocks if u["type"] == "recipes" and u.get("recipeType") in ("minecraft:smelting", "minecraft:blasting") and u.get("output")]
        smelt_items = smelts + from_smelt + [o for o in outs if SMELTABLE.search(o.split(":")[1])]
        process_items = from_machine + [u["output"] for u in unlocks if u["type"] == "recipes" and "create:" in (u.get("recipeType") or "") and u.get("output") and "slag" not in u["output"]] + [o for o in outs if PRODUCT.search(o.split(":")[1])]
        tag_outs = [u["id"] for u in unlocks if u["type"] == "output" and u["id"].startswith("#")]
        recipe_outs = [u["output"] for u in unlocks if u["type"] == "recipes" and u.get("output") and "*" not in u["output"]]
        craftable = list(dict.fromkeys(outs + blocks + from_craft + tag_outs + recipe_outs + from_machine))
        deposits = list(dict.fromkeys(self.deposit_items(row) + craftable))
        c = cap(level)
        result = []
        fell = self.old_tasks(row, "fell")
        if fell:
            result.append({"type": "fell", "count": fell[0]["count"]})
        for entry in plan:
            kind, _, limit = entry.partition("<=")
            count = min(int(limit), c) if limit else c
            task = None
            if kind == "deposit":
                task = self.take_deposit(tree, deposits or outs or ["minecraft:iron_ingot"], level, count)
            elif kind == "craft" and craftable and not fell:
                task = {"type": "craft", "output": craftable[0], "count": count}
            elif kind == "smelt" and (smelt_items or craftable):
                task = {"type": "smelt", "output": (smelt_items or craftable)[0], "count": count}
            elif kind == "process":
                if process_items:
                    task = {"type": "process", "output": process_items[0], "count": count}
                elif cat in PROCESS_TYPES:
                    task = {"type": "process", "recipeType": PROCESS_TYPES[cat], "count": count}
            elif kind == "place" and (blocks or from_block or outs):
                task = {"type": "place", "block": (blocks or from_block or outs)[0], "count": max(1, min(c // 2, 16))}
            elif kind == "mine":
                old_mine = self.old_tasks(row, "mine")
                if old_mine:
                    task = {**old_mine[0], "count": min(old_mine[0]["count"], c * 4)}
                else:
                    pool = pick(MINE_POOL, level)
                    task = {"type": "mine", "block": pool[index % len(pool)], "count": c * 2}
            elif kind == "kill":
                pool = pick(KILL_POOL, level)
                task = {"type": "kill", "entity": pool[index % len(pool)], "count": pick(KILL_COUNT, level)}
            elif kind == "visit":
                old_visit = self.old_tasks(row, "visit")
                if old_visit:
                    task = {"type": "visit", "dimension": old_visit[0]["dimension"]}
                elif cat in SPACE_CATS and level >= 75:
                    task = {"type": "visit", "dimension": next(d for lv, d in PLANETS if level <= lv)}
                elif level >= 100:
                    task = {"type": "visit", "dimension": "minecraft:the_end"}
                elif level >= 25:
                    task = {"type": "visit", "dimension": "minecraft:the_nether"}
            elif kind == "structure":
                pool = pick(STRUCTURES, level)
                task = {"type": "structure", "structure": pool[index % len(pool)], "count": 1}
            elif kind in ("taxes", "trade_value"):
                spurs = round2(2 * sim.interp(self.model["inflow_per_day"], level))
                task = {"type": kind, "spurs": spurs}
            elif kind == "trade":
                task = {"type": "trade", "count": 3 if level <= 30 else 6 if level <= 75 else 12}
            elif kind == "break_item":
                task = {"type": "break_item", "count": 5 if level <= 30 else 10}
            elif kind == "enchant":
                task = {"type": "enchant", "count": 1 if level <= 30 else 3}
            if task is None and kind in ("craft", "smelt", "process", "place") and (kind != "craft" or not fell):
                if kind == "smelt":
                    task = {"type": "smelt", "count": count}
                else:
                    task = {"type": "process", "recipeType": PROCESS_TYPES.get(cat, "create:*"), "count": count}
            if task is None:
                task = self.take_deposit(tree, deposits or outs or ["minecraft:iron_ingot"], level, c)
            if task and not (task["type"] == "deposit" and any(t["type"] == "deposit" for t in result)):
                result.append(task)
        result += self.old_tasks(row, "event")
        return result

    def icon(self, row, unlocks, cat_icon):
        cat = row["category"]
        cands = [self.old[o]["icon"] for o in row["expanded"] if self.old[o].get("icon")]
        cands += [u["id"] for u in unlocks if u["type"] == "output" and concrete(u["id"])]
        cands.append(cat_icon)
        pool = [f"minecraft:{m}" for m in ICON_POOL]
        for options in (cands, pool):
            for icon in options:
                if self.icons[icon] == 0 and self.cat_icons[(cat, icon)] < 3:
                    self.icons[icon] += 1
                    self.cat_icons[(cat, icon)] += 1
                    return icon
        for icon in cands:
            if self.cat_icons[(cat, icon)] < 3:
                self.icons[icon] += 1
                self.cat_icons[(cat, icon)] += 1
                return icon
        self.icons[cands[0]] += 1
        return cands[0]

    def build(self):
        nodes = {}
        for r in sorted(self.tsv.values(), key=lambda r: (int(r["level"]), r["tree"], r["id"])):
            nodes[(r["tree"], r["id"])] = {"tsv": dict(r), "row": self.by_id[r["id"]] if r["old_ids"] != "NEW" else {"tree": r["tree"], "id": r["id"], "category": r["category"], "expanded": []}}
        for (tree, nid), spec in FOOD.items() if False else []:
            pass
        for nid, (level, requires, unlocks, icon) in FOOD.items():
            node = nodes[("technology", nid)]
            t = node["tsv"]
            t["level"] = str(level)
            t["requires"] = " ".join(q if ":" in q else f"technology:{q}" for q in requires)
            kind = t["kind"]
            t["cost"] = str(sim.price(self.model, level, "milestone" if kind == "m" else "detail"))
            hours = sim.interp(self.model["time_hours"], level) * (1.0 if kind == "m" else 0.5)
            t["time"] = f"{int(hours)}h{round(hours % 1 * 60)}m" if hours >= 1 else f"{max(1, round(hours * 60))}m"
            node["food_icon"] = icon
            node["food_unlocks"] = unlocks
        parents = {k: [q.split(":", 1) and tuple(q.split(":", 1)) for q in n["tsv"]["requires"].split()] for k, n in nodes.items()}
        keys = list(nodes)
        order, cyclic = tm.topo({f"{a}:{b}": [f"{p}:{q}" for p, q in ps] for (a, b), ps in parents.items()})
        if cyclic:
            raise SystemExit(f"cycle after food override: {cyclic[:5]}")
        qparents = {f"{a}:{b}": [f"{p}:{q}" for p, q in ps] for (a, b), ps in parents.items()}
        reduced = tm.reduce_edges(qparents, order)
        level = {f"{a}:{b}": int(n["tsv"]["level"]) for (a, b), n in nodes.items()}
        for q in order:
            for p in reduced[q]:
                level[q] = max(level[q], level[p])
        for (a, b), n in nodes.items():
            n["tsv"]["level"] = str(level[f"{a}:{b}"])
            n["requires"] = reduced[f"{a}:{b}"]
        catdef = {t: {c["id"]: c for c in cats} for t, cats in self.oldcats.items()}
        out = {t: {"title": None, "nodes": [], "cats": []} for t in TREE_ORDER}
        index = Counter()
        built = {}
        for (tree, nid), n in sorted(nodes.items(), key=lambda kv: (int(kv[1]["tsv"]["level"]), kv[0])):
            row, t = n["row"], n["tsv"]
            row = {**row, "tree": tree, "id": nid, "category": t["category"]}
            unlocks = n.get("food_unlocks") if "food_unlocks" in n and n["food_unlocks"] is not None else self.unlocks(row)
            if nid == "food_kitchen":
                unlocks = self.unlocks(row)
            cat_icon = catdef.get(tree, {}).get(t["category"], {}).get("icon", "minecraft:stone")
            if t["category"] == "food":
                cat_icon = "farmersdelight:cooking_pot"
            icon = n.get("food_icon") if n.get("food_icon") else self.icon(row, unlocks, cat_icon)
            if n.get("food_icon"):
                self.icons[icon] += 1
            idx = index[(tree, t["category"])]
            index[(tree, t["category"])] += 1
            tasks = FOOD_TASKS[nid] if nid in FOOD_TASKS else self.tasks(row, t, unlocks, idx)
            if nid in FOOD_TASKS:
                tasks = [x for x in tasks]
            requires = []
            for q in n["requires"]:
                head, _, tail = q.partition(":")
                requires.append({"type": "node", "id": tail} if head == tree else {"type": "node", "id": q, "hidden": True} if tree == "buffs" else {"type": "node", "id": q})
            node = {"id": nid, "category": t["category"], "icon": icon, "era": t["era"], "kind": "milestone" if t["kind"] == "m" else "detail", "level": int(t["level"]), "cost": int(t["cost"]), "time": t["time"], "requires": requires, "tasks": tasks, "unlocks": unlocks}
            built[(tree, nid)] = node
            out[tree]["nodes"].append(node)
        seen = set()
        for tree in TREE_ORDER:
            for node in sorted(out[tree]["nodes"], key=lambda n: n["level"]):
                if node["kind"] == "milestone" and node["era"] in ("steel_chemistry", "advanced", "late", "endgame") and node["level"] > 50 and node["era"] not in seen and tree != "buffs":
                    seen.add(node["era"])
                    node["teaser"] = True
        flat = {f"{tree}:{n['id']}": {**n, "tree": tree} for tree in TREE_ORDER for n in out[tree]["nodes"]}
        graph, _ = tree_metrics.build_graph(flat)
        tree_lock.repair(flat, graph, lambda lv: pick(MINE_POOL, lv), PROCESS_TYPES)
        for key, node in flat.items():
            target = next(n for n in out[node["tree"]]["nodes"] if n["id"] == node["id"])
            target["tasks"] = node["tasks"]
        left = tree_lock.violations(flat, graph)
        if left:
            raise SystemExit(f"self-locked tasks remain: {left[:5]}")
        return out, catdef, nodes

    def write(self, out, catdef):
        used = defaultdict(set)
        for tree, d in out.items():
            for n in d["nodes"]:
                used[tree].add(n["category"])
        for tree in TREE_ORDER:
            old_title = json.loads((TREES / f"{tree}.json").read_text(encoding="utf8"))
            cats = [c for c in self.oldcats[tree] if c["id"] in used[tree]]
            if tree == "technology":
                cats.append({"id": "food", "icon": "farmersdelight:cooking_pot", "order": 5})
            cats = [{**c, "order": i} for i, c in enumerate(cats)]
            missing = used[tree] - {c["id"] for c in cats}
            if missing:
                print("categories missing", tree, missing)
            doc = {"version": TREE_VERSION, "title": TREE_TITLE.get(tree, old_title["title"]), "scope": old_title["scope"]}
            if "strict" in old_title:
                doc["strict"] = old_title["strict"]
            doc["categories"] = cats
            doc["nodes"] = out[tree]["nodes"]
            (TREES / f"{tree}.json").write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf8")


def roman(n):
    return ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"][n - 1]


def write_lang(builder, out):
    numbered = re.compile(r"(.+)_(\d+)$")
    prefix = "kami_claims.research.node"
    for code in ("en_us", "de_de"):
        path = LANG / f"{code}.json"
        lang = json.loads(path.read_text(encoding="utf8"))

        def title_of(tree, oid):
            for key in (f"{prefix}.{tree}.{oid}",):
                if key in lang:
                    return lang[key]
            m = numbered.match(oid)
            if m and f"{prefix}.{tree}.{m.group(1)}" in lang:
                return f"{lang[f'{prefix}.{tree}.{m.group(1)}']} {m.group(2)}"
            return None

        def desc_of(tree, oid):
            return lang.get(f"{prefix}.{tree}.{oid}.desc")

        new = {}
        for tree in TREE_ORDER:
            for node in out[tree]["nodes"]:
                nid = node["id"]
                row = builder.by_id.get(nid)
                olds = row["expanded"] if row else []
                fresh = not olds
                otree = builder.old[olds[0]]["tree"] if olds else tree
                titles = [title_of(builder.old[o]["tree"], o) for o in olds]
                titles = [t for t in titles if t]
                tkey = f"{prefix}.{tree}.{nid}"
                m = numbered.match(nid)
                own = f"{prefix}.{otree}.{olds[0]}" in lang if olds else False
                if tree == "buffs":
                    if m and f"{prefix}.buffs.{m.group(1)}" in lang and not re.search(r"_(health|regeneration|speed|haste|resistance|jump_boost|strength|absorption|luck)", nid) and m.group(1) not in ("instant_health", "regeneration", "speed", "haste", "resistance", "jump_boost", "strength", "absorption", "health_boost", "luck"):
                        continue
                    if m and m.group(1) in ("instant_health", "regeneration", "speed", "haste", "resistance", "jump_boost", "strength", "absorption", "health_boost", "luck"):
                        amp = max((u.get("amplifier", 0) for u in node["unlocks"] if u["type"] == "buff"), default=0) + 1
                        base = re.sub(r"\s+[IVX]+$", "", titles[0] if titles else m.group(1).replace("_", " ").title())
                        new[tkey] = f"{base} {roman(amp)}"
                        continue
                    if titles:
                        new[tkey] = titles[0]
                    d = desc_of(builder.old[olds[0]]["tree"], olds[0]) if olds else None
                    if d:
                        new[tkey + ".desc"] = d
                    continue
                if fresh:
                    continue
                if len(olds) == 1 or len(titles) == 1:
                    title = titles[0]
                elif len(titles) == 2 and len(" & ".join(titles)) <= 38:
                    title = " & ".join(titles)
                else:
                    title = titles[0]
                new[tkey] = title
                d = desc_of(builder.old[olds[0]]["tree"], olds[0]) or f"{titles[0]}."
                rest = titles[1:7]
                if len(olds) > 1 and rest and not (len(titles) == 2 and " & " in title):
                    d = d.rstrip() + (" Also covers: " if code == "en_us" else " Umfasst auch: ") + ", ".join(rest) + "."
                new[tkey + ".desc"] = d
        fresh_text = FRESH_LANG[code]
        for key, value in fresh_text.items():
            new[key] = value
        kept = {}
        first = None
        for k, v in lang.items():
            if k.startswith(prefix + "."):
                if first is None:
                    first = len(kept)
                tree = k[len(prefix) + 1:].split(".")[0]
                bare = re.sub(r"\.desc$", "", k)
                nid = bare[len(prefix) + len(tree) + 2:]
                is_base = any(numbered.match(n["id"]) and numbered.match(n["id"]).group(1) == nid and tree == t for t in TREE_ORDER for n in out[t]["nodes"] if t == tree)
                if is_base and tree != "buffs" or (is_base and tree == "buffs" and nid in ("treasury", "land", "citizens", "provinces", "buff_points")):
                    kept[k] = v
                continue
            kept[k] = v
        items = list(kept.items())
        merged = dict(items[:first]) | new | dict(items[first:]) if first is not None else {**kept, **new}
        merged = {k: v for k, v in merged.items() if not re.match(r"kami_claims\.research\.(reload\.(migrated|dry_run)|mail\.(migrated|progress_converted)|migrations\.)", k)}
        merged["kami_claims.research.mail.reset"] = RESET_MAIL[code]
        merged.update(CATEGORY_LANG[code])
        merged["kami_claims.research.tree.tools_armor"] = TREE_LANG[code]
        path.write_text(json.dumps(merged, indent=2, ensure_ascii=False) + "\n", encoding="utf8")
        print(code, "node keys", sum(1 for k in merged if k.startswith(prefix)))


FOOD_TITLES = {
    "food_farming": ("Farming Basics", "Farmland Grundlagen"), "food_cooking_pot": ("Cooking Pot Meals", "Kochtopf Gerichte"), "food_cooking_pot_ii": ("Advanced Meals", "Fortgeschrittene Gerichte"),
    "food_baking": ("Baking", "Backen"), "food_fruit": ("Fruit Kitchen", "Fruchtkueche"), "food_ocean": ("Ocean Cuisine", "Meeresk\u00fcche"), "food_barbecue": ("Barbecue", "Grillen"),
    "food_create": ("Mechanical Kitchen", "Mechanische K\u00fcche"), "food_expanded": ("Expanded Menu", "Erweiterte Speisekarte"), "food_extended": ("Extended Recipes", "Erweiterte Rezepte"),
    "food_orchard": ("Orchard Trees", "Obstb\u00e4ume"), "food_preserves": ("Preserves and Crates", "Vorr\u00e4te und Kisten"), "food_gourmet": ("Gourmet Meals", "Feinschmecker Gerichte"),
}
FOOD_DESC = {
    "food_farming": ("Rich soil, crops, crates and bales from Farmer's Delight.", "Fruchtbarer Boden, Feldfr\u00fcchte, Kisten und Ballen aus Farmer's Delight."),
    "food_cooking_pot": ("Simple cooking pot meals and cutting board recipes.", "Einfache Kochtopf-Gerichte und Schneidebrett-Rezepte."),
    "food_cooking_pot_ii": ("Pasta, rice dishes, sandwiches and large feast blocks.", "Nudeln, Reisgerichte, Sandwiches und gro\u00dfe Festbl\u00f6cke."),
    "food_baking": ("Pies, cakes, cookies and bakery goods.", "Kuchen, Torten, Kekse und Backwaren."),
    "food_fruit": ("All recipes of Fruits Delight.", "Alle Rezepte von Fruits Delight."),
    "food_ocean": ("Seafood from Ocean's Delight, Aquaculture Delight and Crabber's Delight.", "Meeresfr\u00fcchte aus Ocean's Delight, Aquaculture Delight und Crabber's Delight."),
    "food_barbecue": ("Grilling and skewering with Barbecue's Delight.", "Grillen und Aufspie\u00dfen mit Barbecue's Delight."),
    "food_create": ("Automated food production recipes from Create Food.", "Automatisierte Lebensmittelproduktion aus Create Food."),
    "food_expanded": ("Expanded Delight and More Delight recipes.", "Rezepte aus Expanded Delight und More Delight."),
    "food_extended": ("Farmer's Delight Extended recipes.", "Rezepte aus Farmer's Delight Extended."),
    "food_orchard": ("Fruit trees from Dynamic Trees Fruits Delight.", "Obstb\u00e4ume aus Dynamic Trees Fruits Delight."),
    "food_preserves": ("Storage crates for food from Crate Delight.", "Lagerkisten f\u00fcr Lebensmittel aus Crate Delight."),
    "food_gourmet": ("Gourmet meals from every Delight mod cooking pot.", "Feinschmecker-Gerichte aus dem Kochtopf aller Delight-Mods."),
}
RESET_MAIL = {"en_us": "The research tree was rebuilt and your research progress was reset. Level, XP, treasury, loans and tokens were kept, and spent entries were refunded.",
              "de_de": "Der Forschungsbaum wurde neu aufgebaut und dein Forschungsfortschritt zurückgesetzt. Level, XP, Staatskasse, Kredite und Token blieben erhalten, bezahlte Einträge wurden erstattet."}
FRESH_LANG = {"en_us": {}, "de_de": {}}
for i, code in enumerate(("en_us", "de_de")):
    for nid, titles in FOOD_TITLES.items():
        FRESH_LANG[code][f"kami_claims.research.node.technology.{nid}"] = titles[i]
        FRESH_LANG[code][f"kami_claims.research.node.technology.{nid}.desc"] = FOOD_DESC[nid][i]
CATEGORY_LANG = {"en_us": {"kami_claims.research.category.food": "Food"}, "de_de": {"kami_claims.research.category.food": "Essen"}}
TREE_LANG = {"en_us": "Gear", "de_de": "Ausrüstung"}


def write_map(builder):
    mapping = {}
    for line in (ROOT / "docs/tree-mapping.tsv").read_text(encoding="utf8").splitlines()[1:]:
        ot, oid, _, action, nt, nid = line.split("\t")
        key = f"{ot}:{oid}"
        mapping.setdefault(key, []).append(f"{nt}:{nid}")
    lines = [f'  {json.dumps(k)}: {json.dumps(v[0] if len(v) == 1 else v)}' for k, v in mapping.items()]
    ID_MAP.write_text("{\n" + ",\n".join(lines) + "\n}\n", encoding="utf8")


def snapshot_old(builder):
    data = defaultdict(set)
    caps = Counter()
    for node in builder.old.values():
        for u in node["unlocks"]:
            t = u["type"]
            if t == "capacity":
                caps[u["key"]] += u["add"]
            elif t in ADDITIVE:
                caps[t] += u["add"]
            elif t == "buff":
                data["buff"].add(u["effect"])
            elif t == "recipes":
                data["recipes"].add(json.dumps(u, sort_keys=True))
            elif t in ("recipe", "output", "block", "mod", "loan", "feature"):
                data[t].add(u["id"])
    doc = {k: sorted(v) for k, v in data.items()}
    doc["capacity_sums"] = dict(caps)
    doc["nodes"] = len(builder.old)
    doc["recipe_duplicates"] = sum(1 for n in builder.old.values() for u in n["unlocks"] if u["type"] == "recipe") - len(data["recipe"])
    OLD_UNLOCKS.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--old", type=Path, required=True)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--no-lang", action="store_true")
    args = parser.parse_args()
    b = Builder(args.old)
    out, catdef, nodes = b.build()
    print("nodes", {t: len(d["nodes"]) for t, d in out.items()})
    for node, glob, hits in jar_check():
        if hits == 0:
            print("FOOD FILTER MATCHES NOTHING", node, glob)
    if args.write:
        snapshot_old(b)
        b.write(out, catdef)
        if not args.no_lang:
            write_lang(b, out)
        write_map(b)
    return 0


if __name__ == "__main__":
    sys.exit(main())
