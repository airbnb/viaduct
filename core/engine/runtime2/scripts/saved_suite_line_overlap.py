#!/usr/bin/env python3
"""Run Bazel line coverage once per saved-suite batch and compare the line sets."""

import argparse
import csv
import itertools
import json
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Optional


SUITE_ARCHIVES = Path(
    "projects/viaduct/oss/core/engine/runtime2/src/testFixtures/resources/"
    "viaduct/engine/runtime2/property-tests/suites"
)
COVERAGE_TARGET = (
    "//projects/viaduct/oss/core/engine/runtime2/src/test/kotlin/"
    "viaduct/engine/runtime2/resolution:saved_suite_batch_line_coverage"
)
SOURCE_ROOT = Path("projects/viaduct/oss/core/engine/runtime2/src/main/kotlin")
INSTRUMENTATION_FILTER = (
    "^//projects/viaduct/oss/core/engine/runtime2/src/main/kotlin/"
)


def suite_batches(repo_root: Path, suite_id: str) -> list[dict[str, object]]:
    archive = repo_root / SUITE_ARCHIVES / f"{suite_id}.zip"
    if not archive.is_file():
        raise ValueError(f"No saved-suite archive at {archive}")
    with zipfile.ZipFile(archive) as source:
        manifest = json.loads(source.read("manifest.json"))
    batches = manifest.get("batches", [])
    if not batches:
        raise ValueError(f"Saved suite {suite_id} has no batches")
    return batches


def line_set(lcov_path: Path, repo_root: Path) -> set[str]:
    lines: set[str] = set()
    source_file: Optional[str] = None
    for record in lcov_path.read_text().splitlines():
        if record.startswith("SF:"):
            source = Path(record[3:])
            if not source.is_absolute():
                source = repo_root / source
            try:
                source_file = source.resolve().relative_to(repo_root / SOURCE_ROOT).as_posix()
            except ValueError:
                source_file = None
        elif record.startswith("DA:") and source_file is not None:
            values = record[3:].split(",")
            if len(values) >= 2 and int(values[1]) > 0:
                lines.add(f"{source_file}:{values[0]}")
    return lines


def safe_file_part(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]", "_", value)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Record Runtime2 source lines by batch and report overlap."
    )
    parser.add_argument("suite_id", help="Saved suite ID, for example construction-witness")
    parser.add_argument(
        "--batch-id",
        action="append",
        help="Measure only this batch; repeat to compare a subset. By default, measure every batch.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="Output directory (defaults to a suite-specific directory under the system temp directory).",
    )
    args = parser.parse_args()

    repo_root = Path(
        subprocess.check_output(
            ["git", "rev-parse", "--show-toplevel"],
            cwd=Path(__file__).resolve().parent,
            text=True,
        ).strip()
    )
    batches = suite_batches(repo_root, args.suite_id)
    known_batch_ids = {str(batch["id"]) for batch in batches}
    if len(known_batch_ids) != len(batches):
        raise ValueError(f"Saved suite {args.suite_id} has duplicate batch IDs")
    selected_batch_ids = args.batch_id or sorted(known_batch_ids)
    if len(selected_batch_ids) != len(set(selected_batch_ids)):
        raise ValueError("Specify each batch ID only once")
    unknown_batch_ids = set(selected_batch_ids) - known_batch_ids
    if unknown_batch_ids:
        raise ValueError(f"Unknown batch IDs for {args.suite_id}: {sorted(unknown_batch_ids)}")
    report_names = [f"{safe_file_part(batch_id)}.info" for batch_id in selected_batch_ids]
    if len(report_names) != len(set(report_names)):
        raise ValueError("Batch IDs produce colliding coverage report filenames")

    output_dir = args.output_dir or Path(tempfile.gettempdir()) / "rfc271-line-overlap" / args.suite_id
    output_dir.mkdir(parents=True, exist_ok=True)
    execution_root = Path(
        subprocess.check_output(
            ["bazel", "info", "execution_root"], cwd=repo_root, text=True
        ).strip()
    )
    combined_report = execution_root / "bazel-out/_coverage/_coverage_report.dat"
    case_counts = {
        str(batch["id"]): len(batch["queries"])
        for batch in batches
    }
    line_sets: dict[str, set[str]] = {}

    for batch_id in selected_batch_ids:
        print(f"Recording {args.suite_id}/{batch_id}", flush=True)
        combined_report.unlink(missing_ok=True)
        subprocess.run(
            [
                "bazel",
                "coverage",
                COVERAGE_TARGET,
                f"--instrumentation_filter={INSTRUMENTATION_FILTER}",
                "--combined_report=lcov",
                "--nocache_test_results",
                "--test_output=all",
                "--test_env=VIADUCT_RFC271_BATCH_LINE_COVERAGE=true",
                f"--test_env=VIADUCT_RFC271_BATCH_LINE_COVERAGE_SUITE={args.suite_id}",
                f"--test_env=VIADUCT_RFC271_BATCH_LINE_COVERAGE_BATCH={batch_id}",
            ],
            cwd=repo_root,
            check=True,
        )
        if not combined_report.is_file():
            raise RuntimeError(f"Bazel did not produce the coverage report: {combined_report}")
        batch_report = output_dir / f"{safe_file_part(batch_id)}.info"
        shutil.copyfile(combined_report, batch_report)
        line_sets[batch_id] = line_set(batch_report, repo_root)
        if not line_sets[batch_id]:
            raise RuntimeError(
                f"No Runtime2 main-source lines were recorded for {args.suite_id}/{batch_id}; "
                "check the Bazel instrumentation filter and LCOV source paths"
            )

    all_lines = set().union(*line_sets.values()) if line_sets else set()
    summary_path = output_dir / "batch-summary.csv"
    with summary_path.open("w", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(["batch_id", "cases", "executed_lines", "lines_unique_to_measured_batches"])
        for batch_id in selected_batch_ids:
            other_lines = set().union(*(lines for other_id, lines in line_sets.items() if other_id != batch_id))
            writer.writerow(
                [batch_id, case_counts[batch_id], len(line_sets[batch_id]), len(line_sets[batch_id] - other_lines)]
            )

    overlap_rows = []
    for left, right in itertools.combinations(selected_batch_ids, 2):
        shared = sorted(line_sets[left] & line_sets[right])
        union = line_sets[left] | line_sets[right]
        overlap_rows.append(
            {
                "batch_a": left,
                "batch_b": right,
                "shared_line_count": len(shared),
                "jaccard": len(shared) / len(union) if union else 1.0,
                "shared_lines": shared,
            }
        )
    overlaps_path = output_dir / "batch-overlaps.json"
    overlaps_path.write_text(json.dumps(overlap_rows, indent=2) + "\n")

    print(f"Measured {len(selected_batch_ids)} batches; {len(all_lines)} distinct Runtime2 source lines.")
    print(f"Batch counts: {summary_path}")
    print(f"Pairwise shared lines: {overlaps_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1) from error
