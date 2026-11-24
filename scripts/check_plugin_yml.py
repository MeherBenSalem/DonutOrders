#!/usr/bin/env python3
"""NightBeam plugin check (convention section 1, plugins).

Reads plugin.yml from the built plugin jar and fails unless:
  - api-version is the quoted string '1.20';
  - folia-supported is true;
  - bstats-id is present and matches the bStats ID the code starts Metrics with (0 means no bStats page yet:
    a GitHub ::warning::, not a failure);
  - bStats is shaded and relocated (no org/bstats classes left in the jar);
  - the main class, and every other class in the jar outside META-INF/versions, is Java 17 bytecode or older
    (major version 61), because Paper 1.20.1 runs on Java 17.

Usage: python3 scripts/check_plugin_yml.py [--jar path/to/plugin.jar] [--src src/main]
Without --jar the newest plugin jar in target/ or build/libs/ is used.
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import struct
import sys
import zipfile

JAVA17_MAJOR = 61
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

errors: list[str] = []
warnings: list[str] = []


def error(msg: str) -> None:
    errors.append(msg)
    print(f"::error title=plugin.yml check::{msg}")


def warning(msg: str) -> None:
    warnings.append(msg)
    print(f"::warning title=plugin.yml check::{msg}")


def find_jar() -> str | None:
    candidates = []
    for pattern in ("target/*.jar", "build/libs/*.jar"):
        for path in glob.glob(os.path.join(ROOT, pattern)):
            name = os.path.basename(path)
            if name.startswith("original-") or name.endswith(("-sources.jar", "-javadoc.jar", "-plain.jar")):
                continue
            try:
                with zipfile.ZipFile(path) as jar:
                    if "plugin.yml" not in jar.namelist():
                        continue
            except zipfile.BadZipFile:
                continue
            candidates.append(path)
    if not candidates:
        return None
    return max(candidates, key=os.path.getmtime)


def top_level(yml: str, key: str) -> str | None:
    match = re.search(rf"^{re.escape(key)}\s*:\s*(.*?)\s*(?:#.*)?$", yml, re.MULTILINE)
    return match.group(1) if match else None


def unquote(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        return value[1:-1]
    return value


def code_bstats_ids(src: str) -> set[int]:
    """Every bStats ID the sources start Metrics with: a BSTATS_ID constant or a literal new Metrics(this, N)."""
    ids: set[int] = set()
    uses_metrics = False
    for path in glob.glob(os.path.join(src, "**", "*.java"), recursive=True) + glob.glob(
        os.path.join(src, "**", "*.kt"), recursive=True
    ):
        with open(path, encoding="utf-8", errors="replace") as handle:
            text = handle.read()
        if re.search(r"\bnew\s+Metrics\s*\(|\bMetrics\s*\(", text) and "bstats" in text.lower():
            uses_metrics = True
        for m in re.finditer(r"\bBSTATS_(?:PLUGIN_)?ID\s*(?::\s*Int\s*)?=\s*(\d+)", text):
            ids.add(int(m.group(1)))
        for m in re.finditer(r"\bnew\s+Metrics\s*\(\s*[\w.]+\s*,\s*(\d+)\s*\)", text):
            ids.add(int(m.group(1)))
    if not uses_metrics:
        error(f"No bStats Metrics found in {os.path.relpath(src, ROOT)}: the plugin must start bStats in onEnable")
    return ids


def class_major(data: bytes) -> int:
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        return -1
    return struct.unpack(">H", data[6:8])[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", help="plugin jar (default: newest in target/ or build/libs/)")
    parser.add_argument("--src", default=os.path.join(ROOT, "src", "main"), help="sources to scan for the bStats ID")
    args = parser.parse_args()

    jar_path = args.jar or find_jar()
    if not jar_path or not os.path.isfile(jar_path):
        error("No built plugin jar found in target/ or build/libs/; build the plugin first")
        return 1
    print(f"Checking {os.path.relpath(jar_path, ROOT)}")

    with zipfile.ZipFile(jar_path) as jar:
        names = jar.namelist()
        if "plugin.yml" not in names:
            error("plugin.yml is missing from the jar")
            return 1
        yml = jar.read("plugin.yml").decode("utf-8", errors="replace")

        api = top_level(yml, "api-version")
        if api is None:
            error("plugin.yml has no api-version; it must be '1.20'")
        elif api not in ("'1.20'", '"1.20"'):
            error(f"plugin.yml api-version is {api}; it must be the quoted string '1.20'")
        else:
            print("api-version: '1.20' OK")

        folia = top_level(yml, "folia-supported")
        if (folia or "").lower() != "true":
            error(f"plugin.yml folia-supported is {folia!r}; it must be true")
        else:
            print("folia-supported: true OK")

        raw_id = top_level(yml, "bstats-id")
        yml_id = None
        if raw_id is None:
            error("plugin.yml has no bstats-id key (use bstats-id: 0 until the plugin has a bStats page)")
        else:
            try:
                yml_id = int(unquote(raw_id))
            except ValueError:
                error(f"plugin.yml bstats-id {raw_id!r} is not a number")
        ids = code_bstats_ids(args.src)
        if yml_id is not None:
            if len(ids) > 1:
                error(f"The sources use more than one bStats ID: {sorted(ids)}")
            elif not ids:
                error("Could not read the bStats ID from the sources (expected a BSTATS_ID constant)")
            elif yml_id not in ids:
                error(f"plugin.yml bstats-id is {yml_id} but the code starts Metrics with {sorted(ids)[0]}")
            elif yml_id == 0:
                warning("bStats plugin ID is missing (bstats-id: 0): the owner needs to create the bStats page"
                        " and put its ID in the code and plugin.yml")
            else:
                print(f"bstats-id: {yml_id} OK (matches the code)")

        unrelocated = [n for n in names if n.startswith("org/bstats/") and n.endswith(".class")]
        if unrelocated:
            error("bStats is not relocated: org/bstats classes are in the jar (relocate to <plugin package>.libs.bstats)")
        if ids and not any(n.endswith("bstats/bukkit/Metrics.class") for n in names):
            error("bStats Metrics is not shaded into the jar")

        main_class = unquote(top_level(yml, "main") or "")
        entry = main_class.replace(".", "/") + ".class"
        if not main_class or entry not in names:
            error(f"Main class {main_class or '(none)'} is not in the jar")
        else:
            major = class_major(jar.read(entry))
            if major != JAVA17_MAJOR:
                error(f"Main class {main_class} is class file version {major}; it must be {JAVA17_MAJOR} (Java 17)")
            else:
                print(f"Main class {main_class}: class file version {major} (Java 17) OK")

        too_new = []
        for name in names:
            if not name.endswith(".class") or name.startswith("META-INF/versions/"):
                continue
            major = class_major(jar.read(name))
            if major > JAVA17_MAJOR:
                too_new.append(f"{name} ({major})")
        if too_new:
            error(f"{len(too_new)} class(es) need a newer Java than 17 and break on Paper 1.20.1, e.g. {too_new[:3]}")

    if errors:
        print(f"FAILED: {len(errors)} problem(s)")
        return 1
    print("plugin.yml check passed" + (f" with {len(warnings)} warning(s)" if warnings else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
