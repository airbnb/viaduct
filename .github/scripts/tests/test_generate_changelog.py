import unittest
from unittest.mock import patch, MagicMock

from semantic_release.commit_parser.conventional import ConventionalCommitParser
from semantic_release.enums import LevelBump

import sys
from pathlib import Path

# Add parent directory to path so we can import the module
sys.path.insert(0, str(Path(__file__).parent.parent))

from generate_changelog import (
    extract_username_from_email,
    extract_username_from_coauthor,
    clean_commit_message,
    replace_airbnb_marker,
    should_include_commit,
    extract_authors,
    parse_commit,
    group_entries_by_type,
    render_section,
    render_breaking_changes,
    generate_changelog,
    capitalize_first,
    dedupe_entries,
    CommitInfo,
    ChangelogEntry,
    BOT_USERNAMES,
)


class TestExtractUsernameFromEmail(unittest.TestCase):
    def test_valid_email(self):
        self.assertEqual(extract_username_from_email("john.doe@example.com"), "@john.doe")

    def test_email_with_plus(self):
        self.assertEqual(extract_username_from_email("john+test@example.com"), "@john+test")

    def test_empty_email(self):
        self.assertIsNone(extract_username_from_email(""))

    def test_none_email(self):
        self.assertIsNone(extract_username_from_email(None))

    def test_noreply_email(self):
        self.assertIsNone(extract_username_from_email("noreply@github.com"))

    def test_no_reply_email(self):
        self.assertIsNone(extract_username_from_email("no-reply@github.com"))

    def test_github_actions_email(self):
        self.assertIsNone(extract_username_from_email("github-actions@github.com"))

    def test_viaductbot_email(self):
        self.assertIsNone(extract_username_from_email("viaductbot@airbnb.com"))

    def test_invalid_email_no_at(self):
        self.assertIsNone(extract_username_from_email("notanemail"))

    def test_email_with_dash(self):
        self.assertEqual(extract_username_from_email("john-doe@example.com"), "@john-doe")

    def test_email_with_underscore(self):
        self.assertEqual(extract_username_from_email("john_doe@example.com"), "@john_doe")


class TestExtractUsernameFromCoauthor(unittest.TestCase):
    def test_valid_co_author_line(self):
        self.assertEqual(
            extract_username_from_coauthor("John Doe <john.doe@example.com>"),
            "@john.doe"
        )

    def test_co_author_with_full_name(self):
        self.assertEqual(
            extract_username_from_coauthor("Jane Smith <jane.smith@company.com>"),
            "@jane.smith"
        )

    def test_noreply_co_author(self):
        self.assertIsNone(extract_username_from_coauthor("GitHub <noreply@github.com>"))

    def test_github_actions_co_author(self):
        self.assertIsNone(extract_username_from_coauthor("Actions Bot <github-actions@github.com>"))

    def test_viaductbot_co_author(self):
        self.assertIsNone(extract_username_from_coauthor("Viaduct Bot <viaductbot@airbnb.com>"))

    def test_invalid_format_no_email(self):
        self.assertIsNone(extract_username_from_coauthor("John Doe"))

    def test_email_only(self):
        self.assertEqual(extract_username_from_coauthor("<alice@example.com>"), "@alice")

    def test_co_author_with_plus_in_email(self):
        self.assertEqual(
            extract_username_from_coauthor("Bob <bob+test@example.com>"),
            "@bob+test"
        )


class TestCleanCommitMessage(unittest.TestCase):
    def test_removes_github_change_id(self):
        message = "Fix bug Github-Change-Id: 956283"
        result = clean_commit_message(message)
        self.assertEqual(result, "Fix bug")
        self.assertNotIn("Github-Change-Id", result)

    def test_removes_gitorigin_revid(self):
        message = "Update docs GitOrigin-RevId: 1fcdd8123bc49a717103985430322eca3b5b1fb3"
        result = clean_commit_message(message)
        self.assertEqual(result, "Update docs")
        self.assertNotIn("GitOrigin-RevId", result)

    def test_keeps_closes_issue_reference(self):
        message = "Fix parser Closes #137"
        result = clean_commit_message(message)
        self.assertEqual(result, "Fix parser Closes #137")
        self.assertIn("Closes #137", result)

    def test_keeps_fixes_issue_reference(self):
        message = "Fix bug Fixes #456"
        result = clean_commit_message(message)
        self.assertEqual(result, "Fix bug Fixes #456")
        self.assertIn("Fixes #456", result)

    def test_removes_multiple_metadata_patterns(self):
        message = "Feature Github-Change-Id: 123 GitOrigin-RevId: abc123"
        result = clean_commit_message(message)
        self.assertEqual(result, "Feature")
        self.assertNotIn("Github-Change-Id", result)
        self.assertNotIn("GitOrigin-RevId", result)

    def test_mixed_metadata_and_issue_reference(self):
        message = "Build docs Closes #137 Github-Change-Id: 956283"
        result = clean_commit_message(message)
        self.assertEqual(result, "Build docs Closes #137")
        self.assertIn("Closes #137", result)
        self.assertNotIn("Github-Change-Id", result)

    def test_case_insensitive_matching(self):
        message = "Fix GITHUB-CHANGE-ID: 789"
        result = clean_commit_message(message)
        self.assertNotIn("GITHUB-CHANGE-ID", result)


