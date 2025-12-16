import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from validate_demoapp import is_release_branch_matches_with_version_file


class TestIsReleaseBranchMatchesWithVersionFile(unittest.TestCase):
    def test_exact_match_simple_version(self):
        self.assertTrue(is_release_branch_matches_with_version_file("1.0.0", "release/v1.0.0"))

    def test_exact_match_with_larger_numbers(self):
        self.assertTrue(is_release_branch_matches_with_version_file("10.20.30", "release/v10.20.30"))

    def test_exact_match_with_zeros(self):
        self.assertTrue(is_release_branch_matches_with_version_file("0.0.0", "release/v0.0.0"))

    def test_mismatch_different_major(self):
        self.assertFalse(is_release_branch_matches_with_version_file("2.0.0", "release/v1.0.0"))

    def test_mismatch_different_minor(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.1.0", "release/v1.0.0"))

    def test_mismatch_different_patch(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.1", "release/v1.0.0"))

    def test_version_file_with_snapshot_suffix(self):
        self.assertTrue(is_release_branch_matches_with_version_file("1.0.0-SNAPSHOT", "release/v1.0.0-SNAPSHOT"))

    def test_version_file_with_snapshot_branch_without(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0-SNAPSHOT", "release/v1.0.0"))

    def test_version_file_without_snapshot_branch_with(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0", "release/v1.0.0-SNAPSHOT"))

    def test_version_file_with_rc_suffix(self):
        self.assertTrue(is_release_branch_matches_with_version_file("1.0.0-rc1", "release/v1.0.0-rc1"))

    def test_version_file_with_alpha_suffix(self):
        self.assertTrue(is_release_branch_matches_with_version_file("2.5.0-alpha", "release/v2.5.0-alpha"))

    def test_version_file_with_beta_suffix(self):
        self.assertTrue(is_release_branch_matches_with_version_file("3.0.0-beta.1", "release/v3.0.0-beta.1"))

    def test_not_release_branch_main(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0", "main"))

    def test_not_release_branch_master(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0", "master"))

    def test_not_release_branch_feature(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0", "feature/new-feature"))

    def test_not_release_branch_develop(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0", "develop"))

    def test_not_release_branch_hotfix(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0", "hotfix/v1.0.1"))

    def test_release_branch_missing_v_prefix(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0", "release/1.0.0"))

    def test_empty_version_file(self):
        self.assertFalse(is_release_branch_matches_with_version_file("", "release/v1.0.0"))

    def test_empty_branch_name(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0", ""))

    def test_both_empty(self):
        self.assertFalse(is_release_branch_matches_with_version_file("", ""))

    def test_version_file_with_whitespace_no_match(self):
        self.assertFalse(is_release_branch_matches_with_version_file(" 1.0.0", "release/v1.0.0"))

    def test_version_file_with_trailing_whitespace_no_match(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0 ", "release/v1.0.0"))

    def test_version_file_with_newline_no_match(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0\n", "release/v1.0.0"))

    def test_case_sensitive_version(self):
        self.assertFalse(is_release_branch_matches_with_version_file("1.0.0-SNAPSHOT", "release/v1.0.0-snapshot"))

    def test_version_file_arbitrary_content(self):
        self.assertTrue(is_release_branch_matches_with_version_file("foobar", "release/vfoobar"))

    def test_version_file_with_special_chars(self):
        self.assertTrue(is_release_branch_matches_with_version_file("1.0.0+build.123", "release/v1.0.0+build.123"))

    def test_four_part_version(self):
        self.assertTrue(is_release_branch_matches_with_version_file("1.0.0.0", "release/v1.0.0.0"))

    def test_single_digit_version(self):
        self.assertTrue(is_release_branch_matches_with_version_file("1", "release/v1"))


if __name__ == "__main__":
    unittest.main()
