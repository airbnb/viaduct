#!/usr/bin/env python3
"""
Changelog generator using python-semantic-release library.

This module generates changelogs between two git tags using the PSR library
while maintaining Viaduct's authorship semantics:
- Primary author extracted from commit email
- Co-authors parsed from git trailers
- Bot accounts filtered out
- Usernames formatted as @username
"""
from __future__ import annotations

import re
import subprocess
from collections import defaultdict
from dataclasses import dataclass
from typing import Iterator

from semantic_release.commit_parser.conventional import ConventionalCommitParser
from semantic_release.commit_parser.token import ParsedMessageResult, ParseResult
from semantic_release.enums import LevelBump


# Bot accounts to filter from authorship
BOT_USERNAMES = frozenset(['noreply', 'no-reply', 'github-actions', 'viaductbot'])

# Internal metadata patterns to remove from commit messages
METADATA_PATTERNS = [
    r'\s+Github-Change-Id:\s+\w+',
    r'\s+GitOrigin-RevId:\s+[a-f0-9]+',
]

# Change type definitions with sort priority and display names
CHANGE_TYPES: dict[str, tuple[int, str]] = {
    "feat": (1, "Features"),
    "fix": (2, "Bug Fixes"),
    "perf": (3, "Performance Improvements"),
    "docs": (4, "Documentation"),
    "test": (5, "Testing"),
    "refactor": (6, "Refactoring"),
    "style": (7, "Code Style"),
    "chore": (8, "Chores"),
    "ci": (9, "Continuous Integration"),
    "build": (10, "Build System"),
}


@dataclass
class CommitInfo:
    """Raw commit information extracted from git log."""
    sha: str
    message: str
    body: str
    author_email: str
    co_authors_raw: str


@dataclass
class ChangelogEntry:
    """A processed changelog entry with authorship information."""
    sha: str
    message: str
    authors: list[str]
    change_type: str | None
    scope: str | None
    description: str
    is_breaking: bool
    breaking_description: str | None
    bump: LevelBump

    @property
    def formatted_authors(self) -> str:
        """Format authors as comma-separated @usernames."""
        if self.authors:
            return ', '.join(self.authors)
        return '@anonymous'

    @property
    def formatted_entry(self) -> str:
        """Format the complete changelog entry."""
        return f"{self.description} by {self.formatted_authors}"


def extract_username_from_email(email: str) -> str | None:
    """
    Extract username from an email address.

    Args:
        email: Email address (e.g., 'john.doe@example.com')

    Returns:
        Username prefixed with @ (e.g., '@john.doe'), or None if invalid/bot
    """
    if not email:
        return None

    match = re.search(r'^([^@]+)@', email)
    if not match:
        return None

    username = match.group(1)
    if username in BOT_USERNAMES:
        return None

    return f"@{username}"


def extract_username_from_coauthor(author_line: str) -> str | None:
    """
    Extract username from Co-authored-by format.

    Args:
        author_line: Co-author line (e.g., 'Name <email@example.com>')

    Returns:
        Username prefixed with @ (e.g., '@email'), or None if invalid/bot
    """
    match = re.search(r'<([^@]+)@', author_line)
    if not match:
        return None

    username = match.group(1)
    if username in BOT_USERNAMES:
        return None

    return f"@{username}"


def clean_commit_message(message: str) -> str:
    """
    Remove internal metadata from commit message while preserving issue references.

    Args:
        message: Raw commit message

    Returns:
        Cleaned commit message with metadata removed
    """
    cleaned = message
    for pattern in METADATA_PATTERNS:
        cleaned = re.sub(pattern, '', cleaned, flags=re.IGNORECASE)
    return cleaned.strip()


def replace_airbnb_marker(message: str, sha: str) -> str:
    """
    Replace (AIRBNB) marker with commit SHA.

    Args:
        message: Commit message potentially containing (AIRBNB)
        sha: Short commit SHA to substitute

    Returns:
        Message with (AIRBNB) replaced by (sha)
    """
    return re.sub(r'\(AIRBNB\)', f'({sha})', message, flags=re.IGNORECASE)