class TestReplaceAirbnbMarker(unittest.TestCase):
    def test_replaces_airbnb_lowercase(self):
        result = replace_airbnb_marker("Fix bug (airbnb)", "abc123")
        self.assertEqual(result, "Fix bug (abc123)")

    def test_replaces_airbnb_uppercase(self):
        result = replace_airbnb_marker("Update feature (AIRBNB)", "def456")
        self.assertEqual(result, "Update feature (def456)")

    def test_replaces_airbnb_mixed_case(self):
        result = replace_airbnb_marker("Add endpoint (AirBnB)", "789abc")
        self.assertEqual(result, "Add endpoint (789abc)")

    def test_no_airbnb_marker(self):
        result = replace_airbnb_marker("Fix normal bug", "123456")
        self.assertEqual(result, "Fix normal bug")
        self.assertNotIn("123456", result)


class TestShouldIncludeCommit(unittest.TestCase):
    def test_includes_normal_commit(self):
        self.assertTrue(should_include_commit("Fix bug in parser"))

    def test_includes_feat_commit(self):
        self.assertTrue(should_include_commit("feat: add new feature"))

    def test_excludes_ignore_lowercase(self):
        self.assertFalse(should_include_commit("ignore: test commit"))

    def test_excludes_ignore_uppercase(self):
        self.assertFalse(should_include_commit("IGNORE: experimental change"))

    def test_excludes_ignore_mixed_case(self):
        self.assertFalse(should_include_commit("Ignore: testing feature"))

    def test_includes_commit_with_ignore_in_middle(self):
        self.assertTrue(should_include_commit("Fix: ignore whitespace in parser"))


class TestExtractAuthors(unittest.TestCase):
    def test_single_author_no_coauthors(self):
        commit = CommitInfo(
            sha="abc123",
            message="Fix bug",
            body='',
            author_email="john.doe@example.com",
            co_authors_raw=""
        )
        authors = extract_authors(commit)
        self.assertEqual(authors, ["@john.doe"])

    def test_author_with_single_coauthor(self):
        commit = CommitInfo(
            sha="abc123",
            message="Add feature",
            body='',
            author_email="john.doe@example.com",
            co_authors_raw="Jane Smith <jane.smith@example.com>"
        )
        authors = extract_authors(commit)
        self.assertEqual(authors, ["@john.doe", "@jane.smith"])

    def test_author_with_multiple_coauthors(self):
        commit = CommitInfo(
            sha="abc123",
            message="Refactor code",
            body='',
            author_email="alice@example.com",
            co_authors_raw="Bob <bob@example.com>|Charlie <charlie@example.com>"
        )
        authors = extract_authors(commit)
        self.assertEqual(authors, ["@alice", "@bob", "@charlie"])

    def test_bot_author_filtered_out(self):
        commit = CommitInfo(
            sha="abc123",
            message="Update deps",
            body='',
            author_email="noreply@github.com",
            co_authors_raw=""
        )
        authors = extract_authors(commit)
        self.assertEqual(authors, [])

    def test_bot_coauthor_filtered_out(self):
        commit = CommitInfo(
            sha="abc123",
            message="Merge PR",
            body='',
            author_email="john@example.com",
            co_authors_raw="GitHub Actions <github-actions@github.com>"
        )
        authors = extract_authors(commit)
        self.assertEqual(authors, ["@john"])

    def test_all_bots_filtered_out(self):
        commit = CommitInfo(
            sha="abc123",
            message="Auto update",
            body='',
            author_email="noreply@github.com",
            co_authors_raw="Bot <viaductbot@airbnb.com>"
        )
        authors = extract_authors(commit)
        self.assertEqual(authors, [])

    def test_duplicate_author_and_coauthor_deduplicated(self):
        commit = CommitInfo(
            sha="abc123",
            message="Fix bug",
            body='',
            author_email="john@example.com",
            co_authors_raw="John <john@example.com>"
        )
        authors = extract_authors(commit)
        self.assertEqual(authors, ["@john"])

    def test_mixed_valid_and_invalid_coauthors(self):
        commit = CommitInfo(
            sha="abc123",
            message="Team effort",
            body='',
            author_email="lead@example.com",
            co_authors_raw="Dev1 <dev1@example.com>|Bot <noreply@github.com>|Dev2 <dev2@example.com>"
        )
        authors = extract_authors(commit)
        self.assertEqual(authors, ["@lead", "@dev1", "@dev2"])


