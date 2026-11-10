# How to run and use RFC-271 saved-suite results

Use this guide to record the saved test groups, read a new report, and decide what to change. A suite is a named group of saved test cases; a case is one test scenario. A resolver is the code that supplies a value for a GraphQL field.

## Run the regular test

The regular test path fully replays three representative suites and checks their resolver behavior plus CSV and JSON round trips. It does not replay every saved case.

```shell
bazel test //projects/viaduct/oss/core/engine/runtime2/src/test/kotlin/viaduct/engine/runtime2/resolution:resolution --test_filter=viaduct.engine.runtime2.resolution.SavedSuiteCoverageExperimentTest --nocache_test_results --test_output=all
```

## Run the local experimental sample

The experimental mode is opt-in and local. It measures one deterministic case from each saved suite, checks the CSV and JSON round trips, and prints sample metrics. It is much cheaper than replaying every case, but its results are partial: do not use them as complete suite scores or to decide that a behavior is missing.

```shell
bazel test //projects/viaduct/oss/core/engine/runtime2/src/test/kotlin/viaduct/engine/runtime2/resolution:saved_suite_coverage --test_filter=viaduct.engine.runtime2.resolution.SavedSuiteCoverageExperimentTest --test_env=VIADUCT_RFC271_COVERAGE_EXPERIMENT=true --nocache_test_results --test_strategy=local --test_output=streamed
```

Without the environment variable, the experimental test is disabled. The `saved_suite_coverage` target is tagged `manual`, `local`, and `no_ci`; it is intended for a developer to run locally. No timeout is raised for the regular test suite or stored in `.bazelrc`.

The test code is in [SavedSuiteCoverageExperimentTest.kt](../../src/test/kotlin/viaduct/engine/runtime2/resolution/SavedSuiteCoverageExperimentTest.kt), and the local-only target is defined in [BUILD.bazel](../../src/test/kotlin/viaduct/engine/runtime2/resolution/BUILD.bazel).

## Compare source lines across batches

The RFC-271 report measures resolver behavior. For source-line overlap, run the separate local tool below. It starts one Bazel coverage run per saved batch, so each output file contains lines reached by that batch's cases. The tool limits instrumentation to Runtime2 main sources and does not affect normal CI.

```shell
python3 projects/viaduct/oss/core/engine/runtime2/scripts/saved_suite_line_overlap.py construction-witness
```

The output directory is `/tmp/rfc271-line-overlap/<suite-id>` by default. Each `.info` file contains one batch's LCOV data. `batch-summary.csv` reports executed lines and lines unique among the measured batches; `batch-overlaps.json` lists shared `source-file:line` values and pairwise Jaccard similarity. Pass `--batch-id <id>` to measure one batch or repeat it to compare a subset. This run can take time because each batch is replayed separately.

Line overlap is a separate signal from the RFC-271 score. Batches can execute the same line while checking different inputs or results, and a line being executed does not show that a test asserted its behavior.

## Read each result line

Read the named fields in each `RFC-271 suite=...` line this way:

| Output field | What to do with it |
| --- | --- |
| `cases` | In the regular test, check how many cases were replayed out of the suite's total. This is the amount tested, not a quality score. |
| `sampledCases` | In the local experiment, check how many cases were sampled out of the suite's total. One sampled case is not a full-suite score. |
| `rows` | Treat this as the number of field occurrences recorded, including some where no resolver ran. Use it to understand the size of the recording, not to rank suites. |
| `dependencies` | Treat this as the number of times one field used a value from another field. The FO and FI behavior counts show which cases had the important shapes. |
| `coreSupport` | Compare A, FO, FI, X, and B counts with the minimums you wrote down before the run. |
| `supplementalSupport` | Check only the extra behaviors you decided to require; do not assume all must appear in each suite. |
| `observedAttributes` | Use these recorded field and resolver features to see what kinds of tests ran. Make a feature a requirement only if the test plan says it must be present. |
| `D0`, `D1`, `D2`, `dominantShare` | Use these to decide whether the suite repeats the same behavior combinations or covers a wider mix. They are clues for inspection, not pass/fail scores. |
| `similarProfilePairs` | Use a high count as a reason to compare the underlying tests. This number does not identify which tests match. |
| `csvRoundTrip`, `reportRoundTrip` | Require both to be `true`. If the target fails or either value is false, fix the recording before using its numbers. |

## Check the run before interpreting it

1. Confirm the saved-suite test target passed; if it failed, do not use partial output to make decisions.
2. In experimental mode, confirm there is one sample line for every suite registered in `ResolverTestSuiteDefinition`; a missing line means the sample run is incomplete.
3. Confirm the CSV and JSON checks passed for every sampled case; a failed check means the recorded report cannot be trusted until the failure is fixed.
4. Use the behavior counts to describe what ran, not to claim that the tests are correct; the suite's ordinary correctness checks must pass separately.
5. Use the same saved-suite definitions and report version when comparing two sample runs; if either changed, record that difference before interpreting changes. Use a complete replay before making decisions about suite quality.

## Decide what the suite set must cover

Before editing tests, write down why each suite exists, including correctness checks and whether it tests allowing, rejecting, or mixed checker results. The measurement does not record all of those purposes, so protect them with explicit required suite IDs or other tests that prove the same thing.

For the full set of tests you plan to keep, declare a minimum number of different cases for each behavior that must remain. Require all five RFC-271 behaviors in the combined set and write down a minimum case count for each. Add any extra behavior or resolver feature as a requirement only when the test strategy needs it; do not require every behavior in every suite by default.

