from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys

from tools import check_mojibake


def _git(repository: Path, *arguments: str) -> None:
    subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=True,
        capture_output=True,
    )


def test_git_candidates_exclude_ignored_generated_and_binary_files(
    tmp_path: Path,
) -> None:
    _git(tmp_path, "init", "--quiet")
    (tmp_path / ".gitignore").write_text("ignored.py\n", encoding="utf-8")
    (tmp_path / "tracked.md").write_text("tracked\n", encoding="utf-8")
    (tmp_path / "candidate.py").write_text("candidate = True\n", encoding="utf-8")
    (tmp_path / "ignored.py").write_text("ignored = True\n", encoding="utf-8")
    (tmp_path / "artifact.exe").write_bytes(b"MZ\x00binary")
    for directory in (".venv", ".venv-build", "build", "node_modules", "runtime"):
        generated = tmp_path / directory / "generated.py"
        generated.parent.mkdir()
        generated.write_text("generated = True\n", encoding="utf-8")
    _git(tmp_path, "add", "tracked.md")

    candidates = {
        path.relative_to(tmp_path).as_posix()
        for path in check_mojibake.git_candidate_paths(tmp_path)
    }

    assert "tracked.md" in candidates
    assert "candidate.py" in candidates
    assert "ignored.py" not in candidates
    assert "artifact.exe" not in candidates
    assert not any("generated.py" in path for path in candidates)


def test_cli_uses_utf8_output_and_stable_exit_for_explicit_root(
    tmp_path: Path,
) -> None:
    suspicious = tmp_path / "bad.py"
    suspicious.write_text('label = "\u951f\u65a4\u62f7"\n', encoding="utf-8")
    binary_source = tmp_path / "binary.py"
    binary_source.write_bytes(b"\x00\xff\xfe\x00")
    hidden = tmp_path / ".venv" / "hidden.py"
    hidden.parent.mkdir()
    hidden.write_text('label = "\u951f\u65a4\u62f7"\n', encoding="utf-8")

    command = [sys.executable, str(check_mojibake.__file__), str(tmp_path)]
    outputs: list[bytes] = []
    for hash_seed in ("1", "2"):
        environment = {
            **os.environ,
            "PYTHONHASHSEED": hash_seed,
            "PYTHONIOENCODING": "ascii",
        }
        result = subprocess.run(
            command,
            check=False,
            capture_output=True,
            env=environment,
        )
        assert result.returncode == 1
        assert result.stderr == b""
        outputs.append(result.stdout)

    assert outputs[0] == outputs[1]
    output = outputs[0].decode("utf-8")
    assert "bad.py:1" in output
    assert "\u951f\u65a4\u62f7" in output
    assert "hidden.py" not in output
    assert "binary.py" not in output
