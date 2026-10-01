"""Audit of the research trees (metallurgy, technology) against the pack recipes.

Usage: python scripts/research_audit.py [--ns create,create_connected] [--section abcd] [--all]
Needs node for the KubeJS replay. Reports (a) redundant requirements, (b) soft locks,
(c) level and order problems, (d) coverage of unlocked items per namespace.
Lines marked with * involve an owner-fixed node.
"""
import argparse
import json
import os
import pickle
import re
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
RES = os.path.join(ROOT, 'KamiClaims', 'src', 'main', 'resources', 'assets', 'kami_claims', 'research')
SERVER = os.path.join(ROOT, 'Test-Server-NeoForge-1.21.1')
TREES = ['metallurgy', 'technology']
IGNORE_PRODUCERS = {'technology:wood_building', 'technology:crafting_table'}

OWNER = {
    'technology:crafting_table', 'technology:wood_building', 'technology:andesite_casing', 'technology:hand_crank',
    'technology:shafts_cogs', 'technology:water_wheel', 'technology:millstone', 'technology:belt',
    'technology:crushing_wheels', 'technology:gearbox', 'technology:mechanical_press', 'technology:large_water_wheel',
    'technology:propeller_whisk', 'technology:encased_fan', 'technology:chain_drives', 'technology:windmill',
    'technology:blaze_burner', 'technology:copper_casing', 'technology:fluid_basics', 'technology:chain_conveyor',
    'technology:fluid_handling', 'technology:hose_pulley', 'technology:portable_fluid_interface',
    'technology:steam_engine', 'technology:brass_hand', 'technology:steam_whistle', 'technology:copper_table_cover',
    'technology:deployer', 'technology:transmitter', 'technology:precision_mechanism', 'technology:copper_backtank',
    'technology:copper_diving_gear', 'technology:potato_cannon', 'technology:mixer',
    'metallurgy:andesite_alloy_iron', 'metallurgy:andesite_alloy_zinc', 'metallurgy:brass', 'metallurgy:iron_pressing',
}

