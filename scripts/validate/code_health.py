import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from itertools import groupby
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODS = ["KamiLibs", "KamiClaims", "KamiEconomy", "KamiEssentials", "KamiGeology"]
CODE_SUFFIXES = (".kt", ".java")
RESOURCE_SUFFIXES = (".json", ".toml", ".mcmeta")
MAX_FILE_LOC = 400
MAX_FUNCTION_LINES = 80
DUP_WINDOW = 5
DUP_MIN_CHARS = 80
COLOR_SCOPE = re.compile(r"/(ui|client)/")
COLOR_ALLOW = re.compile(r"^(Palette|Theme.*|Vocabulary|SkillMap|MapColors|.*Render(er)?.*)$")
COLOR_LITERAL = re.compile(r"0x[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?\b|\"#[0-9A-Fa-f]{6,8}\"")
COLOR_MASK_CONTEXT = re.compile(r"\b(and|or|shr|shl|ushr|xor)\b|[&|]\s*0x")
EXCLUDED_ANNOTATIONS = {"SubscribeEvent", "SerialName", "Test", "ParameterizedTest", "Mixin", "Inject", "Shadow", "Accessor", "Invoker", "Overwrite", "Redirect", "ModifyArg", "ModifyVariable", "ModifyConstant", "WrapOperation", "Unique", "Mod", "EventBusSubscriber", "JeiPlugin", "WailaPlugin", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll", "Nested", "BeforeTest", "AfterTest"}
EXCLUDED_MODIFIERS = {"override", "operator", "abstract", "actual", "expect"}
VISIBILITY = {"private", "internal", "public", "protected", "const", "lateinit"}
WORD = re.compile(r"[A-Za-z_]\w*")
INTERESTING = re.compile(r"[/\"'{}]")
STRING_STOP = re.compile(r"[\"\\$]")
RAW_STOP = re.compile(r"\"\"\"|\$")
KT_DECL = re.compile(r"^([ \t]*)((?:[a-z]+\s+)*)(fun|val|var|class|object|interface|typealias)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w<>?,. ]*\.)?(\w+)", re.M)
KT_KIND = re.compile(r"\b(fun|val|var|class|object|interface|typealias)\b")
JAVA_DECL = re.compile(r"^([ \t]*)((?:(?:public|protected|private|static|final|abstract|default|synchronized)\s+)*)(?:(?:class|interface|enum|record)\s+(\w+)|[\w<>\[\],.? ]+\s+(\w+)\s*(?:\(|=|;))", re.M)
KT_FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w<>?,. ]*\.)?(`[^`]+`|\w+)\s*\(")
JAVA_FUN = re.compile(r"^[ \t]*(?:(?:public|protected|private|static|final|abstract|default|synchronized)\s+)+[\w<>\[\],.? ]+\s+(\w+)\s*\(", re.M)


def scan(text, kotlin):
    spans = []
    n = len(text)

    def block(i):
        depth, j = 0, i
        while j < n:
            if text.startswith("/*", j):
                depth += 1
                j += 2
            elif text.startswith("*/", j):
                depth -= 1
                j += 2
                if depth == 0:
                    break
            else:
                j += 1
        kind = "kdoc" if text.startswith("/**", i) and not text.startswith("/**/", i) else "block"
        spans.append((kind, i, j))
        return j

    def string(i, raw):
        start, j = i, i + (3 if raw else 1)
        stop = RAW_STOP if raw else STRING_STOP
        while j < n:
            m = stop.search(text, j)
            if not m:
                j = n
                break
            j = m.start()
            c = text[j]
            if c == "\\" and not raw:
                j += 2
            elif c == "$":
                j = code(j + 2, True) if kotlin and text.startswith("{", j + 1) else j + 1
            else:
                j += 3 if raw else 1
                break
        spans.append(("string", start, j))
        return j

    def char(i):
        if i + 1 < n and text[i + 1] == "\\":
            j = text.find("'", i + 2)
            return n if j < 0 else j + 1
        return i + 3 if i + 2 < n and text[i + 2] == "'" else i + 1

    def code(i, in_template):
        depth = 0
        while i < n:
            m = INTERESTING.search(text, i)
            if not m:
                return n
            i, c = m.start(), m.group()
            if c == "/" and text.startswith("//", i):
                j = text.find("\n", i)
                j = n if j < 0 else j
                spans.append(("line", i, j))
                i = j
            elif c == "/" and text.startswith("/*", i):
                i = block(i)
            elif c == "/":
                i += 1
            elif c == '"':
                i = string(i, text.startswith('"""', i))
            elif c == "'":
                i = char(i)
            elif c == "{":
                depth += 1
                i += 1
            else:
                if in_template and depth == 0:
                    return i + 1
                depth -= 1
                i += 1
        return i

    code(0, False)
    return spans


def blank(text, spans, kinds):
    chars = list(text)
    for kind, start, end in spans:
        if kind in kinds:
            for i in range(start, end):
                if chars[i] != "\n":
                    chars[i] = " "
    return "".join(chars)


def line_of(starts, offset):
    lo, hi = 0, len(starts)
    while lo < hi:
        mid = (lo + hi) // 2
        if starts[mid] <= offset:
            lo = mid + 1
        else:
            hi = mid
    return lo


def line_starts(text):
    return [0] + [m.end() for m in re.finditer("\n", text)]


class Source:
    def __init__(self, mod, path):
        self.mod = mod
        self.path = path
        self.rel = path.relative_to(ROOT).as_posix()
        self.kotlin = path.suffix == ".kt"
        self.text = path.read_text(encoding="utf8", errors="replace")
        self.spans = scan(self.text, self.kotlin)
        self.code = blank(self.text, self.spans, {"line", "block", "kdoc"})
        self.masked = blank(self.text, self.spans, {"line", "block", "kdoc", "string"})
        self.starts = line_starts(self.text)
        self.loc = sum(1 for line in self.code.splitlines() if line.strip())


def load_sources(mods):
    return [Source(mod, p) for mod in mods for scope in ("main", "test") for p in sorted((ROOT / mod / "src" / scope).rglob("*")) if p.is_file() and p.suffix in CODE_SUFFIXES]


def resource_text(mods):
    parts = []
    for mod in mods:
        for scope in ("main", "test"):
            for p in (ROOT / mod / "src" / scope / "resources").rglob("*"):
                if p.is_file() and p.suffix in RESOURCE_SUFFIXES and "/lang/" not in p.as_posix():
                    parts.append(p.read_text(encoding="utf8", errors="replace"))
    return "\n".join(parts)


def count_comments(sources):
    found = []
    for s in sources:
        counts = Counter(kind for kind, _, _ in s.spans if kind != "string")
        if counts:
            first = min(start for kind, start, _ in s.spans if kind != "string")
            found.append({"file": s.rel, "mod": s.mod, "kdoc": counts["kdoc"], "block": counts["block"], "line": counts["line"], "first_line": line_of(s.starts, first)})
    return found


def decl_annotations(s, line):
    lines = s.text.splitlines()
    names, i = set(), line - 1
    names.update(re.findall(r"@(\w+)", lines[i].split("fun ")[0].split("class ")[0]) if i < len(lines) else [])
    i -= 1
    while i >= 0 and lines[i].strip().startswith("@"):
        names.update(re.findall(r"@(\w+)", lines[i]))
        i -= 1
    return names


def declarations(s):
    found = []
    serial = "@Serializable" in s.code or "@SerialName" in s.code
    if s.kotlin:
        for m in KT_DECL.finditer(s.masked):
            indent, mods, kind, name = len(m.group(1).replace("	", "    ")), set(m.group(2).split()), m.group(3), m.group(4)
            if kind in ("val", "var") and (serial or (indent > 4 and not mods & VISIBILITY)):
                continue
            found.append((name, m.start(4), mods))
    elif "/mixin/" not in s.rel:
        for m in JAVA_DECL.finditer(s.masked):
            name = m.group(3) or m.group(4)
            if name and name not in {"return", "new", "else", "throw"}:
                found.append((name, m.start(3) if m.group(3) else m.start(4), set(m.group(2).split())))
    return found


def dead_symbols(sources, extra_text):
    words = Counter()
    for s in sources:
        words.update(WORD.findall(s.code))
    words.update(WORD.findall(extra_text))
    result = []
    for s in sources:
        for name, offset, mods in declarations(s):
            if words[name] != 1 or ("/src/test/" in s.rel and name.endswith("Test")) or mods & EXCLUDED_MODIFIERS or name in {"main", "Companion"}:
                continue
            line = line_of(s.starts, offset)
            if decl_annotations(s, line) & EXCLUDED_ANNOTATIONS:
                continue
            result.append({"symbol": name, "file": s.rel, "mod": s.mod, "line": line})
    return result


def duplicate_clusters(sources):
    windows = defaultdict(list)
    for si, s in enumerate(sources):
        lines = [(n, " ".join(t.split())) for n, t in enumerate(s.code.splitlines(), 1) if t.strip() and not t.lstrip().startswith(("import ", "package "))]
        for i in range(len(lines) - DUP_WINDOW + 1):
            joined = "\n".join(t for _, t in lines[i:i + DUP_WINDOW])
            if len(joined) > DUP_MIN_CHARS:
                windows[joined].append((si, i, lines[i][0], lines[i + DUP_WINDOW - 1][0]))
    parent = {}

    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        parent[find(a)] = find(b)

    dup = {k: v for k, v in windows.items() if len(v) > 1}
    spans = {}
    for locs in dup.values():
        for si, i, first, last in locs:
            spans[(si, i)] = (first, last)
            union((si, i), (locs[0][0], locs[0][1]))
            if (si, i - 1) in spans:
                union((si, i), (si, i - 1))
    for (si, i) in list(spans):
        if (si, i + 1) in spans:
            union((si, i), (si, i + 1))
    clusters = defaultdict(list)
    for key in spans:
        clusters[find(key)].append(key)
    report = []
    for members in clusters.values():
        by_file = defaultdict(list)
        for si, i in members:
            by_file[si].append(i)
        runs = []
        for si, indexes in by_file.items():
            for _, group in groupby(enumerate(sorted(indexes)), lambda t: t[1] - t[0]):
                run = [i for _, i in group]
                runs.append((sources[si].rel, spans[(si, run[0])][0], spans[(si, run[-1])][1]))
        report.append({"lines": max(b - a + 1 for _, a, b in runs), "copies": len(runs), "locations": [f"{f}:{a}-{b}" for f, a, b in sorted(runs)]})
    return sorted(report, key=lambda r: (-r["lines"] * (r["copies"] - 1), r["locations"]))


def matching_brace(masked, open_index):
    depth = 0
    for i in range(open_index, len(masked)):
        c = masked[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
    return len(masked) - 1


def function_sizes(s):
    found = []
    pattern = KT_FUN if s.kotlin else JAVA_FUN
    for m in pattern.finditer(s.masked):
        open_paren = s.masked.index("(", m.end(1))
        depth, i = 0, open_paren
        while i < len(s.masked):
            depth += {"(": 1, ")": -1}.get(s.masked[i], 0)
            if depth == 0:
                break
            i += 1
        tail = re.match(r"[^{=;]{0,200}([{=;])", s.masked[i + 1:])
        if not tail or tail.group(1) != "{" or re.search(r"\b(fun|val|var|class)\b", tail.group(0)):
            continue
        start = i + 1 + tail.end() - 1
        first, last = line_of(s.starts, m.start(1)), line_of(s.starts, matching_brace(s.masked, start))
        found.append({"function": m.group(1).strip("`"), "file": s.rel, "mod": s.mod, "line": first, "lines": last - first + 1})
    return found


