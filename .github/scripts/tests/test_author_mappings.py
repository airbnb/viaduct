import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).parent.parent))

import author_mappings


SAMPLE_MAPPINGS_CONTENT = '''
AUTHOR_MAPPINGS = {
    "Alice Example <alice.example@internal.com>": "Alice Example <alicex@users.noreply.github.com>",
    "Bob Example <bob.example@internal.com>": "Bob Example <bobx@users.noreply.github.com>",
    "carol_example <carol.example@external.com>": "Carol Example <carolx@users.noreply.github.com>",
}
'''

SAMPLE_MAPPINGS = {
    "alice.example@internal.com": "alicex",
    "bob.example@internal.com": "bobx",
    "carol.example@external.com": "carolx",
}


class TestParseFile(unittest.TestCase):
    def test_parses_all_entries(self):
        with tempfile.NamedTemporaryFile(mode='w', suffix='.sky', delete=False) as f:
            f.write(SAMPLE_MAPPINGS_CONTENT)
            path = Path(f.name)
        result = author_mappings._parse_file(path)
        self.assertEqual(result, SAMPLE_MAPPINGS)

    def test_skips_malformed_entries(self):
        content = '''
AUTHOR_MAPPINGS = {
    "Valid Name <valid@example.com>": "Valid Name <validuser@users.noreply.github.com>",
    "no-angle-brackets": "also-no-brackets",
}
'''
        with tempfile.NamedTemporaryFile(mode='w', suffix='.sky', delete=False) as f:
            f.write(content)
            path = Path(f.name)
        result = author_mappings._parse_file(path)
        self.assertEqual(result, {"valid@example.com": "validuser"})


class TestResolve(unittest.TestCase):
    def test_exact_match(self):
        result = author_mappings._resolve("alice.example@internal.com", SAMPLE_MAPPINGS)
        self.assertEqual(result, "alicex")

    def test_exact_match_external_email(self):
        result = author_mappings._resolve("carol.example@external.com", SAMPLE_MAPPINGS)
        self.assertEqual(result, "carolx")

    def test_linear_search_substring(self):
        # entry_email is substring of provided email (edge case: extra prefix/suffix)
        result = author_mappings._resolve("noreply+alice.example@internal.com", SAMPLE_MAPPINGS)
        self.assertEqual(result, "alicex")

    def test_no_match_returns_none(self):
        result = author_mappings._resolve("unknown@example.com", SAMPLE_MAPPINGS)
        self.assertIsNone(result)

    def test_empty_mappings_returns_none(self):
        result = author_mappings._resolve("raymie.stata@airbnb.com", {})
        self.assertIsNone(result)

    def test_empty_email_returns_none(self):
        result = author_mappings._resolve("", SAMPLE_MAPPINGS)
        self.assertIsNone(result)


class TestMapEmail(unittest.TestCase):
    def setUp(self):
        author_mappings._cached_mappings = None

    def test_returns_mapped_username_when_file_found(self):
        with tempfile.NamedTemporaryFile(mode='w', suffix='.sky', delete=False) as f:
            f.write(SAMPLE_MAPPINGS_CONTENT)
            path = Path(f.name)
        with patch.object(author_mappings, '_find_file', return_value=path):
            result = author_mappings.map_email("alice.example@internal.com")
        self.assertEqual(result, "alicex")

    def test_returns_none_when_no_file(self):
        with patch.object(author_mappings, '_find_file', return_value=None):
            result = author_mappings.map_email("alice.example@internal.com")
        self.assertIsNone(result)

    def test_returns_none_when_email_not_in_mappings(self):
        with tempfile.NamedTemporaryFile(mode='w', suffix='.sky', delete=False) as f:
            f.write(SAMPLE_MAPPINGS_CONTENT)
            path = Path(f.name)
        with patch.object(author_mappings, '_find_file', return_value=path):
            result = author_mappings.map_email("unknown@example.com")
        self.assertIsNone(result)

    def test_returns_none_on_parse_error(self):
        with patch.object(author_mappings, '_find_file', return_value=Path('/nonexistent/path.sky')):
            result = author_mappings.map_email("alice.example@internal.com")
        self.assertIsNone(result)

    def test_returns_none_for_empty_email(self):
        with patch.object(author_mappings, '_find_file', return_value=None):
            result = author_mappings.map_email("")
        self.assertIsNone(result)

    def test_file_parsed_once_across_multiple_calls(self):
        with tempfile.NamedTemporaryFile(mode='w', suffix='.sky', delete=False) as f:
            f.write(SAMPLE_MAPPINGS_CONTENT)
            path = Path(f.name)
        mock_find = MagicMock(return_value=path)
        with patch.object(author_mappings, '_find_file', mock_find):
            author_mappings.map_email("alice.example@internal.com")
            author_mappings.map_email("bob.example@internal.com")
            author_mappings.map_email("unknown@example.com")
        mock_find.assert_called_once()


if __name__ == '__main__':
    unittest.main()
