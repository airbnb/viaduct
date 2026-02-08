"""
MkDocs Macros Plugin for Code Snippets
Provides codetag and codefile macros similar to Hugo shortcodes.
"""

import re
from pathlib import Path


# Map file extensions to syntax highlighting languages
LANG_MAP = {
    '.kt': 'kotlin',
    '.kts': 'kotlin',
    '.java': 'java',
    '.graphqls': 'graphql',
    '.graphql': 'graphql',
    '.yaml': 'yaml',
    '.yml': 'yaml',
    '.json': 'json',
    '.md': 'markdown',
    '.py': 'python',
    '.js': 'javascript',
    '.ts': 'typescript',
    '.xml': 'xml',
    '.html': 'html',
    '.css': 'css',
    '.sh': 'bash',
    '.bash': 'bash',
    '.sql': 'sql',
    '.proto': 'protobuf',
    '.toml': 'toml',
    '.properties': 'properties',
    '.gradle': 'groovy',
}


def infer_language(path):
    """Infer the syntax highlighting language from a file path's extension."""
    ext = Path(path).suffix.lower()
    return LANG_MAP.get(ext, 'text')


def define_env(env):
    """
    Define custom macros for code snippet embedding.
    This is called by mkdocs-macros-plugin.
    """

    @env.macro
    def codetag(path, tag, lang=None, count=None):
        """
        Extract code snippet by tag from a file.

        Args:
            path: Relative path to the file from the repo root
            tag: Tag name to search for (e.g., "VIADUCT_CONFIG_1")
            lang: Language for syntax highlighting (inferred from file extension if not specified)
            count: Number of lines to include (overrides tag definition)

        Example in markdown:
            {{ codetag('demoapps/starwars/config.kt', 'CONFIG_EXAMPLE') }}
            {{ codetag('demoapps/starwars/schema.graphqls', 'SCHEMA_TAG', lang='graphql') }}
        """
        # Infer language from file extension if not specified
        if lang is None:
            lang = infer_language(path)
        # Get the docs directory
        docs_dir = Path(__file__).parent.parent

        # Construct full path - first try from oss directory (one level up from docs)
        full_path = docs_dir.parent / path

        if not full_path.exists():
            # Try from docs directory itself
            full_path = docs_dir / path

        if not full_path.exists():
            raise FileNotFoundError(f"codetag: File not found: {path} (tried: {full_path})")

        try:
            with open(full_path, 'r', encoding='utf-8') as f:
                content = f.read()

            # Find the tag in the file
            # Format: # tag::TAG_NAME[LINES] Optional comment
            # or: // tag::TAG_NAME[LINES] Optional comment
            tag_pattern = rf'(?:#|//)?\s*tag::{re.escape(tag)}(?:\[(\d+)\])?.*?\n'
            match = re.search(tag_pattern, content)

            if not match:
                raise ValueError(f"codetag: Tag '{tag}' not found in {path}")

            # Get the number of lines to extract
            lines_to_extract = count
            if lines_to_extract is None and match.group(1):
                lines_to_extract = int(match.group(1))
            if lines_to_extract is None:
                lines_to_extract = 10  # Default

            # Extract lines after the tag
            tag_end = match.end()
            remaining_content = content[tag_end:]
            lines = remaining_content.split('\n')[:lines_to_extract]
            snippet = '\n'.join(lines)

            return f"""
```{lang}
{snippet}
```

[View full file on GitHub](https://github.com/airbnb/viaduct/tree/master/{path})
"""

        except (FileNotFoundError, ValueError):
            raise
        except Exception as e:
            raise RuntimeError(f"codetag: Error reading file {path}: {str(e)}")

    @env.macro
    def codefile(path, start=None, end=None, lang=None):
        """
        Extract entire file or line range from a file.

        Args:
            path: Relative path to the file from the repo root
            start: Starting line number (1-indexed, optional)
            end: Ending line number (inclusive, optional)
            lang: Language for syntax highlighting (inferred from file extension if not specified)

        Example in markdown:
            {{ codefile('tenant/api/MyClass.kt', start=10, end=20) }}
            {{ codefile('config/settings.yaml') }}
        """
        # Infer language from file extension if not specified
        if lang is None:
            lang = infer_language(path)
        # Get the docs directory
        docs_dir = Path(__file__).parent.parent

        # Construct full path - first try from oss directory (one level up from docs)
        full_path = docs_dir.parent / path

        if not full_path.exists():
            # Try from docs directory itself
            full_path = docs_dir / path

        if not full_path.exists():
            raise FileNotFoundError(f"codefile: File not found: {path} (tried: {full_path})")

        try:
            with open(full_path, 'r', encoding='utf-8') as f:
                lines = f.readlines()

            # Extract line range if specified (convert to 0-indexed)
            if start is not None or end is not None:
                start_idx = (start - 1) if start else 0
                end_idx = end if end else len(lines)
                lines = lines[start_idx:end_idx]

            content = ''.join(lines).rstrip()

            line_range = ""
            if start or end:
                line_range = f" (lines {start or 1}-{end or len(lines)})"

            return f"""
```{lang}
{content}
```

[View full file on GitHub](https://github.com/airbnb/viaduct/tree/master/{path}){line_range}
"""

        except FileNotFoundError:
            raise
        except Exception as e:
            raise RuntimeError(f"codefile: Error reading file {path}: {str(e)}")

    @env.macro
    def github(file, maxHeight=500, branch="master"):
        """
        Embed a GitHub file with link.

        Args:
            file: Path to file (can include #L10-L20 for line range)
            maxHeight: Max height in pixels (for display purposes)
            branch: Git branch name

        Example in markdown:
            {{ github('tenant/api/TenantModule.kt#L10-L20') }}
        """
        # Parse line range if present
        file_path = file
        line_range = ""
        if '#' in file:
            file_path, line_range = file.split('#', 1)

        github_url = f"https://github.com/airbnb/viaduct/tree/{branch}/{file_path}"
        if line_range:
            github_url += f"#{line_range}"

        # Try to read and embed the actual file (language is inferred automatically)
        return codefile(file_path) + f"\n\n[Open in GitHub]({github_url})"

    @env.macro
    def kdoc(fqcn, display=None):
        """
        Generate a link to Dokka API documentation.

        Args:
            fqcn: Fully qualified class name (e.g., 'viaduct.api.Resolver')
            display: Display text (defaults to class name)

        Example in markdown:
            {{ kdoc('viaduct.api.Resolver', '@Resolver') }}
        """
        parts = fqcn.split(".")
        class_name = parts[-1]
        display_text = display if display else class_name

        # Determine module based on package prefix
        if fqcn.startswith("viaduct.service.api"):
            module = "service/service/service-api"
        elif fqcn.startswith("viaduct.service"):
            module = "service/service/service-wiring"
        elif fqcn.startswith("viaduct.api"):
            module = "tenant-api/-viaduct%20-tenant%20-a-p-i"
        else:
            # Unknown module, return plain code
            return f"`{display_text}`"

        # Build package path (all parts except class name)
        package = ".".join(parts[:-1])

        # Convert class name to URL slug (CamelCase -> kebab-case)
        # e.g., NodeObject -> -node-object -> node-object
        class_slug = re.sub(r'([A-Z])', r'-\1', class_name).lower()

        # Build the documentation URL
        doc_url = f"/apis/{module}/{package}/{class_slug}/"

        return f"[`{display_text}`]({doc_url})"
