import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODS = {
    "KamiLibs": "kami_libs",
    "KamiClaims": "kami_claims",
    "KamiEconomy": "kami_economy",
    "KamiEssentials": "kami_essentials",
    "KamiGeology": "kami_geology",
}
TREES = ROOT / "KamiClaims/src/main/resources/assets/kami_claims/research/trees"
NODE_PREFIX = "kami_claims.research.node"
DESC_EXEMPT_TREES = {"buffs"}
SOURCE_SUFFIXES = (".kt", ".java")
RESOURCE_SUFFIXES = (".json", ".js", ".txt")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?\d*(?:\.\d+)?[sdfxXcb]")
STRING = re.compile(r'"""(.*?)"""|"((?:\\.|[^"\\\n])*)"', re.S)
TEMPLATE = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*")
NUMBERED = re.compile(r"(.+)_(\d+)$")
KEY_SHAPE = re.compile(r"^[a-z0-9_]+(\.[a-z0-9_-]+)+$")
FILE_TAIL = re.compile(r"\.(json|wal|migrated|toml|db|txt|png|log|bak|tmp)$")
MIN_PREFIX = 6
WORST = 8


def placeholders(text):
    return len(PLACEHOLDER.findall(text.replace("%%", "")))


def load_lang(mod, ns, code):
    path = ROOT / mod / f"src/main/resources/assets/{ns}/lang/{code}.json"
    return json.loads(path.read_text(encoding="utf8")) if path.exists() else {}


def parity(en, de):
    mismatched = sorted(k for k in en.keys() & de.keys() if placeholders(str(en[k])) != placeholders(str(de[k])))
    return {"only_en": sorted(en.keys() - de.keys()), "only_de": sorted(de.keys() - en.keys()), "placeholder_mismatch": mismatched}


def tree_nodes():
    return {path.stem: [n["id"] for n in json.loads(path.read_text(encoding="utf8"))["nodes"]] for path in sorted(TREES.glob("*.json"))}


def research_coverage(en, de):
    nodes = tree_nodes()
    missing = defaultdict(list)
    for tree, ids in nodes.items():
        for node in ids:
            family = NUMBERED.match(node)
            bases = [f"{NODE_PREFIX}.{tree}.{node}"] + ([f"{NODE_PREFIX}.{tree}.{family.group(1)}"] if family else [])
            for code, lang in (("en", en), ("de", de)):
                if not any(b in lang for b in bases):
                    missing[f"{code}.title"].append(f"{tree}.{node}")
                if tree not in DESC_EXEMPT_TREES and not any(f"{b}.desc" in lang for b in bases):
                    missing[f"{code}.desc"].append(f"{tree}.{node}")
    valid = {f"{t}.{n}" for t, ids in nodes.items() for n in ids}
    valid |= {f"{t}.{NUMBERED.match(n).group(1)}" for t, ids in nodes.items() for n in ids if NUMBERED.match(n)}
    stale = sorted(
        k for k in en
        if k.startswith(f"{NODE_PREFIX}.") and k.split(".")[3] in nodes and re.sub(r"\.desc$", "", k[len(NODE_PREFIX) + 1:]) not in valid
    )
    return {"missing": {k: v for k, v in missing.items()}, "stale": stale}


def source_files(mod):
    base = ROOT / mod
    for scope in ("main", "test"):
        for path in sorted((base / "src" / scope).rglob("*")):
            if path.is_file() and (path.suffix in SOURCE_SUFFIXES or (scope == "main" and path.suffix in RESOURCE_SUFFIXES and "/lang/" not in path.as_posix())):
                yield path


def literals():
    found = defaultdict(set)
    for mod in MODS:
        for path in source_files(mod):
            text = path.read_text(encoding="utf8", errors="replace")
            for m in STRING.finditer(text):
                found[path].add(m.group(1) if m.group(1) is not None else m.group(2))
    return found


def key_suffixes(key):
    parts = key.split(".")
    return [".".join(parts[i:]) for i in range(len(parts))]


def build_matchers(found):
    static, templates = set(), defaultdict(list)
    for strings in found.values():
        for s in strings:
            if "$" not in s:
                static.add(s)
                continue
            prefix = s.split("$", 1)[0]
            if len(prefix) >= MIN_PREFIX:
                pattern = "".join(".+" if TEMPLATE.fullmatch(part) else re.escape(part) for part in re.split(f"({TEMPLATE.pattern})", s) if part)
                templates[prefix].append(re.compile(pattern))
    return static, templates


def is_referenced(key, static, templates):
    for suffix in key_suffixes(key):
        if suffix in static:
            return True
        for prefix, patterns in templates.items():
            if suffix.startswith(prefix) and any(p.fullmatch(suffix) for p in patterns):
                return True
    return False