class TestChangelogEntry(unittest.TestCase):
    def test_formatted_authors_with_authors(self):
        entry = ChangelogEntry(
            sha="abc123",
            message="Fix bug",
            authors=["@john", "@jane"],
            change_type="fix",
            scope=None,
            description="Fix bug",
            is_breaking=False,
            breaking_description=None,
            bump=LevelBump.PATCH,
        )
        self.assertEqual(entry.formatted_authors, "@john, @jane")

    def test_formatted_authors_empty(self):
        entry = ChangelogEntry(
            sha="abc123",
            message="Fix bug",
            authors=[],
            change_type="fix",
            scope=None,
            description="Fix bug",
            is_breaking=False,
            breaking_description=None,
            bump=LevelBump.PATCH,
        )
        self.assertEqual(entry.formatted_authors, "@anonymous")

    def test_formatted_entry(self):
        entry = ChangelogEntry(
            sha="abc123",
            message="Fix bug",
            authors=["@john"],
            change_type="fix",
            scope=None,
            description="Fix bug",
            is_breaking=False,
            breaking_description=None,
            bump=LevelBump.PATCH,
        )
        self.assertEqual(entry.formatted_entry, "Fix bug by @john")


class TestParseCommit(unittest.TestCase):
    def setUp(self):
        self.parser = ConventionalCommitParser()

    def test_conventional_commit_fix(self):
        commit = CommitInfo(
            sha="abc123",
            message="fix: bug in parser",
            body='',
            author_email="john.doe@example.com",
            co_authors_raw=""
        )
        entry = parse_commit(commit, self.parser)
        self.assertIsNotNone(entry)
        self.assertEqual(entry.change_type, "fix")
        self.assertEqual(entry.bump, LevelBump.PATCH)
        self.assertIn("@john.doe", entry.authors)

    def test_conventional_commit_feat(self):
        commit = CommitInfo(
            sha="abc123",
            message="feat: add new feature",
            body='',
            author_email="john.doe@example.com",
            co_authors_raw=""
        )
        entry = parse_commit(commit, self.parser)
        self.assertIsNotNone(entry)
        self.assertEqual(entry.change_type, "feat")
        self.assertEqual(entry.bump, LevelBump.MINOR)

    def test_breaking_change(self):
        commit = CommitInfo(
            sha="abc123",
            message="fix!: breaking change",
            body='',
            author_email="john.doe@example.com",
            co_authors_raw=""
        )
        entry = parse_commit(commit, self.parser)
        self.assertIsNotNone(entry)
        self.assertTrue(entry.is_breaking)
        self.assertEqual(entry.bump, LevelBump.MAJOR)

    def test_breaking_change_with_body(self):
        commit = CommitInfo(
            sha="abc123",
            message="fix!: breaking change",
            body="BREAKING CHANGE: This change breaks the thing.",
            author_email="john.doe@example.com",
            co_authors_raw=""
        )
        entry = parse_commit(commit, self.parser)
        self.assertIsNotNone(entry)
        self.assertEqual(entry.bump, LevelBump.MAJOR)
        self.assertTrue(entry.is_breaking)
        self.assertEqual(entry.breaking_description, "This change breaks the thing.")

    def test_ignored_commit_returns_none(self):
        commit = CommitInfo(
            sha="abc123",
            message="ignore: test commit",
            body='',
            author_email="john.doe@example.com",
            co_authors_raw=""
        )
        entry = parse_commit(commit, self.parser)
        self.assertIsNone(entry)

    def test_metadata_cleaned(self):
        commit = CommitInfo(
            sha="abc123",
            message="fix: bug Github-Change-Id: 12345",
            body='',
            author_email="john.doe@example.com",
            co_authors_raw=""
        )
        entry = parse_commit(commit, self.parser)
        self.assertIsNotNone(entry)
        self.assertNotIn("Github-Change-Id", entry.description)

    def test_airbnb_marker_replaced(self):
        commit = CommitInfo(
            sha="abc123",
            message="fix: bug (AIRBNB)",
            body='',
            author_email="john.doe@example.com",
            co_authors_raw=""
        )
        entry = parse_commit(commit, self.parser)
        self.assertIsNotNone(entry)
        self.assertIn("(abc123)", entry.message)
        self.assertNotIn("AIRBNB", entry.message)


    def test_pr_reference_preserved_in_description(self):
        commit = CommitInfo(
            sha="abc123",
            message="ci: add standalone demoapp tests workflow (#296)",
            body='',
            author_email="john.doe@example.com",
            co_authors_raw=""
        )
        entry = parse_commit(commit, self.parser)
        self.assertIsNotNone(entry)
        self.assertIn("(#296)", entry.description)


