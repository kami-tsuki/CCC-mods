import argparse
import json
import math
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TREES = ROOT / "KamiClaims/src/main/resources/assets/kami_claims/research/trees"
MODEL = Path(__file__).resolve().with_name("balance_model.json")
TIME_UNITS = {"d": 86400, "h": 3600, "m": 60, "s": 1}


def parse_hours(text):
    return sum(int(n) * TIME_UNITS[u] for n, u in re.findall(r"(\d+)([dhms])", str(text))) / 3600


def fmt_hours(hours):
    d, m = divmod(round(hours * 60), 1440)
    return f"{d}d{m // 60}h"


def interp(table, x):
    if x <= table[0][0]:
        return table[0][1]
    for (x0, y0), (x1, y1) in zip(table, table[1:]):
        if x <= x1:
            f = (x - x0) / (x1 - x0)
            return y0 * (y1 / y0) ** f if y0 > 0 and y1 > 0 else y0 + (y1 - y0) * f
    return table[-1][1]


def level_at(model, day):
    return int(interp([[d, lv] for d, lv in model["level_by_day"]], day))


def nice(value):
    if value < 10:
        return max(0, round(value))
    step = 10 ** (math.floor(math.log10(value)) - 1)
    return int(round(value / step) * step)


def price(model, level, kind):
    days = interp(model["cost_days"], level) * model["kind_weight"].get(kind, 1.0)
    return nice(days * interp(model["inflow_per_day"], level))


def era_of(model, level):
    for era in model["eras"]:
        if level <= era["to"]:
            return era["id"]
    return model["eras"][-1]["id"]


def flat_refs(requires):
    for req in requires:
        if req.get("type") == "node":
            yield req["id"]
        elif "of" in req:
            yield from flat_refs(req["of"])


def requirement_met(req, done, resolve):
    if req.get("type") == "node":
        return resolve(req["id"]) in done
    if "of" in req:
        results = [requirement_met(r, done, resolve) for r in req["of"]]
        return any(results) if req.get("type") == "any" else all(results)
    return True


def load(folder, model, reprice):
    nodes = {}
    for path in sorted(folder.glob("*.json")):
        tree = json.loads(path.read_text(encoding="utf8"))
        for node in tree.get("nodes", []):
            kind = node.get("kind") or ("buff" if path.stem == "buffs" else "milestone")
            level = node.get("level", 1)
            cost = price(model, level, kind) if reprice else node.get("cost", 0)
            nodes[f"{path.stem}:{node['id']}"] = {**node, "tree": path.stem, "kind": kind, "level": level, "cost": cost, "hours": parse_hours(node.get("time", "0m"))}
    return nodes


def load_tsv(path):
    nodes = {}
    for line in path.read_text(encoding="utf8").splitlines()[1:]:
        tree, nid, category, kind, level, era, depth, cost, time, requires, tasks, olds = line.split("	")
        nodes[f"{tree}:{nid}"] = {
            "id": nid, "tree": tree, "kind": kind, "level": int(level), "cost": int(cost), "hours": parse_hours(time),
            "requires": [{"type": "node", "id": r} for r in requires.split()],
        }
    return nodes


def resolver(nodes):
    trees = {n["tree"] for n in nodes.values()}

    def make(tree):
        def resolve(ref):
            head, _, tail = ref.partition(":")
            return ref if tail and head in trees else f"{tree}:{ref}"
        return resolve
    return make


def simulate(nodes, model, horizon_days):
    make = resolver(nodes)
    resolvers = {k: make(n["tree"]) for k, n in nodes.items()}
    children = defaultdict(list)
    for key, node in nodes.items():
        for ref in dict.fromkeys(flat_refs(node.get("requires", []))):
            children[resolvers[key](ref)].append(key)

    def open_for(key, finished):
        return all(requirement_met(r, finished, resolvers[key]) for r in nodes[key].get("requires", []))

    done = {}
    finished = set()
    pending = set(nodes)
    ready = {k for k in pending if open_for(k, finished)}
    cash = 0.0
    busy_until = 0
    running = None
    hour = 0
    share = model["research_share"]
    while hour < horizon_days * 24 and (pending or running):
        day = hour / 24
        level = level_at(model, day)
        cash += interp(model["inflow_per_day"], level) * share / 24
        if running and hour >= busy_until:
            key, running = running, None
            finished.add(key)
            done[key] = day
            pending.discard(key)
            for child in children[key]:
                if child in pending and child not in ready and open_for(child, finished):
                    ready.add(child)
        if not running:
            options = sorted((k for k in ready if nodes[k]["level"] <= level), key=lambda k: (nodes[k]["level"], nodes[k]["cost"]))
            pick = next((k for k in options if nodes[k]["cost"] <= cash), None)
            if pick:
                cash -= nodes[pick]["cost"]
                ready.discard(pick)
                running = pick
                busy_until = hour + max(1, math.ceil(nodes[pick]["hours"]))
        hour += 1
    return done


def critical(nodes, weight):
    make = resolver(nodes)
    memo = {}

    def best(key):
        if key in memo:
            return memo[key]
        memo[key] = (0.0, [])
        refs = [make(nodes[key]["tree"])(r) for r in dict.fromkeys(flat_refs(nodes[key].get("requires", [])))]
        top = max((best(r) for r in refs if r in nodes), default=(0.0, []), key=lambda v: v[0])
        memo[key] = (top[0] + weight(nodes[key]), top[1] + [key])
        return memo[key]

    return max((best(k) for k in nodes), key=lambda v: v[0], default=(0.0, []))


