import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from validate_release_inputs import validate_release_inputs


class TestValidateReleaseInputs(unittest.TestCase):

    def test_rc_inputs_valid(self):
        result = validate_release_inputs("1.2.3", "rc.1", False, "")
        self.assertTrue(result["is_valid"])
        self.assertEqual(result["publish_mode"], "rc")
        self.assertEqual(result["published_version"], "1.2.3-rc.1")
        self.assertEqual(result["source_ref"], "release/v1.2.3")
        self.assertEqual(result["destination_branch"], "rc/v1.2.3-rc.1")
        self.assertFalse(result["is_final"])

    def test_final_inputs_valid(self):
        result = validate_release_inputs("1.2.3", "", True, "notes")
        self.assertTrue(result["is_valid"])
        self.assertEqual(result["publish_mode"], "release")
        self.assertEqual(result["published_version"], "1.2.3")
        self.assertEqual(result["source_ref"], "release/v1.2.3")
        self.assertEqual(result["destination_branch"], "main")
        self.assertTrue(result["is_final"])

    def test_release_version_must_be_semver_triplet(self):
        result = validate_release_inputs("1.2", "rc.1", False, "")
        self.assertFalse(result["is_valid"])
        self.assertIn("X.Y.Z", result["validation_error"])

    def test_cannot_pass_final_and_rc_ver(self):
        result = validate_release_inputs("1.2.3", "rc.1", True, "notes")
        self.assertFalse(result["is_valid"])
        self.assertIn("either final=true or rc_ver", result["validation_error"])

    def test_non_final_requires_rc_ver(self):
        result = validate_release_inputs("1.2.3", "", False, "")
        self.assertFalse(result["is_valid"])
        self.assertIn("rc_ver is required", result["validation_error"])

    def test_rc_ver_must_have_numeric_suffix(self):
        result = validate_release_inputs("1.2.3", "rc", False, "")
        self.assertFalse(result["is_valid"])
        self.assertIn("rc.N", result["validation_error"])

    def test_rc_ver_must_be_positive(self):
        result = validate_release_inputs("1.2.3", "rc.0", False, "")
        self.assertFalse(result["is_valid"])

    def test_final_requires_release_notes(self):
        result = validate_release_inputs("1.2.3", "", True, "")
        self.assertFalse(result["is_valid"])
        self.assertIn("release_notes are required", result["validation_error"])

    def test_input_whitespace_is_trimmed(self):
        result = validate_release_inputs(" 1.2.3 ", " rc.2 ", False, " ")
        self.assertTrue(result["is_valid"])
        self.assertEqual(result["published_version"], "1.2.3-rc.2")

    def test_output_is_json_serializable(self):
        result = validate_release_inputs("1.2.3", "rc.1", False, "")
        parsed = json.loads(json.dumps(result))
        self.assertEqual(parsed["published_version"], "1.2.3-rc.1")
        self.assertIsInstance(parsed["is_valid"], bool)
        self.assertIsInstance(parsed["is_final"], bool)


if __name__ == "__main__":
    unittest.main()
