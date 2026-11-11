import argparse
import difflib
import fnmatch
import io
import json
import pickle
import re
import sys
import tempfile
import zipfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODS = Path("E:/MC_Prism/Instances/1.21.1/minecraft/mods")
INSTANCE = Path("E:/MC_Prism/Instances/1.21.1/minecraft")
KUBEJS = INSTANCE / "kubejs"
PACKS = INSTANCE / "tacz"
CACHE = Path(tempfile.gettempdir()) / "kami_validate" / "refs_index.pkl"
SKIP_NAMESPACES = {"minecraft", "c", "neoforge", "kubejs", "kami_js", "kami_libs", "kami_claims", "kami_economy", "kami_geology", "kami_essentials"}
CLAIMS_RES = ROOT / "KamiClaims/src/main/resources"
SCAN_ROOTS = [
    ("KubeJS", KUBEJS, ["server_scripts", "startup_scripts", "client_scripts", "data"]),
    ("Claims", CLAIMS_RES, ["assets/kami_claims/research", "data"]),
    ("Economy", ROOT / "KamiEconomy/src/main/resources", ["kami_economy"]),
    ("Geology", ROOT / "KamiGeology/src/main/resources", ["data"]),
    ("Essentials", ROOT / "KamiEssentials/src/main/resources", ["kami_essentials"]),
]
SCAN_SUFFIXES = (".js", ".json", ".json5")
SKIP_FILES = {"legacy_sigs.json"}
REF = re.compile(r"(#?)([a-z][a-z0-9_.-]*):([a-z0-9_./*-]+)")
LANG_KEY = re.compile(r"(item|block|fluid|fluid_type|entity|effect|enchantment)\.([a-z0-9_-]+)\.(.+)$")
FLUID_LANG = {"fluid", "fluid_type"}
MAX_NESTING = 2


def index_jar(zf, owner, depth, ids, tags, namespaces):
    for name in zf.namelist():
        parts = name.split("/")
        if parts[0] in ("assets", "data") and len(parts) >= 4:
            ns, kind = parts[1], parts[2]
            namespaces.setdefault(ns, set()).add(owner)
            stem = "/".join(parts[3:])[:-5]
            if parts[0] == "assets" and name.endswith(".json"):
                if kind == "models" and parts[3] in ("item", "block"):
                    ids.add(f"{ns}:{'/'.join(parts[4:])[:-5]}")
                elif kind == "blockstates":
                    ids.add(f"{ns}:{stem}")
                elif kind == "lang" and parts[3] == "en_us.json":
                    index_lang(zf.read(name), ids)
            elif parts[0] == "data" and name.endswith(".json"):
                if kind == "tags" and len(parts) >= 5:
                    tag = f"{ns}:{'/'.join(parts[4:])[:-5]}"
                    tags.add(tag)
                    tags.add(f"{parts[3]}|{tag}")
                    if parts[3] == "fluid":
                        index_fluid_tag(zf.read(name), ids)
                else:
                    ids.add(f"{ns}:{stem}")
        elif name == "META-INF/neoforge.mods.toml":
            for mod_id in re.findall(r'modId\s*=\s*"([^"]+)"', zf.read(name).decode("utf8", "replace")):
                namespaces.setdefault(mod_id, set()).add(owner)
        if depth < MAX_NESTING and name.startswith("META-INF/jars/") and name.endswith(".jar"):
            try:
                index_jar(zipfile.ZipFile(io.BytesIO(zf.read(name))), owner, depth + 1, ids, tags, namespaces)
            except zipfile.BadZipFile:
                pass


def index_lang(raw, ids):
    try:
        lang = json.loads(raw.decode("utf8", "replace"))
    except json.JSONDecodeError:
        return
    for key in lang:
        m = LANG_KEY.match(key)
        if not m or m.group(3).endswith(".desc") or ".tooltip" in m.group(3):
            continue
        kind, ns, path = m.groups()
        for variant in {path, path.replace(".", "/")}:
            ids.add(f"{ns}:{variant}")
            if kind in FLUID_LANG:
                ids.add(f"{ns}:flowing_{variant}")


