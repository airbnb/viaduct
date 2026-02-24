"""
MkDocs hook: capture post-macro-expansion markdown and write per-page
page.md files plus llms.txt / llms-full.txt at site root.

Hook order in MkDocs guarantees that hooks run *after* plugins, so by the
time on_page_markdown fires the macros plugin has already expanded all
{{ codetag(...) }} calls.
"""

from __future__ import annotations

import os
import re
from typing import Any

# Maps src_path -> cleaned markdown for every non-homepage page.
_page_markdown: dict[str, str] = {}
# Ordered list of (src_path, title, url) for llms.txt index.
_page_order: list[tuple[str, str, str]] = []


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _strip_frontmatter(text: str) -> str:
    """Remove YAML frontmatter (--- ... ---) from the top of a markdown string."""
    stripped = text.lstrip()
    if stripped.startswith("---"):
        end = stripped.find("\n---", 3)
        if end != -1:
            return stripped[end + 4:].lstrip()
    return text


def _is_homepage(page: Any) -> bool:
    """Return True for the homepage (index.md with home.html template)."""
    if page.file.src_path == "index.md":
        return True
    meta = getattr(page, "meta", {}) or {}
    return meta.get("template") == "home.html"


def _page_url(page: Any, config: dict) -> str:
    """Build the canonical URL for a page."""
    site_url = config.get("site_url", "").rstrip("/")
    dest = page.file.dest_path  # e.g. "getting_started/index.html"
    # Convert foo/index.html -> foo/
    if dest.endswith("/index.html"):
        url_path = dest[: -len("index.html")]
    elif dest.endswith(".html"):
        url_path = dest[: -len(".html")] + "/"
    else:
        url_path = dest
    return f"{site_url}/{url_path}"


# ---------------------------------------------------------------------------
# MkDocs event handlers
# ---------------------------------------------------------------------------

def on_page_markdown(markdown: str, page: Any, config: dict, files: Any) -> str:
    """Capture expanded markdown for every non-homepage page."""
    if _is_homepage(page):
        return markdown

    cleaned = _strip_frontmatter(markdown)

    # Ensure a top-level heading is present.
    title = getattr(page, "title", None) or page.file.src_path
    if not re.match(r"^#\s", cleaned):
        cleaned = f"# {title}\n\n{cleaned}"

    _page_markdown[page.file.src_path] = cleaned
    return markdown  # return unchanged so the normal build proceeds


def on_post_build(config: dict) -> None:
    """Write page.md files and generate llms.txt / llms-full.txt."""
    site_dir: str = config["site_dir"]

    # ------------------------------------------------------------------
    # Write per-page page.md files
    # ------------------------------------------------------------------
    for src_path, content in _page_markdown.items():
        # Derive the output directory.
        # MkDocs places all pages under a slug directory:
        #   getting_started/index.md          -> site/getting_started/
        #   getting_started/starwars/core/field_resolvers.md
        #                                     -> site/getting_started/starwars/core/field_resolvers/
        if src_path.endswith("/index.md"):
            dest_dir_rel = src_path[: -len("/index.md")]
        elif src_path == "index.md":
            continue  # root homepage – skip
        elif src_path.endswith(".md"):
            dest_dir_rel = src_path[: -len(".md")]
        else:
            dest_dir_rel = src_path
        dest_dir = os.path.join(site_dir, dest_dir_rel)
        os.makedirs(dest_dir, exist_ok=True)
        out_path = os.path.join(dest_dir, "page.md")
        with open(out_path, "w", encoding="utf-8") as fh:
            fh.write(content)

    # ------------------------------------------------------------------
    # Build ordered page list from nav
    # ------------------------------------------------------------------
    _collect_nav_pages(config)

    # ------------------------------------------------------------------
    # llms.txt  (index only)
    # ------------------------------------------------------------------
    site_name = config.get("site_name", "Documentation")
    site_description = config.get("site_description", "")
    site_url = config.get("site_url", "").rstrip("/")

    lines: list[str] = [f"# {site_name}", ""]
    if site_description:
        lines += [f"> {site_description}", ""]
    lines += ["## Pages", ""]
    for src_path, title, url in _page_order:
        lines.append(f"- [{title}]({url})")

    llms_txt_path = os.path.join(site_dir, "llms.txt")
    with open(llms_txt_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    # ------------------------------------------------------------------
    # llms-full.txt  (concatenated content)
    # ------------------------------------------------------------------
    full_parts: list[str] = [f"# {site_name}", ""]
    if site_description:
        full_parts += [f"> {site_description}", ""]
    full_parts.append("")

    for src_path, title, url in _page_order:
        content = _page_markdown.get(src_path)
        if content:
            full_parts += [
                f"---\n## {title}\n\nURL: {url}\n",
                content,
                "",
            ]

    llms_full_path = os.path.join(site_dir, "llms-full.txt")
    with open(llms_full_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(full_parts) + "\n")


# ---------------------------------------------------------------------------
# Nav traversal
# ---------------------------------------------------------------------------

def _collect_nav_pages(config: dict) -> None:
    """Walk config['nav'] and populate _page_order in nav order."""
    _page_order.clear()
    nav = config.get("nav", [])
    _walk_nav(nav, config)


def _walk_nav(items: list, config: dict) -> None:
    for item in items:
        if isinstance(item, dict):
            for key, value in item.items():
                if isinstance(value, str):
                    # Leaf: key=title, value=src_path
                    src_path = value
                    if src_path == "index.md":
                        continue  # skip homepage
                    if src_path in _page_markdown:
                        # Build a synthetic page-like object to get the URL.
                        url = _src_path_to_url(src_path, config)
                        _page_order.append((src_path, key, url))
                elif isinstance(value, list):
                    _walk_nav(value, config)


def _src_path_to_url(src_path: str, config: dict) -> str:
    """Convert a nav src_path (e.g. 'getting_started/index.md') to a URL."""
    site_url = config.get("site_url", "").rstrip("/")
    if src_path.endswith("/index.md"):
        url_path = src_path[: -len("index.md")]
    elif src_path.endswith(".md"):
        url_path = src_path[: -len(".md")] + "/"
    else:
        url_path = src_path + "/"
    return f"{site_url}/{url_path}"