class TestGroupEntriesByType(unittest.TestCase):
    def test_groups_by_type(self):
        entries = [
            ChangelogEntry("a", "m1", [], "fix", None, "d1", False, None, LevelBump.PATCH),
            ChangelogEntry("b", "m2", [], "feat", None, "d2", False, None, LevelBump.MINOR),
            ChangelogEntry("c", "m3", [], "fix", None, "d3", False, None, LevelBump.PATCH),
        ]
        grouped = group_entries_by_type(entries)
        self.assertEqual(len(grouped["fix"]), 2)
        self.assertEqual(len(grouped["feat"]), 1)

    def test_non_conventional_goes_to_chore(self):
        entries = [
            ChangelogEntry("a", "m1", [], None, None, "d1", False, None, LevelBump.NO_RELEASE),
        ]
        grouped = group_entries_by_type(entries)
        self.assertEqual(len(grouped["chore"]), 1)


class TestRenderSection(unittest.TestCase):
    def test_render_section(self):
        entries = [
            ChangelogEntry("a", "m1", ["@john"], "fix", None, "Fix bug 1", False, None, LevelBump.PATCH),
            ChangelogEntry("b", "m2", ["@jane"], "fix", None, "Fix bug 2", False, None, LevelBump.PATCH),
        ]
        result = render_section("fix", entries)
        self.assertIn("## Bug Fixes", result)
        self.assertIn("- Fix bug 1 by @john", result)
        self.assertIn("- Fix bug 2 by @jane", result)

    def test_render_section_capitalizes_lowercase_descriptions(self):
        entries = [
            ChangelogEntry("a", "m1", ["@john"], "fix", None, "fix bug in parser", False, None, LevelBump.PATCH),
            ChangelogEntry("b", "m2", ["@jane"], "fix", None, "add validation", False, None, LevelBump.PATCH),
        ]
        result = render_section("fix", entries)
        self.assertIn("- Fix bug in parser by @john", result)
        self.assertIn("- Add validation by @jane", result)