HARNESS = r"""
const fs = require('fs'), vm = require('vm');
const cfg = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const base = cfg.recipes;
const recipes = new Map(Object.entries(base).map(([k, v]) => [k, JSON.parse(JSON.stringify(v))]));
const tagItems = cfg.tags;
const errors = [];
const tagAdds = {};
const handlers = [];
const tagHandlers = [];
const tagSets = {};
const inTag = (tag, item) => {
  if (!tagSets[tag]) tagSets[tag] = new Set(tagItems[tag] || []);
  return tagSets[tag].has(item);
};
const itemMatch = (spec, item) => {
  if (spec instanceof RegExp) return spec.test(item);
  if (typeof spec === 'object' && spec) spec = spec.item || spec.tag && '#' + spec.tag || String(spec);
  spec = String(spec);
  if (spec.startsWith('#')) return inTag(spec.slice(1), item);
  return spec === item;
};
const stack = (o, out, skip) => {
  if (Array.isArray(o)) { o.forEach(x => stack(x, out, skip)); return; }
  if (!o || typeof o !== 'object') return;
  if (typeof o.item === 'string') { out.push(o.item); return; }
  if (typeof o.tag === 'string') { (tagItems[o.tag] || []).forEach(i => out.push(i)); return; }
  for (const k of Object.keys(o)) if (!skip.includes(k)) stack(o[k], out, skip);
};
const outputsOf = j => {
  const out = [];
  const take = r => { if (typeof r === 'string') out.push(r); else if (r) out.push(r.id || r.item); };
  if (j.result !== undefined) take(j.result);
  if (Array.isArray(j.results)) j.results.forEach(take);
  if (j.output !== undefined) take(j.output);
  return out.filter(Boolean);
};
const inputsOf = j => { const out = []; stack(j, out, ['result', 'results', 'output', 'transitional_item', 'transitionalItem']); return out; };
const idMatch = (f, s) => f instanceof RegExp ? f.test(s) : String(f) === s;
const test = (f, id, j) => {
  if (Array.isArray(f)) return f.some(x => test(x, id, j));
  if (f.or) return f.or.some(x => test(x, id, j));
  if (f.and) return f.and.every(x => test(x, id, j));
  if (f.not) return !test(f.not, id, j);
  for (const k of Object.keys(f)) {
    const v = f[k];
    if (k === 'id' && !idMatch(v, id)) return false;
    if (k === 'type' && !idMatch(v, j.type)) return false;
    if (k === 'mod' && id.split(':')[0] !== v) return false;
    if (k === 'output' && !outputsOf(j).some(o => itemMatch(v, o))) return false;
    if (k === 'input' && !inputsOf(j).some(o => itemMatch(v, o))) return false;
  }
  return true;
};
const filterOf = f => (f === undefined || f === null) ? {} : f;
const swapIn = (o, from, to) => {
  if (Array.isArray(o)) { o.forEach(x => swapIn(x, from, to)); return; }
  if (!o || typeof o !== 'object') return;
  if (typeof o.item === 'string' && itemMatch(from, o.item)) { delete o.item; Object.assign(o, toIng(to)); return; }
  if (typeof o.tag === 'string' && typeof from === 'string' && from === '#' + o.tag) { delete o.tag; Object.assign(o, toIng(to)); return; }
  for (const k of Object.keys(o)) if (!['result', 'results', 'output'].includes(k)) swapIn(o[k], from, to);
};
const toIng = t => typeof t === 'string' ? (t.startsWith('#') ? { tag: t.slice(1) } : { item: t }) : t;
const swapOut = (j, from, to) => {
  const fix = r => {
    if (typeof r === 'string') return itemMatch(from, r) ? String(to) : r;
    if (r && itemMatch(from, r.id || r.item)) { if (r.id) r.id = String(to); else r.item = String(to); }
    return r;
  };
  if (j.result !== undefined) j.result = fix(j.result);
  if (Array.isArray(j.results)) j.results = j.results.map(fix);
};
let counter = 0;
const wrap = (json, id) => ({ id(x) { recipes.delete(this._id); this._id = String(x); recipes.set(this._id, json); return this; }, _id: id, json });
const ev = {
  remove(f) { f = Array.isArray(f) ? { or: f } : filterOf(f); for (const [id, j] of [...recipes]) if (test(f, id, j)) recipes.delete(id); },
  custom(json) { const id = 'kubejs:' + (++counter); recipes.set(id, json); return wrap(json, id); },
  shaped(out, pattern, key) { const id = 'kubejs:s' + (++counter); const j = { type: 'minecraft:crafting_shaped', pattern, key: Object.fromEntries(Object.entries(key).map(([k, v]) => [k, toIng(v)])), result: { id: String(out).replace(/^\d+x /, '') } }; recipes.set(id, j); return wrap(j, id); },
  shapeless(out, ings) { const id = 'kubejs:l' + (++counter); const j = { type: 'minecraft:crafting_shapeless', ingredients: ings.map(toIng), result: { id: String(out).replace(/^\d+x /, '') } }; recipes.set(id, j); return wrap(j, id); },
  stonecutting(out, input) { const id = 'kubejs:c' + (++counter); const j = { type: 'minecraft:stonecutting', ingredient: toIng(input), result: { id: String(out).replace(/^\d+x /, '') } }; recipes.set(id, j); return wrap(j, id); },
  replaceInput(f, from, to) { f = filterOf(f); for (const [id, j] of recipes) if (test(f, id, j)) swapIn(j, from, to); },
  replaceOutput(f, from, to) { f = filterOf(f); for (const [id, j] of recipes) if (test(f, id, j)) swapOut(j, from, to); },
  forEachRecipe(f, cb) { f = Array.isArray(f) ? { or: f } : filterOf(f); for (const [id, j] of [...recipes]) if (test(f, id, j)) cb({ getOrCreateId: () => id, getId: () => id, json: { toString: () => JSON.stringify(j) } }); },
};
const ctx = vm.createContext({
  Java: { loadClass: () => ({}) },
  console: { log() {}, info() {}, warn() {}, error() {} },
  ServerEvents: { recipes: cb => handlers.push(cb), tags: (kind, cb) => tagHandlers.push([kind, cb]), highPriorityData() {}, lowPriorityData() {}, generateData() {}, loaded() {}, commandRegistry() {} },
  EntityEvents: new Proxy({}, { get: () => () => {} }),
  ItemEvents: new Proxy({}, { get: () => () => {} }),
  BlockEvents: new Proxy({}, { get: () => () => {} }),
  PlayerEvents: new Proxy({}, { get: () => () => {} }),
  LevelEvents: new Proxy({}, { get: () => () => {} }),
  NetworkEvents: new Proxy({}, { get: () => () => {} }),
  Item: { exists: () => true, of: (id, n) => ({ item: id, count: n }) },
  Platform: { isLoaded: () => true },
  Utils: new Proxy({}, { get: () => () => {} }),
  JsonIO: new Proxy({}, { get: () => () => {} }),
  Text: new Proxy({}, { get: () => () => {} }),
});
for (const s of cfg.scripts) {
  let code = fs.readFileSync(s, 'utf8').replace(/^﻿/, '');
  try { vm.runInContext(code, ctx, { filename: s }); } catch (e) { errors.push(s + ': ' + e.message); }
}
for (const h of handlers) { try { h(ev); } catch (e) { errors.push('recipes handler: ' + e.message + ' @ ' + String(e.stack).split('\n')[1]); } }
for (const [kind, cb] of tagHandlers) {
  const tev = { add(tag, ids) { (tagAdds[kind + ':' + tag] = tagAdds[kind + ':' + tag] || []).push(...[].concat(ids)); return { }; }, remove() {}, removeAll() {}, removeAllTagsFrom() {}, get() { return { add() {}, remove() {} }; } };
  try { cb(tev); } catch (e) { errors.push('tags handler: ' + e.message); }
}
const removed = Object.keys(base).filter(k => !recipes.has(k));
const changed = {};
for (const [k, v] of recipes) if (!(k in base) || JSON.stringify(base[k]) !== JSON.stringify(v)) changed[k] = v;
fs.writeFileSync(process.argv[3], JSON.stringify({ removed, changed, errors, tagAdds }));
"""


