#!/usr/bin/env python3
"""Scan source assets for common Chinese mojibake markers."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = {
    ".kt",
    ".xml",
    ".kts",
    ".properties",
    ".md",
    ".py",
    ".json",
    ".jsonl",
}
SKIP_DIRS = {".git", ".gradle", ".idea", "build"}

HIGH_CONFIDENCE_PATTERNS = [
    ("replacement character", "\ufffd"),
    ("replacement triplet", "\ufffd\ufffd\ufffd"),
    ("gbk replacement text", "\u951f\u65a4\u62f7"),
    ("utf8-as-latin cjk prefix", "\u00e4\u00b8"),
    ("utf8-as-latin cjk prefix", "\u00e4\u00bb"),
    ("utf8-as-latin cjk prefix", "\u00e4\u00bd"),
    ("utf8-as-latin cjk prefix", "\u00e5\u2026"),
    ("utf8-as-latin cjk prefix", "\u00e9\u201d"),
    ("latin1 mojibake marker", "\u00c3"),
    ("latin1 mojibake marker", "\u00c2"),
]
SINGLE_CHAR_MARKERS = {
    "\u00e5",
    "\u00e6",
    "\u00e7",
    "\u00e9",
    "\u00e4",
    "\u00e8",
}
MOJIBAKE_TAIL_MARKERS = {
    "\u00a0",
    "\u00a1",
    "\u00a2",
    "\u00a3",
    "\u00a4",
    "\u00a5",
    "\u00a6",
    "\u00a7",
    "\u00a8",
    "\u00a9",
    "\u00aa",
    "\u00ab",
    "\u00ac",
    "\u00ad",
    "\u00ae",
    "\u00af",
    "\u00b0",
    "\u00b1",
    "\u00b2",
    "\u00b3",
    "\u00b4",
    "\u00b5",
    "\u00b6",
    "\u00b7",
    "\u00b8",
    "\u00b9",
    "\u00ba",
    "\u00bb",
    "\u00bc",
    "\u00bd",
    "\u00be",
    "\u00bf",
    "\u2018",
    "\u2019",
    "\u201c",
    "\u201d",
    "\u2026",
}


@dataclass(frozen=True)
class Finding:
    path: Path
    line_number: int
    reason: str
    snippet: str


def should_scan(path: Path) -> bool:
    if path.suffix.lower() not in EXTENSIONS:
        return False
    relative_parts = path.relative_to(ROOT).parts
    return not any(part in SKIP_DIRS for part in relative_parts)


def suspicious_single_markers(line: str) -> list[str]:
    hits = [marker for marker in SINGLE_CHAR_MARKERS if marker in line]
    if not hits:
        return []

    hit_count = sum(line.count(marker) for marker in SINGLE_CHAR_MARKERS)
    has_tail = any(marker in line for marker in MOJIBAKE_TAIL_MARKERS)
    if hit_count >= 2 or has_tail:
        return hits
    return []


def scan_line(path: Path, line_number: int, line: str) -> list[Finding]:
    findings: list[Finding] = []
    snippet = line.strip().replace("\t", " ")[:180]

    for reason, pattern in HIGH_CONFIDENCE_PATTERNS:
        if pattern in line:
            findings.append(Finding(path, line_number, reason, snippet))

    singleton_hits = suspicious_single_markers(line)
    if singleton_hits:
        display = " ".join(f"U+{ord(marker):04X}" for marker in singleton_hits)
        findings.append(Finding(path, line_number, f"suspicious latin-1 marker {display}", snippet))

    return findings


def scan_file(path: Path) -> list[Finding]:
    findings: list[Finding] = []
    with path.open("rb") as file:
        for line_number, raw_line in enumerate(file, start=1):
            try:
                line = raw_line.decode("utf-8")
                findings.extend(scan_line(path, line_number, line))
            except UnicodeDecodeError as error:
                findings.append(
                    Finding(
                        path=path,
                        line_number=line_number,
                        reason="file is not valid UTF-8",
                        snippet=str(error),
                    )
                )
                break
    return findings


def main() -> int:
    findings: list[Finding] = []
    for path in sorted(ROOT.rglob("*")):
        if path.is_file() and should_scan(path):
            findings.extend(scan_file(path))

    if findings:
        print("Possible mojibake found:")
        for finding in findings:
            relative = finding.path.relative_to(ROOT)
            print(f"{relative}:{finding.line_number}: {finding.reason}: {finding.snippet}")
        return 1

    print("No mojibake markers found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