class TestDedupeEntries(unittest.TestCase):
    def _entry(self, sha, description, authors=("@geo",), change_type="feat"):
        return ChangelogEntry(
            sha=sha,
            message=description,
            authors=list(authors),
            change_type=change_type,
            scope=None,
            description=description,
            is_breaking=False,
            breaking_description=None,
            bump=LevelBump.MINOR,
        )

    def test_drops_pr_duplicate_when_sha_twin_exists(self):
        sha_entry = self._entry("3b4c18b7", "strict missing-resolver validation at startup (3b4c18b7)")
        pr_entry = self._entry("0000000", "strict missing-resolver validation at startup (#333)")
        result = dedupe_entries([sha_entry, pr_entry])
        self.assertEqual(result, [sha_entry])

    def test_drops_pr_duplicate_regardless_of_order(self):
        sha_entry = self._entry("3b4c18b7", "strict missing-resolver validation at startup (3b4c18b7)")
        pr_entry = self._entry("0000000", "strict missing-resolver validation at startup (#333)")
        result = dedupe_entries([pr_entry, sha_entry])
        self.assertEqual(result, [sha_entry])

    def test_keeps_pr_entry_when_no_sha_twin(self):
        # No internal commit pair — keep the PR entry as-is
        pr_entry = self._entry("0000000", "external contribution (#400)", authors=("@external",))
        result = dedupe_entries([pr_entry])
        self.assertEqual(result, [pr_entry])

    def test_does_not_dedupe_when_authors_differ(self):
        # Same description but different authors — treat as distinct changes
        sha_entry = self._entry("3b4c18b7", "rename SomeClass (3b4c18b7)", authors=("@alice",))
        pr_entry = self._entry("0000000", "rename SomeClass (#333)", authors=("@bob",))
        result = dedupe_entries([sha_entry, pr_entry])
        self.assertEqual(result, [sha_entry, pr_entry])

    def test_does_not_dedupe_when_descriptions_differ(self):
        a = self._entry("aaaaaaaa", "fix parser bug (aaaaaaaa)")
        b = self._entry("bbbbbbbb", "fix lexer bug (bbbbbbbb)")
        result = dedupe_entries([a, b])
        self.assertEqual(result, [a, b])

    def test_keeps_two_sha_entries_with_same_description(self):
        # Two distinct internal commits with identical descriptions —
        # neither has a (#NNN) marker, so neither is the dedupe target.
        a = self._entry("aaaaaaaa", "bump deps (aaaaaaaa)")
        b = self._entry("bbbbbbbb", "bump deps (bbbbbbbb)")
        result = dedupe_entries([a, b])
        self.assertEqual(result, [a, b])

    def test_preserves_relative_order_of_kept_entries(self):
        e1 = self._entry("11111111", "first change (11111111)")
        sha_entry = self._entry("3b4c18b7", "shared change (3b4c18b7)")
        pr_entry = self._entry("0000000", "shared change (#333)")
        e2 = self._entry("22222222", "later change (22222222)")
        result = dedupe_entries([e1, pr_entry, sha_entry, e2])
        self.assertEqual(result, [e1, sha_entry, e2])


class TestCapitalizeFirst(unittest.TestCase):
    def test_capitalizes_lowercase_first_char(self):
        self.assertEqual(capitalize_first("fix bug"), "Fix bug")

    def test_preserves_already_capitalized(self):
        self.assertEqual(capitalize_first("Fix bug"), "Fix bug")

    def test_preserves_rest_of_string(self):
        # Don't lowercase subsequent capitals (acronyms, type names, etc.)
        self.assertEqual(capitalize_first("rename GraphQL types"), "Rename GraphQL types")

    def test_empty_string(self):
        self.assertEqual(capitalize_first(""), "")

    def test_single_char(self):
        self.assertEqual(capitalize_first("a"), "A")

    def test_non_letter_first_char_unchanged(self):
        self.assertEqual(capitalize_first("(scope) fix"), "(scope) fix")


class TestGenerateChangelog(unittest.TestCase):
    def _commit(self, sha, message, author_email="john.doe@example.com", body="", co_authors_raw=""):
        return CommitInfo(
            sha=sha,
            message=message,
            body=body,
            author_email=author_email,
            co_authors_raw=co_authors_raw,
        )

    @patch("generate_changelog.get_commits_between_tags")
    def test_omits_version_header(self, mock_get_commits):
        mock_get_commits.return_value = iter([
            self._commit("abc1234", "feat: add a thing"),
        ])
        changelog = generate_changelog("v0.31.0", "HEAD")
        self.assertNotIn("# Version", changelog)

    @patch("generate_changelog.get_commits_between_tags")
    def test_empty_changelog(self, mock_get_commits):
        mock_get_commits.return_value = iter([])
        changelog = generate_changelog("v1.2.2", "HEAD")
        self.assertEqual(changelog, "No changes.\n")


class TestRenderBreakingChanges(unittest.TestCase):
    def test_render_breaking_changes(self):
        entries = [
            ChangelogEntry("a", "m1", ["@john"], "fix", None, "subject description", True, "body trailer description", LevelBump.MAJOR),
        ]
        result = render_breaking_changes(entries)
        self.assertIn("## Breaking Changes", result)
        self.assertIn("Subject description", result)
        self.assertNotIn("body trailer description", result)
        self.assertIn("@john", result)

    def test_breaking_change_uses_subject_with_sha(self):
        # subject description already has SHA from (AIRBNB) substitution — body trailer does not
        entries = [
            ChangelogEntry("abc1234", "m1", ["@alice"], "refactor", None, "rename SomeClass (abc1234)", True, "SomeClass renamed to OtherClass.", LevelBump.MAJOR),
        ]
        result = render_breaking_changes(entries)
        self.assertIn("Rename SomeClass (abc1234)", result)
        self.assertNotIn("SomeClass renamed to OtherClass.", result)


if __name__ == '__main__':
    unittest.main()