def read_jar(zf, data, depth=0):
    for name in zf.namelist():
        if name.endswith('/'):
            continue
        if name.endswith('.jar') and 'META-INF/jarjar' in name and depth < 3:
            try:
                import io
                with zipfile.ZipFile(io.BytesIO(zf.read(name))) as inner:
                    read_jar(inner, data, depth + 1)
            except zipfile.BadZipFile:
                pass
            continue
        m = re.match(r'data/([^/]+)/(recipes?)/(.+)\.json$', name)
        if m:
            data['recipes'][f'{m.group(1)}:{m.group(3)}'] = name
            data['_raw'][name] = zf.read(name)
            continue
        m = re.match(r'data/([^/]+)/tags/(items?|blocks?)/(.+)\.json$', name)
        if m:
            kind = 'item' if m.group(2).startswith('item') else 'block'
            data['_raw'][name] = zf.read(name)
            data['tags'][kind].append((f'{m.group(1)}:{m.group(3)}', name))
            continue
        m = re.match(r'assets/([^/]+)/lang/en_us\.json$', name)
        if m:
            data['_raw'][name] = zf.read(name)
            data['lang'].append(name)
            continue
        m = re.match(r'assets/([^/]+)/blockstates/(.+)\.json$', name)
        if m:
            data['blocks'].add(f'{m.group(1)}:{m.group(2)}')
            continue
        m = re.match(r'assets/([^/]+)/models/item/(.+)\.json$', name)
        if m:
            data['items'].add(f'{m.group(1)}:{m.group(2)}')
            continue
        m = re.match(r'(?:data|assets)/([^/]+)/', name)
        if m:
            data['namespaces'].add(m.group(1))


def load_pack():
    mods = os.path.join(SERVER, 'mods')
    libs = os.path.join(SERVER, 'libraries', 'net')
    jars = []
    for base, _, files in os.walk(libs):
        for f in files:
            if f.endswith('-extra.jar') and 'minecraft' in base or f.endswith('-universal.jar') and 'neoforge' in base:
                jars.append(os.path.join(base, f))
    jars += [os.path.join(mods, f) for f in sorted(os.listdir(mods)) if f.endswith('.jar')]
    sig = [(f, os.path.getsize(f), int(os.path.getmtime(f))) for f in jars]
    cache = os.path.join(tempfile.gettempdir(), 'research_audit_cache.pkl')
    if os.path.exists(cache):
        try:
            with open(cache, 'rb') as fh:
                saved = pickle.load(fh)
            if saved['sig'] == sig:
                return saved['pack']
        except Exception:
            pass
    pack = {'recipes': {}, 'tags': {'item': defaultdict(list), 'block': defaultdict(list)}, 'lang': {}, 'blocks': set(), 'items': set(), 'namespaces': set(), 'conditions': {}}
    for jar in jars:
        data = {'recipes': {}, 'tags': {'item': [], 'block': []}, 'lang': [], 'blocks': set(), 'items': set(), 'namespaces': set(), '_raw': {}}
        try:
            with zipfile.ZipFile(jar) as zf:
                read_jar(zf, data)
        except zipfile.BadZipFile:
            print('unreadable jar', jar, file=sys.stderr)
            continue
        raw = data['_raw']
        for rid, name in data['recipes'].items():
            try:
                pack['recipes'][rid] = json.loads(raw[name])
            except Exception:
                pass
        for kind in ('item', 'block'):
            for tid, name in data['tags'][kind]:
                try:
                    pack['tags'][kind][tid].append(json.loads(raw[name]))
                except Exception:
                    pass
        for name in data['lang']:
            try:
                pack['lang'].update(json.loads(raw[name]))
            except Exception:
                pass
        pack['blocks'] |= data['blocks']
        pack['items'] |= data['items']
        pack['namespaces'] |= data['namespaces']
    pack['tags'] = {k: dict(v) for k, v in pack['tags'].items()}
    with open(cache, 'wb') as fh:
        pickle.dump({'sig': sig, 'pack': pack}, fh)
    return pack


def condition_ok(recipe, namespaces):
    conds = recipe.get('neoforge:conditions') or recipe.get('conditions') or []

    def ok(c):
        t = c.get('type', '')
        if t == 'neoforge:mod_loaded':
            return c.get('modid') in namespaces
        if t == 'neoforge:not':
            return not ok(c.get('value', {}))
        if t == 'neoforge:and':
            return all(ok(x) for x in c.get('values', []))
        if t == 'neoforge:or':
            return any(ok(x) for x in c.get('values', []))
        return True
    return all(ok(c) for c in conds)


def resolve_tags(raw, extra):
    memo = {}

    def expand(tag, seen):
        if tag in memo:
            return memo[tag]
        if tag in seen:
            return set()
        out = set()
        seen = seen | {tag}
        for doc in raw.get(tag, []):
            if doc.get('replace'):
                out = set()
            for v in doc.get('values', []):
                v = v.get('id') if isinstance(v, dict) else v
                if not v:
                    continue
                if v.startswith('#'):
                    out |= expand(v[1:], seen)
                else:
                    out.add(v)
        for v in extra.get(tag, []):
            if v.startswith('#'):
                out |= expand(v[1:], seen)
            else:
                out.add(v)
        memo[tag] = out
        return out
    for tag in list(raw) + list(extra):
        expand(tag, frozenset())
    return memo


