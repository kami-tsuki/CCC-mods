import argparse
import hashlib
import json
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

ALWAYS_IGNORED = ["minecraft/logs", "minecraft/crash-reports", "minecraft/.cache"]
STORED = {".jar", ".zip", ".png", ".ogg"}
API = "https://discord.com/api/v10"
LIMITS = {0: 10, 1: 10, 2: 50, 3: 100}


def read_cfg(path):
    cfg = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("["):
            key, value = line.split("=", 1)
            cfg.setdefault(key.strip(), value.strip())
    return cfg


def set_export_version(path, version):
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"(?m)^ExportVersion=.*$", f"ExportVersion={version}", text) if "ExportVersion=" in text \
        else text.replace("[General]\n", f"[General]\nExportVersion={version}\n", 1)
    path.write_text(text, encoding="utf-8")


def read_toml(path):
    data = {}
    section = data
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("["):
            section = data.setdefault(line.strip("[]"), {})
            continue
        if "=" in line:
            key, value = (p.strip() for p in line.split("=", 1))
            section[key] = value[1:-1] if value[:1] in "'\"" and value[-1:] == value[:1] else value
    return data


def load_mods(mc):
    mods = {}
    index = mc / "mods" / ".index"
    for toml in sorted(index.glob("*.pw.toml")) if index.is_dir() else []:
        meta = read_toml(toml)
        jar = mc / "mods" / meta.get("filename", "")
        if not jar.is_file():
            continue
        mods[f"minecraft/mods/{jar.name}"] = {
            "file": jar,
            "side": meta.get("side", ""),
            "url": meta.get("download", {}).get("url", ""),
            "modrinth": "update.modrinth" in meta,
            "cf": meta.get("update.curseforge"),
        }
    return mods


def walk(root, ignored):
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        if any(rel == i or rel.startswith(i + "/") for i in ignored):
            continue
        yield rel, path


def add(zf, path, arcname):
    zf.write(path, arcname, compress_type=zipfile.ZIP_STORED if path.suffix.lower() in STORED else zipfile.ZIP_DEFLATED)


def env(side):
    return {"client": "unsupported" if side == "server" else "required",
            "server": "unsupported" if side == "client" else "required"}


def digest(path, algo):
    h = hashlib.new(algo)
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def export_mrpack(out, files, mods, pack):
    linked = {rel: m for rel, m in mods.items() if m["modrinth"] and m["url"].startswith("https://cdn.modrinth.com/")}
    index = {
        "formatVersion": 1, "game": "minecraft", "versionId": pack["version"], "name": pack["name"], "summary": pack["summary"],
        "files": [{
            "path": rel.removeprefix("minecraft/"),
            "hashes": {"sha1": digest(m["file"], "sha1"), "sha512": digest(m["file"], "sha512")},
            "env": env(m["side"]), "downloads": [m["url"]], "fileSize": m["file"].stat().st_size,
        } for rel, m in linked.items()],
        "dependencies": {"minecraft": pack["mc"], "neoforge": pack["loader"]},
    }
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("modrinth.index.json", json.dumps(index, indent=2))
        for rel, path in files:
            if rel.startswith("minecraft/") and rel not in linked:
                add(zf, path, "overrides/" + rel.removeprefix("minecraft/"))
    return len(linked)


def export_curseforge(out, files, mods, pack):
    linked = {rel: m for rel, m in mods.items() if m["cf"]}
    manifest = {
        "minecraft": {"version": pack["mc"], "modLoaders": [{"id": f"neoforge-{pack['loader']}", "primary": True}]},
        "manifestType": "minecraftModpack", "manifestVersion": 1,
        "name": pack["name"], "version": pack["version"], "author": pack["author"],
        "files": [{"projectID": int(m["cf"]["project-id"]), "fileID": int(m["cf"]["file-id"]), "required": True} for m in linked.values()],
        "overrides": "overrides",
    }
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("manifest.json", json.dumps(manifest, indent=2))
        for rel, path in files:
            if rel.startswith("minecraft/") and rel not in linked:
                add(zf, path, "overrides/" + rel.removeprefix("minecraft/"))
    return len(linked)


def export_prism(out, files):
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        for rel, path in files:
            add(zf, path, rel)


def read_jsonc(path):
    return json.loads("\n".join(l for l in path.read_text(encoding="utf-8").splitlines() if not l.strip().startswith("//")))


