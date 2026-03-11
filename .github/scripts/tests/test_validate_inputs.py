import json
import os
import tempfile
import unittest
import sys
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent.parent))

from validate_inputs import validate_json_array, write_outputs, main, OS_DEFAULT, JAVA_DEFAULT


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


class TestWriteOutputs(unittest.TestCase):

    def _write_to_temp(self, fn):
        """Run fn() with a temp file as GITHUB_OUTPUT, return the file contents."""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.txt', delete=False) as f:
            output_path = f.name
        try:
            with patch.dict(os.environ, {'GITHUB_OUTPUT': output_path}):
                fn()
            return Path(output_path).read_text()
        finally:
            Path(output_path).unlink(missing_ok=True)

    def test_writes_first_os_and_first_java(self):
        content = self._write_to_temp(
            lambda: write_outputs(["ubuntu-latest", "macos-latest"], ["11", "17", "21"])
        )
        self.assertIn("coverage_os=ubuntu-latest\n", content)
        self.assertIn("coverage_java=11\n", content)

    def test_coverage_fields_are_only_first_element(self):
        content = self._write_to_temp(
            lambda: write_outputs(["ubuntu-latest", "macos-latest"], ["11", "17", "21"])
        )
        # coverage_os and coverage_java must be single values, not lists
        self.assertIn("coverage_os=ubuntu-latest\n", content)
        self.assertIn("coverage_java=11\n", content)
        self.assertNotIn("coverage_os=macos-latest", content)
        self.assertNotIn("coverage_java=17", content)

    def test_integer_java_written_as_string(self):
        content = self._write_to_temp(
            lambda: write_outputs(["ubuntu-latest"], [11, 17, 21])
        )
        self.assertIn("coverage_java=11\n", content)

    def test_no_op_when_github_output_not_set(self):
        with patch.dict(os.environ, {'GITHUB_OUTPUT': ''}):
            # Should not raise; empty GITHUB_OUTPUT is treated as unset
            write_outputs(["ubuntu-latest"], ["11"])

    def test_has_wide_true_for_multiple_combinations(self):
        content = self._write_to_temp(
            lambda: write_outputs(["ubuntu-latest", "macos-latest"], ["17", "11", "21"])
        )
        self.assertIn("has_wide=true\n", content)

    def test_has_wide_false_for_single_combination(self):
        content = self._write_to_temp(
            lambda: write_outputs(["ubuntu-latest"], ["17"])
        )
        self.assertIn("has_wide=false\n", content)

    def test_wide_matrix_excludes_deep_coordinate(self):
        content = self._write_to_temp(
            lambda: write_outputs(["ubuntu-latest", "macos-latest"], ["17", "11"])
        )
        wide = json.loads(content.split("wide_matrix=", 1)[1].split("\n", 1)[0])
        deep_coord = {"os": "ubuntu-latest", "java": "17"}
        self.assertNotIn(deep_coord, wide)

    def test_wide_matrix_contains_remaining_coordinates(self):
        content = self._write_to_temp(
            lambda: write_outputs(["ubuntu-latest", "macos-latest"], ["17", "11"])
        )
        wide = json.loads(content.split("wide_matrix=", 1)[1].split("\n", 1)[0])
        # 2 os × 2 java = 4 total; minus 1 deep coord = 3 wide
        self.assertEqual(len(wide), 3)
        self.assertIn({"os": "ubuntu-latest", "java": "11"}, wide)
        self.assertIn({"os": "macos-latest", "java": "17"}, wide)
        self.assertIn({"os": "macos-latest", "java": "11"}, wide)

    def test_wide_matrix_empty_for_single_coordinate(self):
        content = self._write_to_temp(
            lambda: write_outputs(["ubuntu-latest"], ["17"])
        )
        wide = json.loads(content.split("wide_matrix=", 1)[1].split("\n", 1)[0])
        self.assertEqual(wide, [])

    def test_integer_java_in_wide_matrix_written_as_string(self):
        content = self._write_to_temp(
            lambda: write_outputs(["ubuntu-latest", "macos-latest"], [17, 11])
        )
        wide = json.loads(content.split("wide_matrix=", 1)[1].split("\n", 1)[0])
        # All java values in wide_matrix should be strings
        for coord in wide:
            self.assertIsInstance(coord["java"], str)


class TestMainCoverageOutput(unittest.TestCase):

    def _run_main(self, os_input="", java_input=""):
        """Run main() and return (exit_code, GITHUB_OUTPUT file contents)."""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.txt', delete=False) as f:
            output_path = f.name
        try:
            env = {'OS_INPUT': os_input, 'JAVA_INPUT': java_input, 'GITHUB_OUTPUT': output_path}
            with patch.dict(os.environ, env):
                exit_code = main()
            return exit_code, Path(output_path).read_text()
        finally:
            Path(output_path).unlink(missing_ok=True)

    def test_defaults_when_no_inputs(self):
        exit_code, content = self._run_main()
        self.assertEqual(exit_code, 0)
        self.assertIn(f"coverage_os={OS_DEFAULT[0]}\n", content)
        self.assertIn(f"coverage_java={JAVA_DEFAULT[0]}\n", content)

    def test_custom_os_uses_first_element(self):
        exit_code, content = self._run_main(os_input='["macos-latest","ubuntu-latest"]')
        self.assertEqual(exit_code, 0)
        self.assertIn("coverage_os=macos-latest\n", content)
        self.assertNotIn("coverage_os=ubuntu-latest", content)

    def test_custom_java_uses_first_element(self):
        exit_code, content = self._run_main(java_input='["21","17","11"]')
        self.assertEqual(exit_code, 0)
        self.assertIn("coverage_java=21\n", content)
        self.assertNotIn("coverage_java=17", content)
        self.assertNotIn("coverage_java=11", content)

    def test_integer_java_input_written_as_string(self):
        exit_code, content = self._run_main(java_input="[21, 17, 11]")
        self.assertEqual(exit_code, 0)
        self.assertIn("coverage_java=21\n", content)

    def test_invalid_os_does_not_write_coverage_point(self):
        exit_code, content = self._run_main(os_input="not-valid-json")
        self.assertEqual(exit_code, 1)
        self.assertNotIn("coverage_os", content)
        self.assertNotIn("coverage_java", content)

    def test_invalid_java_does_not_write_coverage_point(self):
        exit_code, content = self._run_main(java_input="not-valid-json")
        self.assertEqual(exit_code, 1)
        self.assertNotIn("coverage_os", content)
        self.assertNotIn("coverage_java", content)


if __name__ == "__main__":
    unittest.main()
