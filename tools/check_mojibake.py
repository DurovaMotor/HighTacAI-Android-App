#!/usr/bin/env python3
"""Scan Git candidate source files for common Chinese mojibake markers."""

from __future__ import annotations

import argparse
from collections.abc import Sequence
from dataclasses import dataclass
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = {
    ".c",
    ".cc",
    ".cfg",
    ".cmd",
    ".conf",
    ".cpp",
    ".css",
    ".gradle",
    ".h",
    ".hpp",
    ".html",
    ".ini",
    ".iss",
    ".java",
    ".js",
    ".json",
    ".jsonl",
    ".kt",
    ".kts",
    ".md",
    ".properties",
    ".ps1",
    ".psd1",
    ".psm1",
    ".py",
    ".pyi",
    ".sh",
    ".sql",
    ".toml",
    ".ts",
    ".tsx",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
}
SOURCE_FILENAMES = {
    ".gitattributes",
    ".gitignore",
    "dockerfile",
    "gradlew",
}
SKIP_DIRS = {
    ".git",
    ".gradle",
    ".idea",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".venv",
    ".venv-build",
    "__pycache__",
    "build",
    "coverage",
    "dist",
    "htmlcov",
    "node_modules",
    "out",
    "output",
    "playwright-report",
    "runtime",
    "test-results",
    "venv",
}

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
SINGLE_CHAR_MARKERS = (
    "\u00e4",
    "\u00e5",
    "\u00e6",
    "\u00e7",
    "\u00e8",
    "\u00e9",
)
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


class CandidateDiscoveryError(RuntimeError):
    """Raised when source candidates cannot be enumerated safely."""


@dataclass(frozen=True)
class Finding:
    path: Path
    line_number: int
    reason: str
    snippet: str


def _force_utf8_console() -> None:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="backslashreplace")
        except (AttributeError, OSError, ValueError):
            pass


def _path_sort_key(path: Path) -> tuple[str, str]:
    value = path.as_posix()
    return value.casefold(), value


def _relative_directory_parts(path: Path, root: Path) -> tuple[str, ...]:
    try:
        relative = path.resolve().relative_to(root.resolve())
        return relative.parts[:-1]
    except ValueError:
        return path.resolve().parts[:-1]


def should_scan(path: Path, root: Path = ROOT) -> bool:
    directory_parts = _relative_directory_parts(path, root)
    if any(part.casefold() in SKIP_DIRS for part in directory_parts):
        return False
    return (
        path.name.casefold() in SOURCE_FILENAMES
        or path.suffix.casefold() in EXTENSIONS
    )


def _is_binary(path: Path) -> bool:
    with path.open("rb") as file:
        return b"\x00" in file.read(8192)


def git_candidate_paths(root: Path = ROOT) -> list[Path]:
    command = [
        "git",
        "-C",
        str(root),
        "ls-files",
        "--cached",
        "--others",
        "--exclude-standard",
        "-z",
        "--",
    ]
    try:
        result = subprocess.run(command, capture_output=True, check=False)
    except OSError as error:
        raise CandidateDiscoveryError("unable to execute Git") from error
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        message = f"Git candidate discovery failed with exit code {result.returncode}"
        raise CandidateDiscoveryError(f"{message}: {detail}" if detail else message)

    resolved_root = root.resolve()
    candidates: set[Path] = set()
    for raw_path in result.stdout.split(b"\x00"):
        if not raw_path:
            continue
        try:
            relative = Path(raw_path.decode("utf-8"))
        except UnicodeDecodeError as error:
            raise CandidateDiscoveryError(
                "Git returned a path that is not valid UTF-8"
            ) from error
        path = (resolved_root / relative).resolve()
        try:
            path.relative_to(resolved_root)
        except ValueError:
            continue
        if path.is_file() and should_scan(path, resolved_root):
            candidates.add(path)
    return sorted(candidates, key=_path_sort_key)


def _walk_explicit_root(path: Path, repository_root: Path) -> list[Path]:
    if not path.exists():
        raise CandidateDiscoveryError(f"explicit path does not exist: {path}")
    if path.is_file():
        return [path] if should_scan(path, repository_root) else []
    if not path.is_dir():
        return []

    candidates: list[Path] = []
    for current, directories, filenames in os.walk(path, followlinks=False):
        directories[:] = sorted(
            (
                name
                for name in directories
                if name.casefold() not in SKIP_DIRS
            ),
            key=str.casefold,
        )
        current_path = Path(current)
        for filename in sorted(filenames, key=str.casefold):
            candidate = current_path / filename
            if candidate.is_file() and should_scan(candidate, repository_root):
                candidates.append(candidate.resolve())
    return candidates


def discover_candidate_paths(
    root: Path = ROOT,
    explicit_roots: Sequence[Path] = (),
) -> list[Path]:
    if not explicit_roots:
        return git_candidate_paths(root)

    candidates: set[Path] = set()
    for supplied_path in explicit_roots:
        path = supplied_path if supplied_path.is_absolute() else root / supplied_path
        candidates.update(_walk_explicit_root(path.resolve(), root.resolve()))
    return sorted(candidates, key=_path_sort_key)


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
        findings.append(
            Finding(path, line_number, f"suspicious latin-1 marker {display}", snippet)
        )

    return findings


def scan_file(path: Path) -> list[Finding]:
    if _is_binary(path):
        return []

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


def _display_path(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def main(argv: Sequence[str] | None = None) -> int:
    _force_utf8_console()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "paths",
        nargs="*",
        type=Path,
        help="Explicit files or directories to scan instead of Git candidates.",
    )
    args = parser.parse_args(argv)

    try:
        candidates = discover_candidate_paths(ROOT, args.paths)
    except CandidateDiscoveryError as error:
        print(f"Mojibake scan setup failed: {error}", file=sys.stderr)
        return 2

    findings: list[Finding] = []
    for path in candidates:
        try:
            findings.extend(scan_file(path))
        except FileNotFoundError:
            continue
        except OSError as error:
            display_path = _display_path(path, ROOT)
            print(
                f"Mojibake scan failed for {display_path}: {error}",
                file=sys.stderr,
            )
            return 2

    findings.sort(
        key=lambda finding: (
            _display_path(finding.path, ROOT).casefold(),
            _display_path(finding.path, ROOT),
            finding.line_number,
            finding.reason,
            finding.snippet,
        )
    )
    if findings:
        print("Possible mojibake found:")
        for finding in findings:
            relative = _display_path(finding.path, ROOT)
            print(
                f"{relative}:{finding.line_number}: "
                f"{finding.reason}: {finding.snippet}"
            )
        return 1

    print("No mojibake markers found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