def bang_counts(sources):
    return [{"file": s.rel, "mod": s.mod, "count": s.masked.count("!!")} for s in sources if s.kotlin and "!!" in s.masked]


def color_literals(sources):
    found = []
    for s in sources:
        if not COLOR_SCOPE.search("/" + s.rel) or COLOR_ALLOW.match(s.path.stem):
            continue
        for number, line in enumerate(s.code.splitlines(), 1):
            if COLOR_LITERAL.search(line) and not COLOR_MASK_CONTEXT.search(line):
                found.append({"file": s.rel, "mod": s.mod, "line": number})
    return found


def analyse(mods):
    sources = load_sources(mods)
    functions = [f for s in sources for f in function_sizes(s)]
    dups = duplicate_clusters(sources)
    data = {
        "comments": count_comments(sources),
        "dead": dead_symbols(sources, resource_text(mods)),
        "duplicates": dups,
        "big_files": sorted(({"file": s.rel, "mod": s.mod, "loc": s.loc} for s in sources if s.loc > MAX_FILE_LOC), key=lambda r: -r["loc"]),
        "long_functions": sorted((f for f in functions if f["lines"] > MAX_FUNCTION_LINES), key=lambda r: -r["lines"]),
        "bangs": bang_counts(sources),
        "colors": color_literals(sources),
    }
    table = {}
    for mod in mods:
        mine = [s for s in sources if s.mod == mod]
        table[mod] = {
            "files": len(mine),
            "loc": sum(s.loc for s in mine),
            "comments": sum(c["kdoc"] + c["block"] + c["line"] for c in data["comments"] if c["mod"] == mod),
            "dead": sum(1 for d in data["dead"] if d["mod"] == mod),
            "dup_clusters": sum(1 for d in dups if any(loc.startswith(mod + "/") for loc in d["locations"])),
            "big_files": sum(1 for d in data["big_files"] if d["mod"] == mod),
            "long_functions": sum(1 for d in data["long_functions"] if d["mod"] == mod),
            "bangs": sum(d["count"] for d in data["bangs"] if d["mod"] == mod),
            "colors": sum(1 for d in data["colors"] if d["mod"] == mod),
        }
    return {"table": table, "totals": {k: sum(r[k] for r in table.values()) for k in next(iter(table.values()))}, **data}