def month_inflow(model):
    return sum(interp(model["inflow_per_day"], level_at(model, d)) for d in range(model["alpha_days"])) * model["research_share"]


def report_tree(args):
    model = json.loads(args.model.read_text(encoding="utf8"))
    nodes = load_tsv(args.tsv) if args.tsv else load(args.trees, model, args.reprice)
    done = simulate(nodes, model, args.horizon)
    inflow = lambda n: interp(model["inflow_per_day"], n["level"])
    lines = [f"nodes {len(nodes)}  completed {len(done)}  research share {model['research_share']}"]
    lines.append("era".ljust(18) + "nodes".rjust(6) + "cost".rjust(12) + "cost_days".rjust(11) + "first_done".rjust(11) + "last_done".rjust(11) + "done@alpha".rjust(11))
    for era in model["eras"]:
        keys = [k for k, n in nodes.items() if era_of(model, n["level"]) == era["id"]]
        if not keys:
            continue
        times = [done[k] for k in keys if k in done]
        alpha = sum(1 for t in times if t <= model["alpha_days"])
        lines.append(
            era["id"].ljust(18) + str(len(keys)).rjust(6) + str(sum(nodes[k]["cost"] for k in keys)).rjust(12)
            + f"{sum(nodes[k]['cost'] / inflow(nodes[k]) for k in keys):.1f}".rjust(11)
            + (f"{min(times):.1f}" if times else "-").rjust(11)
            + (f"{max(times):.1f}" if len(times) == len(keys) else "never").rjust(11) + f"{alpha}/{len(keys)}".rjust(11)
        )
    visible = [k for k, n in nodes.items() if n["level"] <= model["alpha_level_window"]]
    cost = sum(nodes[k]["cost"] for k in visible)
    lines.append(f"visible at level<={model['alpha_level_window']}: {len(visible)} nodes, cost {cost}, month inflow {month_inflow(model):.0f}, ratio {cost / month_inflow(model):.2f}")
    lines.append(f"done within {model['alpha_days']}d: {sum(1 for t in done.values() if t <= model['alpha_days'])}, level at alpha end {level_at(model, model['alpha_days'])}")
    by_time = critical(nodes, lambda n: n["hours"])
    by_cost = critical(nodes, lambda n: n["cost"] / inflow(n))
    lines.append(f"critical path by time: {fmt_hours(by_time[0])} over {len(by_time[1])} nodes, ends {by_time[1][-1] if by_time[1] else '-'}")
    lines.append(f"critical path by cost: {by_cost[0]:.1f} days of inflow over {len(by_cost[1])} nodes, ends {by_cost[1][-1] if by_cost[1] else '-'}")
    for era in model["eras"]:
        sub = {k: n for k, n in nodes.items() if era_of(model, n["level"]) == era["id"]}
        if sub:
            lines.append(f"  {era['id']}: time path {fmt_hours(critical(sub, lambda n: n['hours'])[0])}, serial time {fmt_hours(sum(n['hours'] for n in sub.values()))}")
    print("\n".join(lines))
    return 0


def report_plan(args):
    model = json.loads(args.model.read_text(encoding="utf8"))
    rows = []
    alpha_total = 0
    for era in model["eras"]:
        span = era["to"] - era["from"] + 1
        cost = 0
        for i in range(era["nodes"]):
            level = era["from"] + int(i * span / era["nodes"])
            kind = "milestone" if (i % 10) < model["milestone_share"] * 10 else "detail"
            value = price(model, level, kind)
            cost += value
            if level <= model["alpha_level_window"]:
                alpha_total += value
        mid = (era["from"] + era["to"]) // 2
        rows.append((era["id"], era["nodes"], cost, cost / interp(model["inflow_per_day"], mid), price(model, mid, "milestone"), price(model, mid, "detail")))
    print("era".ljust(18) + "nodes".rjust(6) + "cost".rjust(12) + "days@mid".rjust(10) + "milestone".rjust(11) + "detail".rjust(9))
    for r in rows:
        print(r[0].ljust(18) + str(r[1]).rjust(6) + str(r[2]).rjust(12) + f"{r[3]:.1f}".rjust(10) + str(r[4]).rjust(11) + str(r[5]).rjust(9))
    print(f"alpha-visible cost {alpha_total}, month inflow {month_inflow(model):.0f}, ratio {alpha_total / month_inflow(model):.2f} (target 1.5-2.0)")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description="Research tree balance simulator.")
    parser.add_argument("--model", type=Path, default=MODEL)
    sub = parser.add_subparsers(dest="mode", required=True)
    tree = sub.add_parser("tree")
    tree.add_argument("--trees", type=Path, default=TREES)
    tree.add_argument("--tsv", type=Path)
    tree.add_argument("--reprice", action="store_true")
    tree.add_argument("--horizon", type=int, default=1500)
    tree.set_defaults(run=report_tree)
    plan = sub.add_parser("plan")
    plan.set_defaults(run=report_plan)
    args = parser.parse_args(argv)
    return args.run(args)


if __name__ == "__main__":
    sys.exit(main())
