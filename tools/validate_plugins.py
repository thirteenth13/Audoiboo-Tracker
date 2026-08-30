#!/usr/bin/env python3
import hashlib
import json
import sys
import zipfile
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
PLUGINS = ROOT / "plugins"
PACKAGES = PLUGINS / "packages"
CATALOG = PLUGINS / "catalog.json"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def load_json_bytes(data: bytes, label: str):
    try:
        return json.loads(data.decode("utf-8"))
    except Exception as exc:
        fail(f"{label}: invalid JSON/UTF-8: {exc}")


def validate_archive(path: Path):
    if not zipfile.is_zipfile(path):
        fail(f"{path}: not a valid ZIP/.abplugin archive")
    try:
        with zipfile.ZipFile(path) as zf:
            bad = zf.testzip()
            if bad:
                fail(f"{path}: corrupt member {bad}")
            names = set(zf.namelist())
            if "plugin.json" not in names:
                fail(f"{path}: plugin.json missing at archive root")
            manifest = load_json_bytes(zf.read("plugin.json"), f"{path}:plugin.json")
            for required in ("id", "name", "version", "apiVersion", "hosts"):
                if required not in manifest:
                    fail(f"{path}: manifest field {required!r} missing")
            for name, entry in manifest.get("entrypoints", {}).items():
                if not isinstance(entry, str) or entry not in names:
                    fail(f"{path}: entrypoint {name!r} -> {entry!r} missing from archive")
            return manifest
    except zipfile.BadZipFile as exc:
        fail(f"{path}: invalid ZIP: {exc}")


def main() -> None:
    archives = sorted(PACKAGES.glob("*.abplugin"))
    if not archives:
        fail("no .abplugin packages found")

    manifests = {}
    for archive in archives:
        manifest = validate_archive(archive)
        key = (manifest["id"], int(manifest["version"]))
        manifests[key] = archive
        print(f"OK ZIP: {archive.relative_to(ROOT)} ({manifest['id']} v{manifest['version']})")

    # Every source plugin must have a package matching its current manifest version.
    for source in sorted(PLUGINS.iterdir()):
        manifest_path = source / "plugin.json"
        if not source.is_dir() or source.name == "packages" or not manifest_path.is_file():
            continue
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        expected = (manifest["id"], int(manifest["version"]))
        if expected not in manifests:
            fail(f"{source}: package for {expected[0]} v{expected[1]} missing")

    # Catalog hashes must match local package bytes whenever the URL names a local package.
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    for item in catalog.get("plugins", []):
        filename = Path(urlparse(item["url"]).path).name
        package = PACKAGES / filename
        if not package.is_file():
            fail(f"catalog: package {filename} not found")
        actual = hashlib.sha256(package.read_bytes()).hexdigest()
        expected = str(item.get("sha256", "")).lower()
        if actual != expected:
            fail(f"catalog: SHA-256 mismatch for {filename}: expected {expected}, got {actual}")
        print(f"OK SHA: {filename} {actual}")

    print(f"Validated {len(archives)} plugin package(s).")


if __name__ == "__main__":
    main()
