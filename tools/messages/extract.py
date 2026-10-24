#!/usr/bin/env python3
"""Writes src/main/resources/messages.yml from the message keys and default texts in the Java sources.

Every text the plugin shows is looked up as messages.format("key", "default", ...), name(...), lore(...), and so on.
This collects those pairs (including both sides of `cond ? "a" : "b"` keys) so messages.yml always lists every key
with its default. Run from the repository root:  python3 tools/messages/extract.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCES = ROOT / "src/main/java"
TARGET = ROOT / "src/main/resources/messages.yml"

STRING = r'"((?:[^"\\]|\\.)*)"'
CALL = re.compile(r'\b(?:msg|format|raw|name|lore|component|sendRaw|sendFormatted|sendComponent|ask)\(\s*(?:[A-Za-z_][\w.()]*\s*,\s*)?'
                  + STRING + r'\s*,\s*' + STRING)
TERNARY = re.compile(r'\b(?:msg|format|raw|name|lore|component|sendRaw|sendFormatted)\(\s*(?:[A-Za-z_][\w.()]*\s*,\s*)?[^,"?;]+\?\s*'
                     + STRING + r'\s*:\s*' + STRING + r'\s*,\s*[^,"?;]+\?\s*' + STRING + r'\s*:\s*' + STRING)
ARRAY = re.compile(r'\{\s*' + STRING + r'\s*,\s*' + STRING + r'\s*\}')
KEY = re.compile(r'^[a-z][a-z0-9-]*(\.[a-z0-9][a-z0-9-]*)+$|^prefix$|^unknown-player$')

HEADER = """# ┌──────────────────────────────────────────────────────────────────────────┐
# │  DonutOrders 2 · messages.yml                                            │
# │  Every text players see. /orderadmin reload applies changes.             │
# └──────────────────────────────────────────────────────────────────────────┘
#
# Formatting: MiniMessage (<gold>, <#49d6a0>, <gradient:#49d6a0:#ffc24b>text</gradient>, <b>), legacy & codes and
# &#RRGGBB hex, mixed freely. %placeholders% are filled in by the plugin; player-made text (names, searches) can never
# add formatting. Menu titles and button names are written in small capitals when style.small-caps is on.
# Missing keys fall back to the built-in English text, so this file can be trimmed to what you change.

"""

SECTIONS = {
    "prefix": "Put in front of chat messages.",
    "general": "General",
    "command": "Commands",
    "help": "/orders help",
    "input": "Questions asked in chat",
    "create": "Creating orders",
    "deliver": "Delivering",
    "sell": "/orders sell",
    "price": "/orders price",
    "collect": "Collecting",
    "mail": "Mailbox",
    "cancel": "Cancelling",
    "edit": "Changing an order",
    "alerts": "/order alerts",
    "notify": "Notifications to buyers",
    "broadcast": "New-order announcement",
    "restrictions": "Rules",
    "admin": "Staff",
    "update": "Update check",
    "gui": "Menus",
    "category": "Categories",
    "sort": "Board sorts",
    "status": "Order states",
    "time": "Durations",
}


def unescape(text):
    return bytes(text, "utf-8").decode("unicode_escape").encode("latin-1").decode("utf-8")


def collect():
    found = {}
    for file in sorted(SOURCES.rglob("*.java")):
        source = file.read_text(encoding="utf-8")
        flat = re.sub(r'"\s*\+\s*\n\s*"', "", source)
        for match in TERNARY.finditer(flat):
            for key, default in ((match.group(1), match.group(3)), (match.group(2), match.group(4))):
                if KEY.match(key):
                    found.setdefault(key, unescape(default))
        for match in CALL.finditer(flat):
            key, default = match.group(1), match.group(2)
            if KEY.match(key):
                found.setdefault(key, unescape(default))
        for match in ARRAY.finditer(flat):
            key, default = match.group(1), match.group(2)
            if KEY.match(key) and key.split(".")[0] in SECTIONS:
                found.setdefault(key, unescape(default))
    return found


def extra():
    """Keys built at run time: categories, sorts, statuses, time units and order-kind hints."""
    keys = {}
    for name in ["all", "blocks", "tools", "food", "combat", "potions", "books", "ingredients", "utilities", "spawners"]:
        keys["category." + name] = name.capitalize()
    for name, text in [("money-per-item", "Money per item"), ("recently-listed", "Recently listed"), ("most-delivered", "Most delivered"),
                       ("most-paid", "Most paid"), ("ending-soon", "Ending soon")]:
        keys["sort." + name] = text
    for name in ["active", "filled", "cancelled", "expired"]:
        keys["status." + name] = {"active": "Open", "filled": "Filled", "cancelled": "Cancelled", "expired": "Expired"}[name]
    for unit in ["minute", "hour", "day"]:
        keys["time." + unit] = "%count% " + unit
        keys["time." + unit + "s"] = "%count% " + unit + "s"
    keys["time.short-day"] = "d"
    keys["time.short-hour"] = "h"
    keys["time.short-minute"] = "m"
    keys["time.expired"] = "ended"
    keys["gui.create.mode-type"] = "<dark_gray>Any plain %item% counts"
    keys["gui.create.mode-book"] = "<dark_gray>Books with exactly this enchantment count"
    keys["gui.create.mode-exact"] = "<dark_gray>Only this exact item counts"
    keys["prefix"] = "<#49d6a0><b>Orders</b> <dark_gray>» <reset>"
    keys["unknown-player"] = "Unknown"
    keys["general.server-name"] = "Server"
    keys["input.cancel-word"] = "cancel"
    return keys


def quote(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main():
    keys = collect()
    keys.update(extra())
    tree = {}
    for key in sorted(keys):
        node = tree
        parts = key.split(".")
        for part in parts[:-1]:
            child = node.setdefault(part, {})
            if not isinstance(child, dict):
                sys.exit(f"{key}: '{part}' is both a text and a section")
            node = child
        if isinstance(node.get(parts[-1]), dict):
            sys.exit(f"{key} is both a text and a section")
        node[parts[-1]] = keys[key]

    order = list(SECTIONS)
    lines = [HEADER.rstrip("\n"), ""]

    def write(node, indent):
        for name in node:
            value = node[name]
            if isinstance(value, dict):
                lines.append("  " * indent + name + ":")
                write(value, indent + 1)
            else:
                lines.append("  " * indent + name + ": " + quote(value))

    for section in sorted(tree, key=lambda name: order.index(name) if name in order else len(order)):
        value = tree[section]
        lines.append("# " + "─" * 3 + " " + SECTIONS.get(section, section) + " " + "─" * max(3, 70 - len(SECTIONS.get(section, section))))
        if isinstance(value, dict):
            lines.append(section + ":")
            write(value, 1)
        else:
            lines.append(section + ": " + quote(value))
        lines.append("")
    TARGET.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {len(keys)} messages to {TARGET.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