def unused_keys(en, static, templates):
    return sorted(k for k in en if not is_referenced(k, static, templates))


def undefined_refs(found, all_keys):
    ns_prefixes = tuple(f"{ns}." for ns in MODS.values())
    prefixes = {k.rsplit(".", n)[0] + "." for k in all_keys for n in range(1, k.count(".") + 1)}
    report = defaultdict(list)
    for path, strings in found.items():
        for s in sorted(strings):
            if s.startswith(ns_prefixes) and KEY_SHAPE.match(s) and not FILE_TAIL.search(s) and s not in all_keys and s + "." not in prefixes:
                report[path.relative_to(ROOT).as_posix()].append(s)
    return report


def worst_groups(keys):
    return Counter(".".join(k.split(".")[:3]) for k in keys).most_common(WORST)


def analyse():
    result = {"mods": {}}
    en_all = {}
    for mod, ns in MODS.items():
        en, de = load_lang(mod, ns, "en_us"), load_lang(mod, ns, "de_de")
        en_all.update(en)
        result["mods"][mod] = {"keys_en": len(en), "keys_de": len(de), "parity": parity(en, de), "_en": en, "_de": de}
    found = literals()
    static, templates = build_matchers(found)
    coverage = research_coverage(result["mods"]["KamiClaims"]["_en"], result["mods"]["KamiClaims"]["_de"])
    for mod in MODS:
        unused = unused_keys(result["mods"][mod].pop("_en"), static, templates)
        result["mods"][mod].pop("_de")
        result["mods"][mod]["unused"] = {"count": len(unused), "worst_groups": worst_groups(unused), "keys": unused}
    undefined = undefined_refs(found, en_all)
    result["research"] = coverage
    result["undefined_refs"] = {"count": sum(len(v) for v in undefined.values()), "worst_files": sorted(((len(v), f) for f, v in undefined.items()), reverse=True)[:WORST], "by_file": {f: v for f, v in undefined.items()}}
    return result


def violations(result):
    count = 0
    for data in result["mods"].values():
        p = data["parity"]
        count += len(p["only_en"]) + len(p["only_de"]) + len(p["placeholder_mismatch"])
    count += sum(len(v) for v in result["research"]["missing"].values()) + len(result["research"]["stale"])
    return count + result["undefined_refs"]["count"]


def render(result, verbose):
    out = [f"{'mod':16}{'en':>6}{'de':>6}{'only_en':>9}{'only_de':>9}{'placeholder':>13}{'unused':>8}"]
    for mod, d in result["mods"].items():
        p = d["parity"]
        out.append(f"{mod:16}{d['keys_en']:>6}{d['keys_de']:>6}{len(p['only_en']):>9}{len(p['only_de']):>9}{len(p['placeholder_mismatch']):>13}{d['unused']['count']:>8}")
    for mod, d in result["mods"].items():
        p = d["parity"]
        for label in ("only_en", "only_de", "placeholder_mismatch"):
            for k in p[label][:10 if not verbose else None]:
                out.append(f"  {mod} {label}: {k}")
        if d["unused"]["count"]:
            out.append(f"  {mod} unused worst groups: " + ", ".join(f"{g}({n})" for g, n in d["unused"]["worst_groups"]))
            if verbose:
                out.extend(f"    unused {k}" for k in d["unused"]["keys"])
    r = result["research"]
    out.append(f"research missing: " + (", ".join(f"{k}={len(v)}" for k, v in r["missing"].items()) or "none") + f"; stale keys: {len(r['stale'])}")
    for label, items in [*r["missing"].items(), ("stale", r["stale"])]:
        out.extend(f"  {label}: {i}" for i in items[:10 if not verbose else None])
    u = result["undefined_refs"]
    out.append(f"undefined key references in code: {u['count']}")
    for count, f in u["worst_files"]:
        out.append(f"  {f}: {count} ({', '.join(u['by_file'][f][:3])})")
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(description="Lang key and placeholder parity, research node coverage, unused and undefined keys.")
    parser.add_argument("--json", action="store_true", help="print results as json")
    parser.add_argument("--verbose", action="store_true", help="list every offending key instead of the first ten")
    parser.add_argument("--fail-unused", action="store_true", help="count unused keys as violations")
    args = parser.parse_args(argv)
    result = analyse()
    count = violations(result) + (sum(d["unused"]["count"] for d in result["mods"].values()) if args.fail_unused else 0)
    if args.json:
        print(json.dumps({"ok": count == 0, "violations": count, **result}, indent=1))
    else:
        print("\n".join(render(result, args.verbose)))
        print(f"violations: {count}")
    return 0 if count == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
