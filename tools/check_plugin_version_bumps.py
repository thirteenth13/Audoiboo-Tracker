#!/usr/bin/env python3
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
PLUGINS = ROOT / "plugins"
IDS = ("baza-knig", "knigavuhe", "poleknig", "lis10book", "izib")


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def manifest_version(ref: str, plugin_id: str):
    try:
        raw = git("show", f"{ref}:plugins/{plugin_id}/plugin.json")
    except subprocess.CalledProcessError:
        return None
    return int(json.loads(raw)["version"])


def main() -> int:
    head = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
    try:
        base = sys.argv[2] if len(sys.argv) > 2 else git("rev-parse", f"{head}^")
    except subprocess.CalledProcessError:
        print("No parent commit available; skipping plugin version bump check")
        return 0

    changed = set(git("diff", "--name-only", base, head).splitlines())
    failures = []
    for plugin_id in IDS:
        prefix = f"plugins/{plugin_id}/"
        source_changed = any(path.startswith(prefix) for path in changed)
        if not source_changed:
            continue
        old_version = manifest_version(base, plugin_id)
        new_version = manifest_version(head, plugin_id)
        if old_version is None or new_version is None:
            continue
        if new_version <= old_version:
            failures.append(
                f"{plugin_id}: plugin sources changed but version did not increase "
                f"({old_version} -> {new_version})"
            )

    if failures:
        print("Plugin package version bump required:")
        for failure in failures:
            print(f" - {failure}")
        return 1

    print("Plugin version bump check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
