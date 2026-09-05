#!/usr/bin/env python3
import hashlib
import json
import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PLUGINS = ROOT / "plugins"
PACKAGES = PLUGINS / "packages"
CATALOG = PLUGINS / "catalog.json"
IDS = ("baza-knig", "knigavuhe", "poleknig", "lis10book", "izib")
BASE = "https://raw.githubusercontent.com/thirteenth13/Audoiboo-Tracker/main/plugins/packages"
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def build(plugin_id: str):
    source = PLUGINS / plugin_id
    manifest = json.loads((source / "plugin.json").read_text(encoding="utf-8"))
    version = int(manifest["version"])
    target = PACKAGES / f"{plugin_id}-{version}.abplugin"
    PACKAGES.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for path in sorted(p for p in source.rglob("*") if p.is_file()):
            rel = path.relative_to(source).as_posix()
            info = zipfile.ZipInfo(rel, FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zf.writestr(info, path.read_bytes())
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    return manifest, target, digest


def main():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    by_id = {entry["id"]: entry for entry in catalog["plugins"]}
    for plugin_id in IDS:
        manifest, target, digest = build(plugin_id)
        entry = by_id[plugin_id]
        entry["name"] = manifest["name"]
        entry["version"] = int(manifest["version"])
        entry["apiVersion"] = int(manifest["apiVersion"])
        entry["url"] = f"{BASE}/{target.name}"
        entry["sha256"] = digest
    CATALOG.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