def ingredient_items(ing, tags):
    if isinstance(ing, str):
        return {ing} if not ing.startswith('#') else set(tags.get(ing[1:], ()))
    if isinstance(ing, list):
        out = set()
        for x in ing:
            out |= ingredient_items(x, tags)
        return out
    if isinstance(ing, dict):
        if 'item' in ing:
            return {ing['item']} if isinstance(ing['item'], str) else set()
        if 'tag' in ing:
            return set(tags.get(ing['tag'], ()))
        out = set()
        for key in ('children', 'ingredients', 'base'):
            if key in ing:
                out |= ingredient_items(ing[key], tags)
        return out
    return set()


def result_ids(r):
    if isinstance(r, str):
        return [r]
    if isinstance(r, dict):
        v = r.get('id') or r.get('item')
        return [v] if isinstance(v, str) else []
    return []


def recipe_facts(rid, j, tags):
    outs = []
    sequenced = 'sequenced_assembly' in j.get('type', '')
    for key in ('result', 'results', 'output'):
        v = j.get(key)
        if isinstance(v, list):
            for n, r in enumerate(v):
                if n == 0 or (not sequenced and not (isinstance(r, dict) and 'chance' in r)):
                    outs += result_ids(r)
        elif v is not None:
            outs += result_ids(v)
    transitional = set()
    for key in ('transitional_item', 'transitionalItem'):
        transitional |= set(result_ids(j.get(key)))
    slots = []

    def walk(o, key=None):
        if isinstance(o, list):
            if key in ('ingredients',):
                for x in o:
                    s = ingredient_items(x, tags)
                    if s:
                        slots.append(s)
            elif key in ('ingredient', 'input', 'base', 'addition', 'template') or (o and all(isinstance(x, dict) and ('item' in x or 'tag' in x) for x in o)):
                s = ingredient_items(o, tags)
                if s:
                    slots.append(s)
            else:
                for x in o:
                    walk(x, None)
            return
        if not isinstance(o, dict):
            return
        if isinstance(o.get('item'), str) or 'tag' in o:
            s = ingredient_items(o, tags)
            if s:
                slots.append(s)
            return
        for k, v in o.items():
            if k in ('result', 'results', 'output', 'transitional_item', 'transitionalItem'):
                continue
            if k == 'key' and isinstance(v, dict):
                for sub in v.values():
                    s = ingredient_items(sub, tags)
                    if s:
                        slots.append(s)
                continue
            if k in ('ingredient', 'input', 'ingredients', 'base', 'addition', 'template'):
                walk(v, k)
            elif isinstance(v, (dict, list)):
                walk(v, k)
    walk({k: v for k, v in j.items() if k not in ('neoforge:conditions',)})
    slots = [s for s in slots if not (s and s <= transitional)]
    uniq = []
    for s in slots:
        if s not in uniq:
            uniq.append(s)
    flat = set()
    for s in uniq:
        flat |= s
    return {'id': rid, 'type': j.get('type', ''), 'outs': outs, 'first': outs[:1], 'slots': uniq, 'flat': flat}


def kubejs(pack_recipes, tags_flat):
    unparsed = []
    scripts = []
    sdir = os.path.join(SERVER, 'kubejs', 'server_scripts')
    for base, _, files in os.walk(sdir):
        for f in files:
            if f.endswith('.js'):
                scripts.append(os.path.join(base, f))
    scripts.sort()
    changed, removed, tag_adds = {}, [], {}
    with tempfile.TemporaryDirectory() as tmp:
        cfg = os.path.join(tmp, 'cfg.json')
        out = os.path.join(tmp, 'out.json')
        js = os.path.join(tmp, 'harness.js')
        with open(js, 'w', encoding='utf8') as fh:
            fh.write(HARNESS)
        with open(cfg, 'w', encoding='utf8') as fh:
            json.dump({'recipes': pack_recipes, 'tags': {k: sorted(v) for k, v in tags_flat.items()}, 'scripts': scripts}, fh)
        try:
            proc = subprocess.run(['node', js, cfg, out], capture_output=True, text=True, timeout=300)
            if proc.returncode != 0:
                unparsed.append('node failed: ' + proc.stderr.strip()[:300])
            else:
                with open(out, encoding='utf8') as fh:
                    res = json.load(fh)
                changed, removed, tag_adds = res['changed'], res['removed'], res['tagAdds']
                unparsed += res['errors']
        except FileNotFoundError:
            unparsed.append('node is not installed, KubeJS scripts skipped')
    ddir = os.path.join(SERVER, 'kubejs', 'data')
    for base, _, files in os.walk(ddir):
        for f in files:
            m = re.match(r'.*[\\/]data[\\/]([^\\/]+)[\\/]recipes?[\\/](.+)\.json$', os.path.join(base, f))
            if m and f.endswith('.json'):
                try:
                    with open(os.path.join(base, f), encoding='utf8') as fh:
                        changed[f'{m.group(1)}:{m.group(2).replace(os.sep, "/")}'] = json.load(fh)
                except Exception as e:
                    unparsed.append(f'{f}: {e}')
    return changed, removed, tag_adds, unparsed


def glob_re(pattern):
    return re.compile('^' + ''.join('.*' if c == '*' else '.' if c == '?' else re.escape(c) for c in pattern) + '$')


def qualify(tree, ref):
    return ref if ':' in ref else f'{tree}:{ref}'


