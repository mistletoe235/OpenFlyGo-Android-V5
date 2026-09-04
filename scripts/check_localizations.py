#!/usr/bin/env python3
"""Validate OpenFly Android locale resources and report visible XML literals."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
VISIBLE_ATTRIBUTES = {"text", "hint", "contentDescription", "title", "summary"}
SOURCE_SINKS = (
    "setText", "text =", "setHint", "hint =", "setTitle", "setMessage",
    "setContentDescription", "contentDescription =", "Toast.makeText", "toast(",
    "showBanner", "appendLog", "log(", "setError", "error =",
    "setPositiveButton", "setNegativeButton", "setNeutralButton", "setItems",
    # Indirect UI state used throughout the flight, survey, HIL, and V86
    # controllers. These strings are eventually rendered by a TextView/dialog
    # even though the assignment itself is not an Android widget call.
    "reject(", "operationMessage", "message =", "label =", "title =", "detail =",
    "reason =", "blockingReason", "status =", "return \"", "-> \"",
    "showPreviewStatus", "showMessage(", "showError(",
    "publish(", "pauseInternal(", "abortInternal(", "cancelAutoTakeoff(",
    "stopFromHil(", "showStatus(", "show(", "stopControl(", "runControl(",
    "runWorker(", "cancelPendingFrameRequest(", "simulatorStatusMessage",
)
CJK = re.compile(r"[\u3400-\u9fff]")
QUOTED_CJK = re.compile(r'"(?:\\.|[^"\\])*[\u3400-\u9fff](?:\\.|[^"\\])*"')
FORMAT = re.compile(r"%(?:\d+\$)?[-+# 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")


def resource_entries(path: Path) -> dict[tuple[str, str], ET.Element]:
    root = ET.parse(path).getroot()
    entries: dict[tuple[str, str], ET.Element] = {}
    for node in root:
        name = node.attrib.get("name")
        if not name or node.attrib.get("translatable") == "false":
            continue
        entries[(node.tag, name)] = node
    return entries


def duplicate_resource_keys(path: Path) -> list[tuple[str, str]]:
    seen: set[tuple[str, str]] = set()
    duplicates: list[tuple[str, str]] = []
    for node in ET.parse(path).getroot():
        name = node.attrib.get("name")
        if not name:
            continue
        key = (node.tag, name)
        if key in seen:
            duplicates.append(key)
        seen.add(key)
    return duplicates


def text_and_placeholders(node: ET.Element) -> tuple[str, ...]:
    text = "".join(node.itertext())
    return tuple(sorted(FORMAT.findall(text)))


def visible_xml_literals(res: Path) -> list[str]:
    findings: list[str] = []
    for path in sorted(res.glob("layout*/**/*.xml")) + sorted(res.glob("menu*/**/*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            findings.append(f"{path}: invalid XML: {exc}")
            continue
        for node in root.iter():
            for attribute in VISIBLE_ATTRIBUTES:
                value = node.attrib.get(ANDROID_NS + attribute)
                if value and not value.startswith("@") and CJK.search(value):
                    findings.append(f"{path.relative_to(res.parent)}: android:{attribute}=\"{value}\"")
    return findings


def visible_source_literals(source_root: Path) -> list[str]:
    findings: list[str] = []
    for path in sorted(source_root.rglob("*.java")) + sorted(source_root.rglob("*.kt")):
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if "localization:ignore" in line:
                continue
            if CJK.search(line) and any(sink in line for sink in SOURCE_SINKS):
                findings.append(f"{path.relative_to(source_root.parent)}:{line_number}: {line.strip()}")
    return findings


def all_source_cjk_literals(source_root: Path) -> list[str]:
    """Reject every remaining CJK string literal in production Java/Kotlin.

    UI rendering can pass through indirect domain, callback, exception, and log
    paths that a sink list will never enumerate completely. App-owned Chinese
    belongs in Android resources; protocol compatibility markers should use an
    explicit escaped representation and comment.
    """
    findings: list[str] = []
    for path in sorted(source_root.rglob("*.java")) + sorted(source_root.rglob("*.kt")):
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if "localization:ignore" in line:
                continue
            if QUOTED_CJK.search(line):
                findings.append(f"{path.relative_to(source_root.parent)}:{line_number}: {line.strip()}")
    return findings


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--strict-hardcoded", action="store_true")
    parser.add_argument("--strict-source", action="store_true")
    parser.add_argument("--strict-all-source", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    res = root / "app" / "src" / "main" / "res"
    default = resource_entries(res / "values" / "strings.xml")
    english = resource_entries(res / "values-en" / "strings.xml")
    errors: list[str] = []
    for path in (res / "values" / "strings.xml", res / "values-en" / "strings.xml"):
        for kind, name in duplicate_resource_keys(path):
            errors.append(f"duplicate resource in {path.parent.name}: {kind}/{name}")
    for key in sorted(default.keys() - english.keys()):
        errors.append(f"missing English resource: {key[0]}/{key[1]}")
    for key in sorted(english.keys() - default.keys()):
        errors.append(f"English-only resource: {key[0]}/{key[1]}")
    for key in sorted(default.keys() & english.keys()):
        zh_formats = text_and_placeholders(default[key])
        en_formats = text_and_placeholders(english[key])
        if zh_formats != en_formats:
            errors.append(f"placeholder mismatch {key[0]}/{key[1]}: {zh_formats} != {en_formats}")

    hardcoded = visible_xml_literals(res)
    source_hardcoded = visible_source_literals(root / "app" / "src" / "main" / "java")
    all_source_hardcoded = all_source_cjk_literals(root / "app" / "src" / "main" / "java")
    print(f"locale parity: zh={len(default)} en={len(english)}")
    print(f"visible CJK XML literals remaining: {len(hardcoded)}")
    for finding in hardcoded[:40]:
        print(f"  {finding}")
    if len(hardcoded) > 40:
        print(f"  ... and {len(hardcoded) - 40} more")
    print(f"visible CJK source sink lines remaining: {len(source_hardcoded)}")
    for finding in source_hardcoded[:40]:
        print(f"  {finding}")
    if len(source_hardcoded) > 40:
        print(f"  ... and {len(source_hardcoded) - 40} more")
    print(f"all production CJK source literals remaining: {len(all_source_hardcoded)}")
    for finding in all_source_hardcoded[:40]:
        print(f"  {finding}")
    if len(all_source_hardcoded) > 40:
        print(f"  ... and {len(all_source_hardcoded) - 40} more")
    if args.strict_hardcoded and hardcoded:
        errors.append(f"{len(hardcoded)} visible XML literals remain")
    if args.strict_source and source_hardcoded:
        errors.append(f"{len(source_hardcoded)} visible source sink lines remain")
    if args.strict_all_source and all_source_hardcoded:
        errors.append(f"{len(all_source_hardcoded)} production CJK source literals remain")
    if errors:
        print("localization check failed:", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        return 1
    print("localization resource check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