def render(result, top):
    cols = list(result["totals"])
    out = ["mod".ljust(16) + "".join(c.rjust(max(len(c), 6) + 2) for c in cols)]
    for name, row in [*result["table"].items(), ("TOTAL", result["totals"])]:
        out.append(name.ljust(16) + "".join(str(row[c]).rjust(max(len(c), 6) + 2) for c in cols))
    sections = [
        ("comments", lambda r: f"{r['file']}:{r['first_line']} kdoc={r['kdoc']} block={r['block']} line={r['line']}"),
        ("big_files", lambda r: f"{r['file']} loc={r['loc']}"),
        ("long_functions", lambda r: f"{r['file']}:{r['line']} {r['function']} lines={r['lines']}"),
        ("bangs", lambda r: f"{r['file']} !!={r['count']}"),
        ("colors", lambda r: f"{r['file']}:{r['line']}"),
        ("dead", lambda r: f"{r['file']}:{r['line']} {r['symbol']}"),
        ("duplicates", lambda r: f"lines={r['lines']} copies={r['copies']} " + " | ".join(r["locations"][:3])),
    ]
    for name, fmt in sections:
        rows = result[name]
        if name == "bangs":
            rows = sorted(rows, key=lambda r: -r["count"])
        if rows:
            out.append(f"\n{name} (top {min(top, len(rows))} of {len(rows)})")
            out.extend("  " + fmt(r) for r in rows[:top])
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(description="Comment counter, dead symbols, duplicates, size and color report for the Kami mods.")
    parser.add_argument("--mods", nargs="+", default=MODS, help="module folders under the repo root")
    parser.add_argument("--top", type=int, default=10, help="rows to print per section")
    parser.add_argument("--json", action="store_true", help="print results as json")
    parser.add_argument("--strict", action="store_true", help="also fail on dead symbols, duplicates, size caps, !! and color literals")
    args = parser.parse_args(argv)
    result = analyse(args.mods)
    totals = result["totals"]
    failing = totals["comments"] + (sum(totals[k] for k in ("dead", "dup_clusters", "big_files", "long_functions", "bangs", "colors")) if args.strict else 0)
    if args.json:
        print(json.dumps({"ok": failing == 0, **result}, indent=1))
    else:
        print("\n".join(render(result, args.top)))
    return 0 if failing == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