def index_fluid_tag(raw, ids):
    try:
        values = json.loads(raw.decode("utf8", "replace")).get("values", [])
    except json.JSONDecodeError:
        return
    for value in values:
        entry = value if isinstance(value, str) else value.get("id", "")
        if entry and not entry.startswith("#") and ":" in entry:
            ids.add(entry)
            ns, _, path = entry.partition(":")
            ids.add(f"{ns}:flowing_{path}")


def index_packs(packs, ids, namespaces):
    for path in packs.glob("*/data/*/**/*.json"):
        ns, *rest = path.relative_to(packs).parts[2:]
        namespaces.setdefault(ns, set())
        ids.add(f"{ns}:{path.stem}")
        ids.add(f"{ns}:{'/'.join([*rest[1:-1], path.stem])}")


def index_signature(mods, packs):
    jars = [(p.name, p.stat().st_size, p.stat().st_mtime_ns) for p in sorted(mods.glob("*.jar"))]
    files = [p.stat().st_mtime_ns for p in packs.glob("*/data/**/*.json")]
    return [jars, len(files), max(files, default=0)]


def load_index(mods, packs, cache, refresh):
    signature = index_signature(mods, packs)
    if not refresh and cache.exists():
        stored = pickle.loads(cache.read_bytes())
        if stored["signature"] == signature:
            return stored["ids"], stored["tags"], stored["namespaces"], True
    ids, tags, namespaces = set(), set(), {}
    for jar in sorted(mods.glob("*.jar")):
        try:
            with zipfile.ZipFile(jar) as zf:
                index_jar(zf, jar.name, 0, ids, tags, namespaces)
        except zipfile.BadZipFile:
            print(f"unreadable jar: {jar.name}", file=sys.stderr)
    index_packs(packs, ids, namespaces)
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_bytes(pickle.dumps({"signature": signature, "ids": ids, "tags": tags, "namespaces": namespaces}))
    return ids, tags, namespaces, False


def collect_refs(roots):
    refs = defaultdict(list)
    for label, base, subs in roots:
        for sub in subs:
            for path in sorted((base / sub).rglob("*")):
                if path.suffix not in SCAN_SUFFIXES or path.name in SKIP_FILES:
                    continue
                rel = f"{label}:{path.relative_to(base).as_posix()}"
                for number, line in enumerate(path.read_text(encoding="utf8", errors="replace").splitlines(), 1):
                    for m in REF.finditer(line):
                        hash_, ns, ref_path = m.groups()
                        start = m.start()
                        if hash_ and start and re.match(r"[a-z0-9_]", line[start - 1]):
                            hash_ = ""
                        ref_path = ref_path.rstrip("./-")
                        if not ref_path or ref_path.startswith("/") or ref_path.endswith("_") or ".*" in ref_path or line[m.end():m.end() + 2] == "//":
                            continue
                        refs[(hash_, ns, ref_path)].append((rel, number))
    return refs


def class_literals(mods, owners, needed):
    found = set()

    def scan(zf):
        for name in zf.namelist():
            if name.endswith(".class"):
                data = zf.read(name)
                found.update(p for p in needed if p.encode() in data)
            elif name.startswith("META-INF/jars/") and name.endswith(".jar"):
                scan(zipfile.ZipFile(io.BytesIO(zf.read(name))))

    for owner in owners:
        with zipfile.ZipFile(mods / owner) as zf:
            scan(zf)
    return found


def drop_registered(missing, mods, namespaces):
    needed = defaultdict(set)
    for entry in missing:
        ns, _, path = entry["ref"].lstrip("#").partition(":")
        if "*" not in path:
            needed[ns].add(path)
    registered = {ns: class_literals(mods, namespaces[ns], paths) for ns, paths in needed.items()}
    return [e for e in missing if "*" in e["ref"] or e["ref"].lstrip("#").partition(":")[2] not in registered[e["ref"].lstrip("#").partition(":")[0]]]