def should_include_commit(message: str) -> bool:
    """
    Check if a commit should be included in the changelog.

    Args:
        message: Commit message to check

    Returns:
        True if commit should be included, False if it should be ignored
    """
    return not message.lower().startswith('ignore:')


def get_commits_between_tags(tag1: str, tag2: str) -> Iterator[CommitInfo]:
    """
    Get all commits between two git tags.

    Args:
        tag1: Starting tag (exclusive)
        tag2: Ending tag (inclusive)

    Yields:
        CommitInfo objects for each commit in the range
    """
    # Custom format with markers for reliable parsing
    # Use %x00 (null byte) as record separator since body can contain newlines
    format_str = (
        'SHA_START%hSHA_END '
        'MSG_START%sMSG_END '
        'BODY_START%bBODY_END '
        'AUTHOR_START%aeAUTHOR_END '
        'COAUTHORS_START%(trailers:key=Co-authored-by,valueonly,separator=%x7C)COAUTHORS_END'
        '%x00'  # Null byte as record separator
    )

    result = subprocess.run(
        ['git', 'log', f'{tag1}..{tag2}', f'--format=format:{format_str}'],
        capture_output=True,
        text=True,
        check=True,
    )

    # Split on null byte instead of newlines to handle multi-line bodies
    for record in result.stdout.split('\x00'):
        if not record.strip():
            continue

        # Extract SHA
        sha_match = re.search(r'SHA_START(.+?)SHA_END', record)
        sha = sha_match.group(1).strip() if sha_match else ''

        # Extract message
        msg_match = re.search(r'MSG_START(.+?)MSG_END', record)
        message = msg_match.group(1).strip() if msg_match else ''

        # Extract body (use DOTALL flag since body can contain newlines)
        body_match = re.search(r'BODY_START(.+?)BODY_END', record, re.DOTALL)
        body = body_match.group(1).strip() if body_match else ''

        # Extract author email
        author_match = re.search(r'AUTHOR_START(.+?)AUTHOR_END', record)
        author_email = author_match.group(1).strip() if author_match else ''

        # Extract co-authors
        coauthors_match = re.search(r'COAUTHORS_START(.*)COAUTHORS_END', record)
        co_authors_raw = coauthors_match.group(1).strip() if coauthors_match else ''

        yield CommitInfo(
            sha=sha,
            message=message,
            body=body,
            author_email=author_email,
            co_authors_raw=co_authors_raw,
        )


def extract_authors(commit: CommitInfo) -> list[str]:
    """
    Extract all authors (primary + co-authors) from a commit.

    Args:
        commit: CommitInfo object

    Returns:
        List of @usernames for all non-bot authors
    """
    authors = []

    # Add primary author
    primary = extract_username_from_email(commit.author_email)
    if primary:
        authors.append(primary)

    # Add co-authors
    if commit.co_authors_raw:
        for coauthor in commit.co_authors_raw.split('|'):
            coauthor = coauthor.strip()
            if coauthor:
                username = extract_username_from_coauthor(coauthor)
                if username:
                    authors.append(username)

    return authors


def parse_commit(commit: CommitInfo, parser: ConventionalCommitParser) -> ChangelogEntry | None:
    """
    Parse a commit into a ChangelogEntry.

    Args:
        commit: Raw commit information
        parser: Conventional commit parser instance

    Returns:
        ChangelogEntry if the commit should be included, None otherwise
    """
    # Check if commit should be included
    if not should_include_commit(commit.message):
        return None

    # Clean the message
    cleaned_message = clean_commit_message(commit.message)
    cleaned_message = replace_airbnb_marker(cleaned_message, commit.sha)

    # Extract authors
    authors = extract_authors(commit)

    # Parse with conventional commit parser (don't include authors in message - they're added later)
    message_with_body = f"{cleaned_message}\n\n{commit.body}"
    parsed = parser.parse_message(message_with_body)

    if isinstance(parsed, ParsedMessageResult):
        # Conventional commit was successfully parsed
        return ChangelogEntry(
            sha=commit.sha,
            message=cleaned_message,
            authors=authors,
            change_type=parsed.type,
            scope=parsed.scope,
            description=parsed.descriptions[0] if parsed.descriptions else cleaned_message,
            is_breaking=bool(parsed.breaking_descriptions) or parsed.bump == LevelBump.MAJOR,
            breaking_description=parsed.breaking_descriptions[0] if parsed.breaking_descriptions else None,
            bump=parsed.bump,
        )
    else:
        # Not a conventional commit - include as-is
        return ChangelogEntry(
            sha=commit.sha,
            message=cleaned_message,
            authors=authors,
            change_type=None,
            scope=None,
            description=cleaned_message,
            is_breaking=False,
            breaking_description=None,
            bump=LevelBump.NO_RELEASE,
        )


