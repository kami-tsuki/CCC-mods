import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REMOVE_STUB = "remove() {}, removeAll() {}, removeAllTagsFrom() {}"
REMOVE_REAL = "remove(tag, ids) { (tagAdds[kind + ':!' + tag] = tagAdds[kind + ':!' + tag] || []).push(...[].concat(ids)); return {}; }, removeAll() {}, removeAllTagsFrom(ids) { (tagAdds[kind + ':!*'] = tagAdds[kind + ':!*'] || []).push(...[].concat(ids)); }"


def load_audit(mods=None):
    spec = importlib.util.spec_from_file_location("research_audit", ROOT / "scripts/research_audit.py")
    ra = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ra)
    if mods:
        ra.SERVER = str(Path(mods).resolve().parent)
    ra.HARNESS = ra.HARNESS.replace(REMOVE_STUB, REMOVE_REAL)
    original = ra.resolve_tags

    def resolve_tags(raw, extra):
        removed = {k: v for k, v in extra.items() if k.startswith("!")}
        memo = original(raw, {k: v for k, v in extra.items() if not k.startswith("!")})
        for tag, ids in removed.items():
            if tag[1:] == "*":
                for k in memo:
                    memo[k] = set(memo[k]) - set(ids)
            elif tag[1:] in memo:
                memo[tag[1:]] = set(memo[tag[1:]]) - set(ids)
        return memo

    ra.resolve_tags = resolve_tags
    return ra


def load_model(mods=None):
    ra = load_audit(mods)
    model = ra.Model()
    model.by_first = model.by_out
    model.resolve()
    return ra, model