def edges(cond, tree, definite, anyof):
    t = cond.get('type')
    if t == 'node':
        definite.add(qualify(tree, cond['id']))
    elif t == 'all':
        for c in cond.get('of', []):
            edges(c, tree, definite, anyof)
    elif t == 'any':
        group = set()
        for c in cond.get('of', []):
            sub_d, sub_a = set(), []
            edges(c, tree, sub_d, sub_a)
            group |= sub_d
            for g in sub_a:
                group |= g
        if group:
            anyof.append(group)


class Model:
    def __init__(self):
        self.pack = load_pack()
        self.namespaces = self.pack['namespaces']
        raw_item_tags = self.pack['tags']['item']
        self.block_tags = resolve_tags(self.pack['tags']['block'], {})
        base = {rid: j for rid, j in self.pack['recipes'].items() if condition_ok(j, self.namespaces)}
        pre_tags = resolve_tags(raw_item_tags, {})
        self.changed, self.removed, tag_adds, self.unparsed = kubejs(base, pre_tags)
        extra = {k.split(':', 1)[1]: v for k, v in tag_adds.items() if k.startswith('item:')}
        self.tags = resolve_tags(raw_item_tags, extra)
        recipes = {rid: j for rid, j in base.items() if rid not in set(self.removed)}
        recipes.update(self.changed)
        self.recipes = {}
        for rid, j in recipes.items():
            if isinstance(j, dict) and 'type' in j:
                self.recipes[rid] = recipe_facts(rid, j, self.tags)
        self.by_out = defaultdict(set)
        self.by_first = defaultdict(set)
        self.by_in = defaultdict(set)
        for rid, f in self.recipes.items():
            for o in f['outs']:
                self.by_out[o].add(rid)
            for o in f['first']:
                self.by_first[o].add(rid)
            for i in f['flat']:
                self.by_in[i].add(rid)
        self.load_trees()
        self.resolve()

    def load_trees(self):
        self.nodes = {}
        self.groups = {}
        for fn in os.listdir(os.path.join(RES, 'groups')):
            with open(os.path.join(RES, 'groups', fn), encoding='utf8') as fh:
                self.groups[fn[:-5]] = json.load(fh)
        for tree in TREES:
            with open(os.path.join(RES, 'trees', tree + '.json'), encoding='utf8') as fh:
                data = json.load(fh)
            for n in data['nodes']:
                n['tree'] = tree
                self.nodes[f"{tree}:{n['id']}"] = n
        self.definite, self.anyof = {}, {}
        for key, n in self.nodes.items():
            d, a = set(), []
            for c in n.get('requires', []):
                edges(c, n['tree'], d, a)
            self.definite[key] = d
            self.anyof[key] = a

    def closure(self, key, lenient):
        seen, todo = set(), [key]
        while todo:
            k = todo.pop()
            nxt = set(self.definite.get(k, ()))
            if lenient:
                for g in self.anyof.get(k, ()):
                    nxt |= g
            for x in nxt:
                if x not in seen and x in self.nodes:
                    seen.add(x)
                    todo.append(x)
        return seen

    def item_match(self, spec, item):
        if spec.startswith('#'):
            return item in self.tags.get(spec[1:], ())
        return glob_re(spec).match(item) is not None

    def select(self, u, memo):
        t = u['type']
        if t == 'group':
            out = set()
            for sub in self.groups.get(u['id'], {}).get('unlocks', []):
                if sub['type'] != 'group':
                    out |= self.select(sub, memo)
            return out
        key = json.dumps(u, sort_keys=True)
        if key in memo:
            return memo[key]
        out = set()
        if t == 'recipe':
            rx = glob_re(u['id'])
            out = {rid for rid in self.recipes if rx.match(rid)}
        elif t == 'recipe_type':
            rx = glob_re(u['id'])
            out = {rid for rid, f in self.recipes.items() if rx.match(f['type'])}
        elif t == 'output':
            out = self.by_spec(u['id'], self.by_first)
        elif t == 'recipes':
            cand = None
            if u.get('output'):
                cand = self.by_spec(u['output'], self.by_first)
            if u.get('input'):
                ins = self.by_spec(u['input'], self.by_in)
                cand = ins if cand is None else cand & ins
            if cand is None:
                cand = set(self.recipes)
            if u.get('recipeType'):
                rx = glob_re(u['recipeType'])
                cand = {rid for rid in cand if rx.match(self.recipes[rid]['type'])}
            out = cand
        elif t == 'mod':
            out = {rid for rid, f in self.recipes.items() if rid.split(':')[0] == u['id'] or any(o.split(':')[0] == u['id'] for o in f['first'])}
        memo[key] = out
        return out

    def by_spec(self, spec, index):
        if spec.startswith('#'):
            items = self.tags.get(spec[1:], ())
            return {rid for i in items for rid in index.get(i, ())}
        if '*' not in spec and '?' not in spec:
            return set(index.get(spec, ()))
        rx = glob_re(spec)
        return {rid for i, rids in index.items() if rx.match(i) for rid in rids}

    def block_match(self, spec, block):
        if spec.startswith('#'):
            return block in self.block_tags.get(spec[1:], ())
        return glob_re(spec).match(block) is not None

    def resolve(self):
        memo = {}
        base = set()
        for u in [{'type': 'group', 'id': 'basics'}]:
            base |= self.select(u, memo)
        self.baseline = base
        self.sel = {}
        self.sel_blocks = {}
        for key, n in self.nodes.items():
            rs, bl = set(), []
            for u in n.get('unlocks', []):
                rs |= self.select(u, memo)
                if u['type'] == 'block':
                    bl.append(u['id'])
                elif u['type'] == 'group':
                    bl += [x['id'] for x in self.groups.get(u['id'], {}).get('unlocks', []) if x['type'] == 'block']
            self.sel[key] = rs - base
            self.sel_blocks[key] = bl
        self.rnodes = defaultdict(set)
        for key, rs in self.sel.items():
            for rid in rs:
                self.rnodes[rid].add(key)
        self.load_world()
        self.free_items = set()
        produced = set(self.by_out)
        changed = True
        free_recipes = set()
        while changed:
            changed = False
            for rid, f in self.recipes.items():
                if rid in free_recipes or rid in self.rnodes or not self.free_type(f):
                    continue
                if all(o in self.hard for o in f['outs']):
                    continue
                if all(any(i not in produced or i in self.free_items for i in s) for s in f['slots']):
                    free_recipes.add(rid)
                    for o in f['outs']:
                        if o not in self.free_items:
                            self.free_items.add(o)
                    changed = True
        self.produced = produced

    def free_type(self, f):
        if f['type'] in ('minecraft:crafting_shaped', 'minecraft:crafting_shapeless', 'minecraft:stonecutting', 'minecraft:smithing_transform', 'minecraft:smithing_trim'):
            return True
        return f['type'] in ('minecraft:smelting', 'minecraft:blasting', 'minecraft:smoking', 'minecraft:campfire_cooking') and f['id'].split(':')[0] in ('minecraft', 'create')

    def is_free(self, item):
        return item not in self.produced or item in self.free_items or item in self.world

    def load_world(self):
        self.world = {'minecraft:coal', 'minecraft:charcoal'}
        self.hard = set()
        for tag, items in self.tags.items():
            if re.match(r'c:(ingots|nuggets|plates|storage_blocks|rods|gears|wires|dusts)/', tag):
                self.hard |= items
            if re.match(r'c:(raw_materials|ores|gems|slimeballs|ender_pearls|rods|crops|seeds|foods|nuggets/netherite|dusts/redstone|dusts/glowstone|stones|cobblestones|sands|gravels|netherracks|obsidians)', tag):
                self.world |= items

    def need(self, slot, node, allow_self):
        if any(self.is_free(i) for i in slot):
            return None
        producers = set()
        for i in slot:
            for rid in self.by_out.get(i, ()):
                producers |= self.rnodes.get(rid, set())
        producers -= IGNORE_PRODUCERS
        if not producers:
            return None
        anc = self.closure(node, True)
        if allow_self:
            anc = anc | {node}
        if producers & anc:
            return None
        lvl = min(self.nodes[p]['level'] for p in producers)
        kind = 'SOFTLOCK' if lvl > self.nodes[node]['level'] else 'NEEDS-REQ'
        return kind, producers