def find_missing(refs, ids, tags, namespaces):
    by_ns = defaultdict(list)
    for entry in ids:
        ns, _, path = entry.partition(":")
        by_ns[ns].append(path)
    tag_ns = defaultdict(list)
    for entry in tags:
        if "|" not in entry:
            ns, _, path = entry.partition(":")
            tag_ns[ns].append(path)
    missing, unknown = [], defaultdict(int)
    for (hash_, ns, path), locations in refs.items():
        if ns in SKIP_NAMESPACES:
            continue
        if ns not in namespaces:
            unknown[ns] += 1
            continue
        pool = tag_ns[ns] if hash_ else by_ns[ns]
        if "*" in path:
            found = any(fnmatch.fnmatch(p, path) for p in pool)
        else:
            found = f"{ns}:{path}" in (tags if hash_ else ids) or any(p.startswith(f"{path}/") for p in pool)
        if not found:
            hint = [] if "*" in path else difflib.get_close_matches(path, pool, 1, 0.6)
            missing.append({"ref": f"{hash_}{ns}:{path}", "hint": f"{hash_}{ns}:{hint[0]}" if hint else "", "locations": locations})
    return missing, unknown


def group_by_file(missing):
    grouped = defaultdict(list)
    for entry in missing:
        by_file = defaultdict(list)
        for rel, line in entry["locations"]:
            by_file[rel].append(line)
        for rel, lines in by_file.items():
            grouped[rel].append((min(lines), entry["ref"], entry["hint"], len(lines)))
    return grouped


def main(argv=None):
    parser = argparse.ArgumentParser(description="Check that every namespaced id referenced by Kami data and KubeJS exists in the installed mods.")
    parser.add_argument("--mods", type=Path, default=MODS, help="folder with the installed mod jars")
    parser.add_argument("--packs", type=Path, default=PACKS, help="folder with gun pack directories (tacz)")
    parser.add_argument("--cache", type=Path, default=CACHE, help="pickle path for the cached jar index")
    parser.add_argument("--refresh", action="store_true", help="rebuild the jar index even if the cache is current")
    parser.add_argument("--unknown-ns", action="store_true", help="also list namespaces that no installed jar provides")
    parser.add_argument("--json", action="store_true", help="print results as json")
    args = parser.parse_args(argv)
    ids, tags, namespaces, cached = load_index(args.mods, args.packs, args.cache, args.refresh)
    refs = collect_refs(SCAN_ROOTS)
    missing, unknown = find_missing(refs, ids, tags, namespaces)
    missing = drop_registered(missing, args.mods, namespaces)
    summary = {"ids": len(ids), "tags": len(tags), "namespaces": len(namespaces), "unique_refs": len(refs), "missing": len(missing), "unknown_namespaces": len(unknown), "index_cached": cached}
    ok = not missing
    if args.json:
        print(json.dumps({"ok": ok, "summary": summary, "missing": missing, "unknown_namespaces": dict(unknown)}, indent=1))
        return 0 if ok else 1
    print(f"index: {summary['ids']} ids, {summary['tags']} tags, {summary['namespaces']} namespaces ({'cached' if cached else 'rebuilt'})")
    print(f"references: {summary['unique_refs']} unique, {len(missing)} missing, {len(unknown)} unknown namespaces")
    for rel, rows in sorted(group_by_file(missing).items()):
        print(f"\n{rel}")
        for line, ref, hint, count in sorted(rows):
            print(f"  L{line} {ref}{f' (x{count})' if count > 1 else ''}{f'  -> {hint}' if hint else ''}")
    if args.unknown_ns and unknown:
        print("\nunknown namespaces: " + ", ".join(f"{k}({v})" for k, v in sorted(unknown.items())))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
