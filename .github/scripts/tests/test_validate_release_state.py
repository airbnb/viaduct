import json
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from validate_release_state import validate_release_state

_TAG = "snapshot/20260101T000000Z"


class TestValidateReleaseState(unittest.TestCase):

    # --- spec-required test cases ---

    def test_main_snapshot_mode_valid(self):
        """main branch + 0.27.0-SNAPSHOT + snapshot mode → valid"""
        result = validate_release_state("snapshot", "main", "0.27.0-SNAPSHOT", _TAG)
        self.assertTrue(result["is_valid"])
        self.assertFalse(result["is_release_branch"])
        self.assertEqual(result["base_release_version"], "")
        self.assertEqual(result["effective_version"], "0.27.0-SNAPSHOT")
        self.assertEqual(result["validation_error"], "")

    def test_main_snapshot_mode_missing_snapshot_suffix_invalid(self):
        """main branch + 0.27.0 + snapshot mode → invalid (missing -SNAPSHOT)"""
        result = validate_release_state("snapshot", "main", "0.27.0", _TAG)
        self.assertFalse(result["is_valid"])
        self.assertIn("SNAPSHOT", result["validation_error"])

    def test_release_branch_release_mode_exact_version_valid(self):
        """release/v0.27.0 branch + 0.27.0 + release mode → valid"""
        result = validate_release_state("release", "release/v0.27.0", "0.27.0", _TAG)
        self.assertTrue(result["is_valid"])
        self.assertTrue(result["is_release_branch"])
        self.assertEqual(result["base_release_version"], "0.27.0")
        self.assertEqual(result["effective_version"], "0.27.0")

    def test_release_branch_release_mode_snapshot_version_invalid(self):
        """release/v0.27.0 branch + 0.27.0-SNAPSHOT + release mode → invalid"""
        result = validate_release_state("release", "release/v0.27.0", "0.27.0-SNAPSHOT", _TAG)
        self.assertFalse(result["is_valid"])
        self.assertIn("exactly", result["validation_error"])

    def test_release_branch_rc_mode_exact_rc_version_valid(self):
        """release/v0.27.0 branch + 0.27.0-rc.1 + rc mode → valid"""
        result = validate_release_state("rc", "release/v0.27.0", "0.27.0-rc.1", _TAG)
        self.assertTrue(result["is_valid"])
        self.assertTrue(result["is_release_branch"])

    def test_release_branch_rc_snapshot_mode_valid(self):
        """release/v0.27.0 branch + 0.27.0-rc.1-SNAPSHOT + snapshot mode → valid"""
        result = validate_release_state("snapshot", "release/v0.27.0", "0.27.0-rc.1-SNAPSHOT", _TAG)
        self.assertTrue(result["is_valid"])
        self.assertTrue(result["is_release_branch"])

    def test_release_branch_version_mismatch_invalid(self):
        """release/v0.27.0 branch + 0.28.0 + release mode → invalid (version doesn't match branch)"""
        result = validate_release_state("release", "release/v0.27.0", "0.28.0", _TAG)
        self.assertFalse(result["is_valid"])
        self.assertIn("0.27.0", result["validation_error"])

    def test_feature_branch_snapshot_mode_valid(self):
        """Non-release branch (feature/foo) + snapshot mode → valid if VERSION is SNAPSHOT"""
        result = validate_release_state("snapshot", "feature/foo", "0.27.0-SNAPSHOT", _TAG)
        self.assertTrue(result["is_valid"])
        self.assertFalse(result["is_release_branch"])

    # --- is_release_branch field ---

    def test_is_release_branch_true_for_release_branch(self):
        result = validate_release_state("release", "release/v0.27.0", "0.27.0", _TAG)
        self.assertTrue(result["is_release_branch"])

    def test_is_release_branch_false_for_main(self):
        result = validate_release_state("snapshot", "main", "0.27.0-SNAPSHOT", _TAG)
        self.assertFalse(result["is_release_branch"])

    def test_is_release_branch_false_for_feature_branch(self):
        result = validate_release_state("snapshot", "feature/my-feature", "0.27.0-SNAPSHOT", _TAG)
        self.assertFalse(result["is_release_branch"])

    # --- base_release_version field ---

    def test_base_release_version_extracted_on_release_branch(self):
        result = validate_release_state("release", "release/v1.2.3", "1.2.3", _TAG)
        self.assertEqual(result["base_release_version"], "1.2.3")

    def test_base_release_version_empty_on_non_release_branch(self):
        result = validate_release_state("snapshot", "main", "0.27.0-SNAPSHOT", _TAG)
        self.assertEqual(result["base_release_version"], "")

    def test_base_release_version_empty_on_feature_branch(self):
        result = validate_release_state("snapshot", "feature/foo", "0.27.0-SNAPSHOT", _TAG)
        self.assertEqual(result["base_release_version"], "")

    # --- effective_version strips whitespace ---

    def test_effective_version_strips_trailing_newline(self):
        result = validate_release_state("snapshot", "main", "0.27.0-SNAPSHOT\n", _TAG)
        self.assertEqual(result["effective_version"], "0.27.0-SNAPSHOT")

    def test_effective_version_strips_surrounding_whitespace(self):
        result = validate_release_state("snapshot", "main", "  0.27.0-SNAPSHOT  ", _TAG)
        self.assertEqual(result["effective_version"], "0.27.0-SNAPSHOT")

    def test_effective_version_is_pass_through_of_version_content(self):
        result = validate_release_state("release", "release/v0.28.0", "0.28.0\n", _TAG)
        self.assertEqual(result["effective_version"], "0.28.0")

    # --- snapshot_tag field ---

    def test_snapshot_tag_is_passed_through(self):
        result = validate_release_state("snapshot", "main", "0.27.0-SNAPSHOT", _TAG)
        self.assertEqual(result["snapshot_tag"], _TAG)

    def test_snapshot_tag_always_present_even_when_invalid(self):
        result = validate_release_state("snapshot", "main", "0.27.0", _TAG)
        self.assertEqual(result["snapshot_tag"], _TAG)

    def test_snapshot_tag_present_in_release_mode(self):
        result = validate_release_state("release", "release/v0.27.0", "0.27.0", _TAG)
        self.assertEqual(result["snapshot_tag"], _TAG)

    # --- non-release branch in release mode ---

    def test_main_release_mode_invalid(self):
        """Cannot release from non-release branch"""
        result = validate_release_state("release", "main", "0.27.0-SNAPSHOT", _TAG)
        self.assertFalse(result["is_valid"])
        self.assertIn("release/vX.Y.Z", result["validation_error"])

    def test_feature_branch_release_mode_invalid(self):
        result = validate_release_state("release", "feature/foo", "0.27.0", _TAG)
        self.assertFalse(result["is_valid"])
        self.assertIn("release/vX.Y.Z", result["validation_error"])

    def test_feature_branch_rc_mode_invalid(self):
        result = validate_release_state("rc", "feature/foo", "0.27.0-rc.1", _TAG)
        self.assertFalse(result["is_valid"])
        self.assertIn("release/vX.Y.Z", result["validation_error"])

    def test_develop_branch_release_mode_invalid(self):
        result = validate_release_state("release", "develop", "0.27.0", _TAG)
        self.assertFalse(result["is_valid"])

    # --- RC version variants ---

    def test_rc_version_release_mode_invalid(self):
        """RC version should not be publishable in release mode"""
        result = validate_release_state("release", "release/v0.27.0", "0.27.0-rc.1-SNAPSHOT", _TAG)
        self.assertFalse(result["is_valid"])

    def test_rc_version_snapshot_mode_valid(self):
        result = validate_release_state("snapshot", "release/v0.27.0", "0.27.0-rc.2-SNAPSHOT", _TAG)
        self.assertTrue(result["is_valid"])

    def test_rc_version_rc_mode_valid(self):
        result = validate_release_state("rc", "release/v0.27.0", "0.27.0-rc.2", _TAG)
        self.assertTrue(result["is_valid"])

    def test_rc_version_rc_mode_missing_numeric_suffix_invalid(self):
        result = validate_release_state("rc", "release/v0.27.0", "0.27.0-rc", _TAG)
        self.assertFalse(result["is_valid"])
        self.assertIn("rc.N", result["validation_error"])

    def test_rc_version_rc_mode_zero_suffix_invalid(self):
        result = validate_release_state("rc", "release/v0.27.0", "0.27.0-rc.0", _TAG)
        self.assertFalse(result["is_valid"])

    def test_release_version_rc_mode_invalid(self):
        result = validate_release_state("rc", "release/v0.27.0", "0.27.0", _TAG)
        self.assertFalse(result["is_valid"])

    def test_snapshot_version_rc_mode_invalid(self):
        result = validate_release_state("rc", "release/v0.27.0", "0.27.0-SNAPSHOT", _TAG)
        self.assertFalse(result["is_valid"])

    def test_rc_version_without_snapshot_suffix_release_mode_invalid(self):
        """0.27.0-rc.1 is not a valid release version"""
        result = validate_release_state("release", "release/v0.27.0", "0.27.0-rc.1", _TAG)
        self.assertFalse(result["is_valid"])

    # --- release branch version mismatch ---

    def test_release_branch_minor_version_mismatch(self):
        """release/v0.27.0 cannot publish version 0.27.1"""
        result = validate_release_state("release", "release/v0.27.0", "0.27.1", _TAG)
        self.assertFalse(result["is_valid"])

    def test_release_branch_major_version_mismatch(self):
        result = validate_release_state("release", "release/v1.0.0", "2.0.0", _TAG)
        self.assertFalse(result["is_valid"])

    def test_release_branch_snapshot_version_mismatch(self):
        """release/v0.27.0 with 0.28.0-SNAPSHOT in snapshot mode → mismatch"""
        result = validate_release_state("snapshot", "release/v0.27.0", "0.28.0-SNAPSHOT", _TAG)
        self.assertFalse(result["is_valid"])

    def test_release_branch_four_part_version_is_invalid(self):
        """release/v1.0.0 with 1.0.0.1-SNAPSHOT should be invalid (startswith false positive guard)"""
        result = validate_release_state("snapshot", "release/v1.0.0", "1.0.0.1-SNAPSHOT", _TAG)
        self.assertFalse(result["is_valid"])

    # --- branch pattern edge cases ---

    def test_release_branch_without_v_prefix_is_not_release(self):
        """release/0.27.0 (missing 'v') is not a valid release branch"""
        result = validate_release_state("snapshot", "release/0.27.0", "0.27.0-SNAPSHOT", _TAG)
        self.assertFalse(result["is_release_branch"])
        # Treated as non-release branch: VERSION ends with -SNAPSHOT → valid
        self.assertTrue(result["is_valid"])

    def test_release_branch_with_extra_suffix_is_not_release(self):
        """release/v0.27.0-hotfix is not a valid release branch"""
        result = validate_release_state("snapshot", "release/v0.27.0-hotfix", "0.27.0-SNAPSHOT", _TAG)
        self.assertFalse(result["is_release_branch"])

    def test_release_branch_with_prerelease_label_is_not_release(self):
        """release/v0.27.0.1 (four-part) is not a valid release branch"""
        result = validate_release_state("snapshot", "release/v0.27.0.1", "0.27.0-SNAPSHOT", _TAG)
        self.assertFalse(result["is_release_branch"])

    # --- validation_error field ---

    def test_validation_error_empty_when_valid(self):
        result = validate_release_state("snapshot", "main", "0.27.0-SNAPSHOT", _TAG)
        self.assertEqual(result["validation_error"], "")

    def test_validation_error_nonempty_when_invalid(self):
        result = validate_release_state("snapshot", "main", "0.27.0", _TAG)
        self.assertNotEqual(result["validation_error"], "")

    def test_validation_error_mentions_branch_for_release_mode_on_main(self):
        result = validate_release_state("release", "main", "0.27.0", _TAG)
        self.assertIn("main", result["validation_error"])

    # --- output schema completeness ---

    def test_output_contains_all_required_fields(self):
        result = validate_release_state("snapshot", "main", "0.27.0-SNAPSHOT", _TAG)
        expected_keys = {
            "is_release_branch",
            "base_release_version",
            "effective_version",
            "is_valid",
            "validation_error",
            "snapshot_tag",
        }
        self.assertEqual(set(result.keys()), expected_keys)

    def test_output_is_json_serializable(self):
        result = validate_release_state("snapshot", "main", "0.27.0-SNAPSHOT", _TAG)
        json_str = json.dumps(result)
        parsed = json.loads(json_str)
        self.assertEqual(parsed["effective_version"], "0.27.0-SNAPSHOT")
        self.assertIsInstance(parsed["is_valid"], bool)
        self.assertIsInstance(parsed["is_release_branch"], bool)


if __name__ == "__main__":
    unittest.main()
