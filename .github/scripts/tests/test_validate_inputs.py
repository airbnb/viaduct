import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from validate_inputs import validate_json_array


class TestValidateJsonArray(unittest.TestCase):

    # --- valid inputs ---

    def test_valid_single_os(self):
        self.assertEqual(validate_json_array("os", '["ubuntu-latest"]'), ["ubuntu-latest"])

    def test_valid_multiple_os(self):
        self.assertEqual(
            validate_json_array("os", '["ubuntu-latest","macos-latest"]'),
            ["ubuntu-latest", "macos-latest"],
        )

    def test_valid_single_java_version_string(self):
        self.assertEqual(validate_json_array("java_versions", '["21"]', allow_numbers=True), ["21"])

    def test_valid_multiple_java_versions_strings(self):
        self.assertEqual(
            validate_json_array("java_versions", '["11","17","21"]', allow_numbers=True),
            ["11", "17", "21"],
        )

    def test_valid_with_spaces_in_json(self):
        self.assertEqual(
            validate_json_array("java_versions", '["11", "17", "21"]', allow_numbers=True),
            ["11", "17", "21"],
        )

    def test_valid_java_versions_integers(self):
        self.assertEqual(
            validate_json_array("java_versions", "[11, 17, 21]", allow_numbers=True),
            [11, 17, 21],
        )

    def test_valid_java_versions_mixed(self):
        self.assertEqual(
            validate_json_array("java_versions", '["11", 17, 21]', allow_numbers=True),
            ["11", 17, 21],
        )

    # --- invalid JSON ---

    def test_invalid_json_leading_equals(self):
        # the os== double-equals bug in trigger_all_builds.sh produces this
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("os", "=[ubuntu-latest]")
        self.assertIn("not valid JSON", str(ctx.exception))

    def test_invalid_json_bare_string(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("os", "ubuntu-latest")
        self.assertIn("not valid JSON", str(ctx.exception))

    def test_invalid_json_unquoted_array_element(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("os", "[ubuntu-latest]")
        self.assertIn("not valid JSON", str(ctx.exception))

    def test_invalid_json_malformed(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("os", "[")
        self.assertIn("not valid JSON", str(ctx.exception))

    # --- not an array ---

    def test_not_array_plain_string(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("os", '"ubuntu-latest"')
        self.assertIn("must be a JSON array", str(ctx.exception))

    def test_not_array_object(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("os", '{"os": "ubuntu-latest"}')
        self.assertIn("must be a JSON array", str(ctx.exception))

    def test_not_array_integer(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("java_versions", "21")
        self.assertIn("must be a JSON array", str(ctx.exception))

    # --- empty array ---

    def test_empty_array(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("os", "[]")
        self.assertIn("must not be an empty array", str(ctx.exception))

    # --- non-string elements rejected for os ---

    def test_integer_rejected_for_os(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("os", "[21]")
        self.assertIn("array of strings", str(ctx.exception))

    def test_mixed_types_rejected_for_os(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("os", '["ubuntu-latest", 2404]')
        self.assertIn("array of strings", str(ctx.exception))

    # --- non-string/non-integer elements rejected for java_versions ---

    def test_float_rejected_for_java_versions(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("java_versions", "[17.1]", allow_numbers=True)
        self.assertIn("invalid values", str(ctx.exception))

    def test_null_rejected_for_java_versions(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("java_versions", "[null]", allow_numbers=True)
        self.assertIn("invalid values", str(ctx.exception))

    def test_boolean_rejected_for_java_versions(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("java_versions", "[true]", allow_numbers=True)
        self.assertIn("invalid values", str(ctx.exception))

    def test_boolean_rejected_for_os(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("os", "[true]")
        self.assertIn("array of strings", str(ctx.exception))

    def test_hint_present_for_invalid_json(self):
        with self.assertRaises(ValueError) as ctx:
            validate_json_array("os", "=[ubuntu-latest]")
        self.assertIn("Hint", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