| Label | Behavior to require when relevant |
| --- | --- |
| A | One field uses both an argument from the query and a value stored on its object. |
| FO | The value produced by one field is used by at least two other fields. |
| FI | One field uses values produced by at least two other fields. |
| X | One test case uses query arguments and stored object values. |
| B | One test case includes both successful field results and an error result. |

The report also counts these additional behaviors. Decide which matter for the suite set, then set a minimum for each one you require.

| Additional behavior | What it means |
| --- | --- |
| List-element object-field error | A field on an item in a list uses a value from that item and returns an error. |
| Nested resolver output | A field returns an object whose nested fields are also supplied by resolvers. |
| Nested field reads parent | A nested field uses a value supplied by a field on its parent object. |
| Read crosses list depth | One field uses a value from another field at a different list depth. |
| Dependency diamond | One field's value is used by two fields, and both are used by another field. |
| Null or error reaches consumer | A field reads a value from another field, and that value is null or an error. |

Resolver features describe what the tests use, such as where a field runs, what kind of inputs it has, and what it returns. They are descriptive until you choose one as a requirement and set its minimum case count.

## Compare a proposed change

The experimental command above reports one sampled case from each registered saved suite. It does not decide whether a proposed suite change meets your requirements. For a comparison, add or update a test that calls `compareSavedSuiteCandidate` for one suite, or `compareSavedSuiteSetCandidate` for a change to the full set. Both functions replay the saved suites with the same correctness checks before comparing them. `ResolverTestSuite.load` reads and unzips the saved suite archives, so the test can load separate saved versions and compare their measurements.

For a single-suite comparison, give the test the old and proposed versions of that suite and the list of behaviors or features it must keep, with a minimum case count for each. For a full-set comparison, also list suite IDs that must remain because their purpose is required. Read the comparison result only after the proposed version passes every requirement; missing required suites or insufficient case counts make it unacceptable regardless of gains elsewhere.

There is no separate command-line option for choosing the baseline and proposed suites; the comparison is called from a test. The example test compares the saved `construction-witness` suite with the saved `empty-object-fragment` suite and checks that the latter is rejected when it lacks a required behavior. Run it with:

```shell
bazel test //projects/viaduct/oss/core/engine/runtime2/src/test/kotlin/viaduct/engine/runtime2/resolution:resolution \
  --test_filter=viaduct.engine.runtime2.resolution.StoredSuiteCoverageExampleTest \
  --nocache_test_results \
  --test_output=all
```

The comparison functions are in [SavedSuiteCoverage.kt](../../src/testFixtures/kotlin/viaduct/engine/runtime2/coverage/SavedSuiteCoverage.kt). [StoredSuiteCoverageExampleTest.kt](../../src/test/kotlin/viaduct/engine/runtime2/resolution/StoredSuiteCoverageExampleTest.kt) shows how to load and compare two saved suite archives.

## Interpret each new report

1. **Check required behaviors first.** For each required behavior, count the cases that show it in the combined set you plan to keep. If a count is below its minimum, reject the proposed set or add or change tests until it meets the minimum.
2. **Compare the right things.** When changing cases inside one suite, compare that suite with its previous version. When adding or removing a suite, compare the whole current set with the whole proposed set; another suite may already cover the same behavior.
3. **Inspect case-level changes.** Look at which test cases were added, removed, or changed, which behaviors each case shows, and where the behavior occurred. A matching case ID or count alone does not show that two tests check the same thing.
4. **Use D0, D1, D2, the most-common share, and similarity only to find cases to inspect.** They describe whether cases repeat the same behavior combinations. Higher variety is not automatically better, and lower variety is not automatically a reason to remove a test.
5. **Inspect combinations that appear or disappear.** If D0 rises, check whether the new combinations test behaviors the suite is meant to cover. If D0 falls, inspect the lost combinations and verify that no requirement or test purpose disappeared. Use D1 and D2 to see whether the overall mix changed or whether most cases still repeat the same combinations.
6. **Treat high similarity as a prompt to compare tests.** It compares behavior combinations within one suite, not whole suites. Read the test cases and compare what they prove; do not remove a test based on a similarity value alone.
7. **Compare size only after all requirements pass.** Compare the number of kept suites, test cases, and schemas. A proposed set is smaller by these measures when none goes up and at least one goes down. If one count drops while another rises, decide from the protected test purposes and correctness needs.
8. **Update the comparison point after accepting a change.** Record what you expected, which tests or suites changed, which requirements passed, and what remains missing. Use that accepted version as the starting point for the next experiment.

If a required behavior has no cases, first create a known example and confirm the measurement recognizes it. If a behavior appears in nearly every case, inspect examples and narrow its definition if it cannot distinguish useful tests. Do not treat a surprising count as proof that a test is missing or redundant until the measured examples make sense.

## What the diversity numbers mean

A **behavior combination** is the set of RFC-271 behaviors seen in one test case. The diversity numbers count these combinations; they do not measure code coverage, test correctness, or resolver features.

**D0** counts how many different combinations appear. **D1** estimates how many equally common combinations would give about the same spread as the observed cases. **D2** gives more weight to combinations that appear often, so rare combinations affect it less. Higher values mean more variety or a more even mix, not better tests.

The **most-common share** is the fraction of cases with the most frequent combination. A lower share means fewer tests repeat that one combination; it does not show whether the other combinations are useful.

**Jaccard similarity** compares two combinations by dividing the number of behaviors they share by the total number of behaviors in either combination. A high value means the combinations are alike; it does not show that the tests are duplicates.

The case counts for different behaviors overlap because one test can show several behaviors. Do not add the counts from different behavior rows to estimate the number of unique tests.