def request(token, method, url, body=None, content_type="application/json"):
    headers = {"Authorization": f"Bot {token}", "User-Agent": "DiscordBot (https://github.com/kami-tsuki, 1.0)"}
    if body is not None:
        headers["Content-Type"] = content_type
    for _ in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, body, headers, method=method), timeout=600) as r:
                return json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise RuntimeError(f"HTTP {e.code}: {e.read().decode(errors='replace')[:300]}") from None
            time.sleep(float(json.loads(e.read()).get("retry_after", 1)) + 0.5)
    raise RuntimeError("rate limited")


def post(token, channel, content, file=None):
    payload = json.dumps({"content": content, "allowed_mentions": {"parse": []}})
    if file is None:
        return request(token, "POST", f"{API}/channels/{channel}/messages", payload.encode())
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n{payload}\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"{file.name}\"\r\n"
            f"Content-Type: application/octet-stream\r\n\r\n").encode() + file.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    return request(token, "POST", f"{API}/channels/{channel}/messages", body, f"multipart/form-data; boundary={boundary}")


def mb(path):
    return path.stat().st_size / 1048576


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--instance", required=True, type=Path)
    ap.add_argument("--version", required=True)
    ap.add_argument("--discord-config", required=True, type=Path)
    ap.add_argument("--channel", required=True)
    ap.add_argument("--out", type=Path)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    root = args.instance
    mc = root / "minecraft" if (root / "minecraft").is_dir() else root / ".minecraft"
    cfg_path = root / "instance.cfg"
    set_export_version(cfg_path, args.version)
    cfg = read_cfg(cfg_path)
    components = {c["uid"]: c["version"] for c in json.loads((root / "mmc-pack.json").read_text(encoding="utf-8"))["components"]}
    pack = {
        "name": cfg.get("ExportName") or cfg.get("name", "Modpack"), "version": args.version,
        "summary": cfg.get("ExportSummary", ""), "author": cfg.get("ExportAuthor") or "kami-tsuki",
        "mc": components["net.minecraft"], "loader": components["net.neoforged"],
    }
    packignore = root / ".packignore"
    ignored = ALWAYS_IGNORED + ([l.strip() for l in packignore.read_text(encoding="utf-8").splitlines() if l.strip()] if packignore.is_file() else [])
    files = list(walk(root, ignored + ["instance.cfg.PsnSOY"]))
    pack_files = [(rel, p) for rel, p in files if not rel.startswith("minecraft/mods/.index/")]
    mods = load_mods(mc)

    out = args.out or Path(__file__).resolve().parent.parent / "build" / "release" / args.version
    out.mkdir(parents=True, exist_ok=True)
    stem = re.sub(r"[^A-Za-z0-9.]+", "-", pack["name"]).strip("-") + "-" + args.version
    mrpack, cf, prism = out / f"{stem}.mrpack", out / f"{stem}-curseforge.zip", out / f"{stem}-prism.zip"

    print(f"Exporting {pack['name']} {args.version} ({len(mods)} indexed mods)...")
    mr_linked = export_mrpack(mrpack, pack_files, mods, pack)
    cf_linked = export_curseforge(cf, pack_files, mods, pack)
    export_prism(prism, files)
    jars = sum(1 for rel, _ in pack_files if rel.startswith("minecraft/mods/") and rel.endswith(".jar"))
    for f in (mrpack, cf, prism):
        print(f"  {f.name}  {mb(f):.1f} MB")
    if args.dry_run:
        return

    discord = read_jsonc(args.discord_config)
    token = discord["token"]
    tier = 0
    try:
        tier = request(token, "GET", f"{API}/guilds/{discord['guildId']}").get("premium_tier", 0)
    except Exception as e:
        print(f"  could not read guild boost tier: {e}")
    limit = LIMITS.get(tier, 10)
    title = f"**{pack['name']}** `{args.version}` · MC {pack['mc']} · NeoForge {pack['loader']}"
    entries = [
        (mrpack, "🟢", "Modrinth", f"{mr_linked}/{jars} mods linked · import in Modrinth App or Prism"),
        (cf, "🟠", "CurseForge", f"{cf_linked}/{jars} mods linked · import in CurseForge App"),
        (prism, "🟣", "Prism", f"full instance · drag into Prism Launcher"),
    ]
    failed = 0
    for path, icon, label, info in entries:
        head = f"{icon} {title}\n**{label}** · {mb(path):.1f} MB · {info}"
        try:
            if mb(path) > limit:
                raise RuntimeError(f"file is {mb(path):.1f} MB, guild upload limit is {limit} MB (boost tier {tier})")
            post(token, args.channel, head, path)
            print(f"  sent {path.name}")
        except Exception as e:
            failed += 1
            print(f"  failed {path.name}: {e}")
            post(token, args.channel, f"❌ {title}\n**{label}** upload failed · `{path.name}`\n```\n{str(e)[:400]}\n```")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
