"""
Custom djlint rule to validate links to /apis/ directory.

This module checks that all links from non-API pages to the /apis/ directory
point to pages that actually exist in the built site.
"""

import os
import re
from pathlib import Path
from urllib.parse import unquote, urlparse


def run(rule, config, html, filepath, line_ends, *args, **kwargs):
    """
    Validate that links to /apis/ from non-API pages point to existing files.

    Args:
        rule: The rule definition from .djlint_rules.yaml
        config: The djLint configuration object
        html: The complete file content
        filepath: Current file path being linted
        line_ends: Character position data for line calculations

    Returns:
        List of error dictionaries with 'code', 'line', 'match', and 'message' keys
    """
    errors = []

    # Skip files in the apis directory
    filepath_str = str(filepath)
    if "/apis/" in filepath_str or "\\apis\\" in filepath_str:
        return errors

    # Find the site root directory
    # The filepath will be something like /path/to/docs/site/getting_started/index.html
    # We need to find the 'site' directory
    site_dir = _find_site_dir(filepath_str)
    if not site_dir:
        return errors

    # Find all href attributes that link to /apis/
    # Match href="/apis/..." or href="../apis/..." or href="../../apis/..." etc.
    link_pattern = re.compile(
        r'href=["\']([^"\']*?(?:/apis/|\.\.\/[^"\']*apis/)[^"\']*)["\']',
        re.IGNORECASE
    )

    for match in link_pattern.finditer(html):
        href = match.group(1)
        link_start = match.start(1)

        # Skip external links and anchors
        if href.startswith(('http://', 'https://', 'mailto:', '#')):
            continue

        # Normalize the path to resolve relative references
        target_path = _resolve_link_path(href, filepath_str, site_dir)

        if target_path and not _path_exists(target_path, site_dir):
            line_pos = _get_line_position(link_start, line_ends)
            errors.append({
                "code": rule["name"],
                "line": line_pos,
                "match": match.group(0),
                "message": f"{rule['message']}: {href} -> {target_path}"
            })

    return errors


def _find_site_dir(filepath):
    """Find the site directory from a filepath."""
    path = Path(filepath)

    # Walk up the directory tree to find 'site'
    for parent in path.parents:
        if parent.name == "site":
            return str(parent)

        # Check if a 'site' directory exists as a sibling
        site_candidate = parent / "site"
        if site_candidate.is_dir():
            return str(site_candidate)

    return None


def _resolve_link_path(href, filepath, site_dir):
    """
    Resolve a link href to an absolute path within the site directory.

    Args:
        href: The href value from the link
        filepath: The current file being linted
        site_dir: The site directory root

    Returns:
        The resolved path relative to site_dir, or None if not an API link
    """
    # Remove query string and fragment
    href = href.split('?')[0].split('#')[0]

    # URL decode the path
    href = unquote(href)

    # Handle absolute paths starting with /
    if href.startswith('/'):
        # Absolute path from site root
        resolved = href.lstrip('/')
    else:
        # Relative path - resolve from current file's directory
        current_dir = Path(filepath).parent
        try:
            resolved = str((current_dir / href).resolve())
            # Make it relative to site_dir
            site_path = Path(site_dir)
            resolved_path = Path(resolved)
            if site_path in resolved_path.parents or resolved_path == site_path:
                resolved = str(resolved_path.relative_to(site_path))
            else:
                # Path is outside site directory
                return None
        except (ValueError, RuntimeError):
            return None

    # Only check paths that go to /apis/
    if not resolved.startswith('apis/') and '/apis/' not in resolved:
        return None

    return resolved


def _path_exists(relative_path, site_dir):
    """
    Check if a path exists in the site directory.

    Handles both direct file paths and directory index files.
    """
    full_path = Path(site_dir) / relative_path

    # Check for exact file
    if full_path.exists():
        return True

    # Check for directory with index.html
    if full_path.is_dir():
        index_path = full_path / "index.html"
        if index_path.exists():
            return True

    # If path doesn't end with / or .html, try adding index.html
    if not relative_path.endswith('/') and not relative_path.endswith('.html'):
        index_path = full_path / "index.html"
        if index_path.exists():
            return True

        # Also try with .html extension
        html_path = Path(site_dir) / (relative_path + ".html")
        if html_path.exists():
            return True

    # Handle trailing slash
    if relative_path.endswith('/'):
        index_path = Path(site_dir) / relative_path / "index.html"
        if index_path.exists():
            return True

    return False


def _get_line_position(char_position, line_ends):
    """
    Convert a character position to a line:column string.

    Args:
        char_position: The character offset in the file
        line_ends: List of dicts with 'start' and 'end' keys for each line

    Returns:
        A string in the format "line:column" (1-indexed)
    """
    if not line_ends:
        return "1:0"

    for line_num, line_info in enumerate(line_ends, start=1):
        if char_position <= line_info["end"]:
            column = char_position - line_info["start"]
            return f"{line_num}:{column}"

    return f"{len(line_ends)}:0"