def mark(node):
    return '*' if node in OWNER else ' '


def short(slot):
    items = sorted(slot)
    return items[0] if len(items) == 1 else f'{items[0]}+{len(items) - 1}'


def producers_text(m, producers):
    return ', '.join(f"{p}(L{m.nodes[p]['level']})" for p in sorted(producers, key=lambda p: m.nodes[p]['level']))


def task_slots(m, task):
    kind = task['type']
    if kind == 'deposit':
        specs = ([task['item']] if task.get('item') else []) + task.get('items', [])
    elif kind == 'craft':
        specs = [task['output']]
    else:
        return []
    out = []
    for s in specs:
        items = set(m.tags.get(s[1:], ())) if s.startswith('#') else {s}
        out.append((s, items))
    return out


def report_a(m):
    lines = []
    for key in m.nodes:
        direct = m.definite[key]
        for x in sorted(direct):
            for y in direct:
                if x != y and x in m.closure(y, False):
                    lines.append(f'{mark(key)} {key}: requires {x} but {y} already requires it')
                    break
        if len(m.definite[key]) != len(set(m.definite[key])):
            lines.append(f'{key}: duplicate')
        for x in m.definite[key]:
            if x not in m.nodes:
                lines.append(f'{key}: unknown requirement {x}')
    return lines


def report_b(m):
    soft, req = [], []
    for key, n in m.nodes.items():
        groups = defaultdict(list)
        for rid in sorted(m.sel[key]):
            groups[tuple(m.recipes[rid]['first'])].append(rid)
        seen = set()
        for first, rids in groups.items():
            attempts = []
            for rid in rids:
                issues = []
                for slot in m.recipes[rid]['slots']:
                    res = m.need(slot, key, True)
                    if res:
                        issues.append((slot, res))
                if not issues:
                    attempts = []
                    break
                attempts.append((len(issues), rid, issues))
            if not attempts:
                continue
            attempts.sort(key=lambda x: (x[0], x[1]))
            _, rid, issues = attempts[0]
            for slot, res in issues:
                if (short(slot), res[0]) in seen:
                    continue
                seen.add((short(slot), res[0]))
                line = f"{mark(key)} {key} L{n['level']}: {rid} needs {short(slot)} <- {producers_text(m, res[1])}"
                (soft if res[0] == 'SOFTLOCK' else req).append(line)
        for task in n.get('tasks', []):
            for spec, items in task_slots(m, task):
                res = m.need(items, key, False)
                if not res:
                    if any(m.is_free(i) for i in items):
                        continue
                    own = {key} == {p for i in items for rid in m.by_out.get(i, ()) for p in m.rnodes.get(rid, ())}
                    if not own:
                        continue
                    res = ('SOFTLOCK', {key})
                line = f"{mark(key)} {key} L{n['level']}: task {task['type']} {spec} <- {producers_text(m, res[1])}"
                (soft if res[0] == 'SOFTLOCK' else req).append(line)
    return soft, req