def group_entries_by_type(entries: list[ChangelogEntry]) -> dict[str, list[ChangelogEntry]]:
    """
    Group changelog entries by their change type.

    Args:
        entries: List of ChangelogEntry objects

    Returns:
        Dictionary mapping change types to lists of entries
    """
    grouped: dict[str, list[ChangelogEntry]] = defaultdict(list)
    for entry in entries:
        if entry.change_type:
            grouped[entry.change_type].append(entry)
        else:
            # Non-conventional commits go under 'chore'
            grouped['chore'].append(entry)
    return dict(grouped)


def render_section(change_type: str, entries: list[ChangelogEntry]) -> str:
    """
    Render a changelog section for a specific change type.

    Args:
        change_type: The type of change (feat, fix, etc.)
        entries: List of entries for this type

    Returns:
        Formatted markdown section
    """
    if change_type not in CHANGE_TYPES:
        title = change_type.title()
        priority = 99
    else:
        priority, title = CHANGE_TYPES[change_type]

    lines = [f"## {title}", ""]
    for entry in entries:
        lines.append(f"- {entry.formatted_entry}")
    lines.append("")

    return '\n'.join(lines)


def render_breaking_changes(entries: list[ChangelogEntry]) -> str:
    """
    Render the breaking changes section.

    Args:
        entries: List of entries with breaking changes

    Returns:
        Formatted markdown section for breaking changes
    """
    lines = ["## Breaking Changes", ""]
    for entry in entries:
        desc = entry.breaking_description or entry.description
        lines.append(f"- {desc} by {entry.formatted_authors}")
    lines.append("")

    return '\n'.join(lines)


def generate_changelog(tag1: str, tag2: str) -> str:
    """
    Generate a changelog between two git tags.

    Args:
        tag1: Starting tag (exclusive)
        tag2: Ending tag (inclusive)

    Returns:
        Formatted markdown changelog
    """
    parser = ConventionalCommitParser()

    # Get and parse all commits
    entries: list[ChangelogEntry] = []
    for commit in get_commits_between_tags(tag1, tag2):
        entry = parse_commit(commit, parser)
        if entry:
            entries.append(entry)

    if not entries:
        return f"# Version {tag2}\n\nNo changes.\n"

    # Separate breaking changes
    breaking_entries = [e for e in entries if e.is_breaking]
    regular_entries = [e for e in entries if not e.is_breaking]

    # Group regular entries by type
    grouped = group_entries_by_type(regular_entries)

    # Sort sections by priority
    sorted_types = sorted(
        grouped.keys(),
        key=lambda t: CHANGE_TYPES.get(t, (99, t))[0]
    )

    # Build changelog
    sections = [f"# Version {tag2}", ""]

    # Add breaking changes first if any
    if breaking_entries:
        sections.append(render_breaking_changes(breaking_entries))

    # Add regular sections
    for change_type in sorted_types:
        sections.append(render_section(change_type, grouped[change_type]))

    return '\n'.join(sections)


def main():
    """CLI entrypoint for changelog generation."""
    import argparse

    arg_parser = argparse.ArgumentParser(
        description='Generate changelog between two git tags using python-semantic-release.'
    )
    arg_parser.add_argument('tag1', help='Starting git tag (exclusive)')
    arg_parser.add_argument('tag2', help='Ending git tag (inclusive)')

    args = arg_parser.parse_args()

    changelog = generate_changelog(args.tag1, args.tag2)
    print(changelog)


if __name__ == '__main__':
    main()
