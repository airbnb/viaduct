#!/usr/bin/env python3
from __future__ import annotations

import re
import subprocess
from pathlib import Path


_TREEHOUSE_CANDIDATES = [
    Path('~/repos/treehouse').expanduser(),
    Path('~/airlab/repos/treehouse').expanduser(),
    Path('~/src/treehouse').expanduser(),
]
_TREEHOUSE_MAPPINGS_SUBPATH = Path('projects/viaduct/oss/.copybara/author_mappings.bara.sky')
_LOCAL_MAPPINGS_SUBPATH = Path('.copybara/author_mappings.bara.sky')


def _parse_file(path: Path) -> dict[str, str]:
    content = path.read_text()
    result = {}
    for internal, github in re.findall(r'"([^"]+)"\s*:\s*"([^"]+)"', content):
        internal_email = re.search(r'<([^>]+)>', internal)   # extract email from "Name <email>"
        github_username = re.search(r'<([^@]+)@', github)    # extract username from "Name <user@host>"
        if internal_email and github_username:
            result[internal_email.group(1)] = github_username.group(1)
    return result


def _find_file() -> Path | None:
    candidates: list[Path] = []
    try:
        repo_root = Path(subprocess.run(
            ['git', 'rev-parse', '--show-toplevel'],
            capture_output=True, text=True, check=True,
        ).stdout.strip())
        candidates.append(repo_root / _LOCAL_MAPPINGS_SUBPATH)
    except subprocess.CalledProcessError:
        pass
    for treehouse in _TREEHOUSE_CANDIDATES:
        candidates.append(treehouse / _TREEHOUSE_MAPPINGS_SUBPATH)
    return next((p for p in candidates if p.exists()), None)


def _resolve(email: str, mappings: dict[str, str]) -> str | None:
    """Exact match first, then linear search by email substring. Mirrors map_author logic."""
    if email in mappings:
        return mappings[email]
    for entry_email, github_username in mappings.items():
        if entry_email in email:
            return github_username
    return None


_cached_mappings: dict[str, str] | None = None


def _get_mappings() -> dict[str, str]:
    global _cached_mappings
    if _cached_mappings is None:
        path = _find_file()
        _cached_mappings = _parse_file(path) if path else {}
    return _cached_mappings


def map_email(email: str) -> str | None:
    """Returns the mapped GitHub username for an email, or None if no mapping is found."""
    try:
        return _resolve(email, _get_mappings()) or None
    except Exception:
        pass
    return None