def report_c(m):
    lines = []
    for key, n in m.nodes.items():
        refs = set(m.definite[key])
        for g in m.anyof[key]:
            refs |= g
        for r in sorted(refs):
            if r in m.nodes and m.nodes[r]['level'] > n['level']:
                lines.append(f"{mark(key)} {key} L{n['level']} requires {r} L{m.nodes[r]['level']}")
        if n['level'] < 1:
            lines.append(f'{key}: level {n["level"]}')
    for key, n in m.nodes.items():
        lvls = set()
        for rid in m.sel[key]:
            for slot in m.recipes[rid]['slots']:
                if any(m.is_free(i) for i in slot):
                    continue
                prods = {p for i in slot for r in m.by_out.get(i, ()) for p in m.rnodes.get(r, ())}
                if prods and prods & (m.closure(key, True) | {key}) and min(m.nodes[p]['level'] for p in prods & (m.closure(key, True) | {key})) > n['level']:
                    lvls.add((short(slot), tuple(sorted(prods & (m.closure(key, True) | {key})))))
        for item, prods in sorted(lvls):
            lines.append(f"{mark(key)} {key} L{n['level']} ingredient {item} from {', '.join(f'{p}(L{m.nodes[p]['level']})' for p in prods)}")
    return lines


def report_e(m):
    known = set(m.pack['items']) | set(m.pack['blocks']) | set(m.by_out) | set(m.by_in)
    for k in m.pack['lang']:
        mm = re.match(r'(?:item|block)\.([^.]+)\.(.+)$', k)
        if mm:
            known.add(f"{mm.group(1)}:{mm.group(2).replace('.', '/')}")
    lines = []

    def check(key, spec, what):
        if spec.startswith('#'):
            if not m.tags.get(spec[1:]) and spec[1:] not in m.block_tags:
                lines.append(f'{key}: {what} tag {spec} is unknown or empty')
        elif '*' in spec or '?' in spec:
            rx = glob_re(spec)
            if not any(rx.match(i) for i in known):
                lines.append(f'{key}: {what} {spec} matches nothing')
        elif spec not in known:
            lines.append(f'{key}: {what} {spec} is not in the pack')
    for key, n in m.nodes.items():
        for u in n.get('unlocks', []):
            if u['type'] in ('output', 'block'):
                check(key, u['id'], 'unlock ' + u['type'])
            elif u['type'] == 'recipes':
                for f in ('input', 'output'):
                    if u.get(f) and not (u[f].startswith('*:') and False):
                        check(key, u[f], 'recipes ' + f)
        for t in n.get('tasks', []):
            specs = [t.get('item'), t.get('block'), t.get('output')] + t.get('items', []) + t.get('blocks', [])
            for spec in specs:
                if spec:
                    check(key, spec, 'task ' + t['type'])
        if n.get('icon') and n['icon'] not in known:
            lines.append(f"{key}: icon {n['icon']} is not in the pack")
        if not any(m.sel[key]) and not m.sel_blocks[key]:
            lines.append(f'{key}: unlocks select no recipe and no block')
    return lines


def report_f(m):
    bands = [(1, 1, 10, 25, 1, 3), (2, 5, 30, 200, 5, 20), (6, 10, 200, 600, 20, 45), (11, 15, 600, 1500, 45, 90), (16, 20, 1500, 3000, 90, 180), (21, 25, 3000, 5000, 180, 240)]
    lines = []
    for key, n in m.nodes.items():
        if n['tree'] != 'technology':
            continue
        mt = re.match(r'(?:(\d+)h)?(?:(\d+)m)?$', n['time'])
        minutes = int(mt.group(1) or 0) * 60 + int(mt.group(2) or 0)
        cap = 10000 if n['level'] <= 6 else 50000
        if n['cost'] > cap:
            lines.append(f"{mark(key)} {key} L{n['level']}: cost {n['cost']} above cap {cap}")
        for lo, hi, c0, c1, t0, t1 in bands:
            if lo <= n['level'] <= hi and n['cost'] > 0 and not (c0 <= n['cost'] <= c1 and t0 <= minutes <= t1):
                lines.append(f"{mark(key)} {key} L{n['level']}: cost {n['cost']} time {n['time']} outside band {c0}-{c1} / {t0}-{t1}m")
    return lines


def age_of(m, item, memo, depth=0):
    if item in memo:
        return memo[item]
    memo[item] = 0
    name = item.split(':')[1]
    rank = 0
    if re.search(r'brass|electron_tube|precision|sturdy|rose_quartz|mechanical_arm|crafter|deployer|railway|track|train|smart|rotation_speed|sequenced|stock|package|display|nixie|redstone|pulse|latch|content_obs|stockpile|clockwork|elevator|filter|schematic|chain_conveyor', name):
        rank = 2
    elif re.search(r'copper|fluid|pipe|pump|valve|spout|drain|hose|steam|backtank|diving|tank|blaze|portable_fluid|potato', name):
        rank = 1
    if depth < 3:
        best = None
        for rid in m.by_first.get(item, ()):
            val = 0
            for slot in m.recipes[rid]['slots']:
                val = max(val, min(age_of(m, i, memo, depth + 1) for i in slot))
            best = val if best is None else min(best, val)
        if best is not None:
            rank = max(rank, best)
    memo[item] = rank
    return rank


DECO = re.compile(r'(_pillar$|_slab$|_stairs$|_wall$|_brick$|_bricks$|_tile$|_tiles$|^cut_|^polished_cut|^layered_|_window$|_window_pane$|_pane$|scoria|scorchia|^crimsite|^ochrum|^veridium|^asurine|limestone|^tuff|shingle|framed_glass|ornate|tiled_glass|_door$|_ladder$|scaffolding|_bars$|dye|_sail$|window|copycat|table_cloth|lamp|nixie_tube|deepslate_|dripstone|calcite|granite|diorite|andesite_pillar|^rose_quartz_)')
EXEMPT = {
    'goods': re.compile(r'^(experience_block|experience_nugget|potion|tree_fertilizer)$'),
    'foreign metal': re.compile(r'^crushed_raw_(aluminum|osmium|platinum|quicksilver|uranium)$'),
}


def report_d(m, namespaces):
    gated = set()
    for key in m.nodes:
        gated |= m.sel[key]
    block_specs = [b for key in m.nodes for b in m.sel_blocks[key]]
    out = {}
    for ns in namespaces:
        items = {b for b in m.pack['blocks'] if b.startswith(ns + ':')}
        for k in m.pack['lang']:
            mm = re.match(r'(?:item|block)\.([^.]+)\.([^.]+)$', k)
            if mm and mm.group(1) == ns:
                items.add(f'{ns}:{mm.group(2)}')
        items |= {o for o in m.by_out if o.startswith(ns + ':')}
        uncovered, norecipe = [], []
        for item in sorted(items):
            recs = m.by_out.get(item, set())
            if recs and any(r in gated for r in recs):
                continue
            if not recs:
                if any(m.block_match(s, item) for s in block_specs):
                    continue
                norecipe.append(item)
                continue
            if any(m.block_match(s, item) for s in block_specs):
                continue
            uncovered.append(item)
        out[ns] = (uncovered, norecipe)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ns', default='create,create_connected,create_factory_logistics,sliceanddice,createvintageneoforged,create_hypertube,create_jetpack,create_train_parts')
    ap.add_argument('--section', default='abcdef')
    ap.add_argument('--all', action='store_true')
    a = ap.parse_args()
    m = Model()
    print(f'recipes {len(m.recipes)} (kubejs removed {len(m.removed)}, added or changed {len(m.changed)}), nodes {len(m.nodes)}, tags {len(m.tags)}')
    print(f'unparsed kubejs: {len(m.unparsed)}')
    for u in m.unparsed:
        print('  ?', u[:200])
    if 'a' in a.section:
        r = report_a(m)
        print(f'\n(a) redundant requirements: {len(r)}')
        print('\n'.join(r))
    if 'b' in a.section:
        soft, req = report_b(m)
        print(f'\n(b) soft locks: {len(soft)}')
        print('\n'.join(soft))
        print(f'\n(b2) needs requirement (producer at lower level, not an ancestor): {len(req)}')
        print('\n'.join(req))
    if 'c' in a.section:
        r = report_c(m)
        print(f'\n(c) level and order problems: {len(r)}')
        print('\n'.join(r))
    if 'e' in a.section:
        r = report_e(m)
        print(f'\n(e) unknown ids: {len(r)}')
        print('\n'.join(r))
    if 'f' in a.section:
        r = report_f(m)
        print(f'\n(f) cost and time outside the level bands: {len(r)}')
        print('\n'.join(r))
    if 'd' in a.section:
        res = report_d(m, a.ns.split(','))
        mem = {}
        for ns, (unc, nor) in res.items():
            print(f'\n(d) {ns}: uncovered {len(unc)}, without recipe {len(nor)}')
            groups = defaultdict(list)
            for i in unc:
                name = i.split(':')[1]
                label = next((k for k, rx in EXEMPT.items() if rx.match(name)), None)
                if label is None and DECO.search(name):
                    label = 'deco'
                groups['exempt ' + label if label else ['andesite', 'copper', 'brass'][age_of(m, i, mem)]].append(i)
            real = sum(len(v) for k, v in groups.items() if not k.startswith('exempt'))
            print(f'  needs a node: {real}')
            for age in ('andesite', 'copper', 'brass', 'exempt goods', 'exempt foreign metal', 'exempt deco'):
                items = groups.get(age, [])
                if items:
                    exempt = age.startswith('exempt')
                    shown = items if (a.all or not exempt) else items[:12]
                    print(f'  {age} ({len(items)}): ' + ' '.join(i.split(":")[1] for i in shown) + (' ...' if len(shown) < len(items) else ''))
            if nor and a.all:
                print('  no recipe: ' + ' '.join(i.split(':')[1] for i in nor))


if __name__ == '__main__':
    main()
