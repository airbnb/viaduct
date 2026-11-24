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
bazel test //projects/viaduct/oss/core/engine/runtime2/src/test/kotlin/viaduct/engine/runtime2/resolution:saved_suite_coverage --test_filter=viaduct.engine.runtime2.resolution.SavedSuiteCoverageExperimentTest --test_env=VIADUCT_RFC271_COVERAGE_EXPERIMENT=true --nocache_test_results --test_output=streamed
```

Without the environment variable, the experimental test is disabled. The `saved_suite_coverage` target is tagged `manual`, `local`, and `no_ci`; it is intended for a developer to run locally. No timeout is raised for the regular test suite or stored in `.bazelrc`.

The test code is in [SavedSuiteCoverageExperimentTest.kt](../../src/test/kotlin/viaduct/engine/runtime2/resolution/SavedSuiteCoverageExperimentTest.kt), and the local-only target is defined in [BUILD.bazel](../../src/test/kotlin/viaduct/engine/runtime2/resolution/BUILD.bazel).

## Compare two candidates and read the page

`compare_resolver_test_suites` replays two candidates and writes a text report and an HTML page. A candidate is a stored suite ID, or `<suite-id>@seed=<n>,size=<S:Q>,config=<name:value;...>` to regenerate the definition with overrides.

```shell
bazel run //projects/viaduct/oss/core/engine/runtime2:compare_resolver_test_suites -- object-fragment@size=30:5 object-fragment --html /tmp/object-fragment.html
```

Read the page in this order:

1. **Not applicable by config.** An obligation shows `n/a` on a side whose generator config cannot produce its evidence. A, X and LOE need argument and object-field variable bindings; FO, FI, DA, LX, DD and NE need recorded provider reads, which only exist with `resolverVariablesEnabled` and an object-field or Query-field provider. A suite with variables off cannot exercise them whatever batches it keeps; pick a variables-enabled suite for those obligations instead of changing the suite's purpose. "Not exercised" means the config could produce the evidence but no case did.
2. **D0 is relative.** "N of M combinations" counts the combinations seen in either candidate, not every combination the obligations could form. For a suite with variables off, the core five can only form the empty signature and {B}, so its D0 comes from the candidate interactions.
3. **D1, D2 and singletons.** D0 ignores support; a suite can reach full D0 with one case per combination. The tiles show D1 and D2 beside D0 and count combinations carried by a single case, which one regenerated schema can drop.

## Select stored batches from a generated pool

A definition with `retainedBatches` stores only the batches that `StoredSuiteSelectionExperimentTest` chose from a larger pool generated with the definition's `counts`. Selection requires every catalog obligation and every attribute matching `selectionAttributePrefixes` with `selectionMinimumCases` distinct cases across `selectionMinimumBatches` batches, and keeps every obligation combination the pool produced with `selectionMinimumCombinationCases` cases. Requirements the pool cannot meet, including obligations the config cannot generate, stay at their attainable support rather than failing the run.

```shell
bazel test //projects/viaduct/oss/core/engine/runtime2/src/test/kotlin/viaduct/engine/runtime2/resolution:saved_suite_coverage --test_filter=viaduct.engine.runtime2.resolution.StoredSuiteSelectionExperimentTest --nocache_test_results --test_output=all
```

The run prints, per suite, pool-versus-selected support for every requirement and every combination, with `n/a` on requirements the config cannot generate. When the selection differs from the stored `retainedBatches`, the test fails naming the definition to update; after updating it, run `materialize_resolver_test_suites <suite-id>` to rewrite the archive. System properties `viaduct.runtime2.selection.poolSchemas`, `minimumCases`, `minimumCombinationCases` and `coverEveryCombination` try other settings without changing the definition, and `viaduct.runtime2.selection.suite` runs selection on a suite that has no retained batches yet, for example to see which core obligations a variables-enabled suite can keep. The test launcher takes them through `JVM_FLAGS`:

```shell
bazel test //projects/viaduct/oss/core/engine/runtime2/src/test/kotlin/viaduct/engine/runtime2/resolution:saved_suite_coverage --test_filter=viaduct.engine.runtime2.resolution.StoredSuiteSelectionExperimentTest --test_env='JVM_FLAGS=-Dviaduct.runtime2.selection.suite=resolution-object-fragment-from-object-field -Dviaduct.runtime2.selection.poolSchemas=200' --nocache_test_results --test_output=all
```

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

## Check that saved tests catch feature removal

A generator setting or coverage count alone does not show that an ARB test checks a feature. A saved test must run the feature, use its result in an expected value, and fail when the feature is disabled. Keep several such tests in multiple batches so regenerating the suite is unlikely to remove all of them. If the report cannot show that a test actually uses the feature, add evidence rather than guessing from the suite name or settings.

| Feature being removed | What a saved test must do | What should fail when the feature is disabled | Current evidence |
| --- | --- | --- | --- |
| Parent-reading resolver inputs | A descendant resolver reads an ancestor resolver's value through its parent input, although the query does not request that ancestor field. The descendant's expected output uses that value. | The descendant's expected output changes or the test fails when the parent input is ignored. Merely selecting a parent field or `__typename` does not count. | The generation setting gives longer ancestor paths a 0.5 chance when they are available. In the saved 1,000-case suite, 602 cases include the path and reach the `result` resolver; 349 do not request `ancestorValue` in the query or child fragments. Ignoring parent inputs changes results in at least five cases across two batches. Disabling `InstallParentBackedgeFields.installParentBackedgeFields` failed at `b007/0bd2eb`. A change that disables only the helper that supplies ancestor fields to descendants still passed, so that narrower code path is not yet covered. |
| Variables in type-checker inputs | A type checker uses a variable in its object or Query input, and the variable gets its value at runtime from an argument, resolver field, or provider. | The checker's expected allow or deny result changes or the test fails when that input value is not supplied. | The `resolution-type-checker-success` suite has 101 qualifying cases across 28 batches. Ignoring checker inputs failed at `b001/e071e2`. |
| Query fragments | A resolver uses a nonempty Query fragment to read a value that the query does not provide elsewhere. Its expected result uses that value. | The expected result changes or the test fails when the fragment value is ignored. A nonempty fragment alone does not count. | Generated resolver outputs now use values read from their Query fragments. The `resolution-query-fragment` suite requires at least five cases where fragment values affect the result, across two batches. Disabling fragment handling failed at `b001/7b78ad`. |
| Nested `fromArgument` paths | A resolver or checker reads a `FromArgument` value through at least two nested input-object fields, then uses it. | The expected result changes or the test fails when nested path reading is disabled. | The `resolution-object-fragment-from-argument-mutation-sensitive` suite has 11 such cases across four batches. Disabling nested path reading failed at `b017/c74156`. |
| Root-field references | A selected field gets its value from a Query resolver through a root-field reference, and the consumer uses that value. | The consumer's expected result changes or the test fails when root-field references are not discovered. A source-provided value alone does not count. | The `resolution-field-checker-root-reference` suite replays 150 cases. Disabling reference discovery failed at `b001/52d978`. |
| Passive checked fields | A field checker runs on a value already supplied by its parent, without running a field resolver for that value. | The checker's expected result changes or the test fails when the checker is skipped for that value. | The `resolution-field-checker-passive` suite has 102 such cases across 26 batches. Skipping the checker failed the focused saved-suite test. |

All six feature-disable checks have a saved test that fails: parent-reading resolver inputs (`b007/0bd2eb` when ancestor-value installation is disabled), type-checker inputs (`b001/e071e2`), query fragments (`b001/7b78ad`), nested `fromArgument` paths (`b017/c74156`), root-field references (`b001/52d978`), and passive checked fields (`b001/cd5171`). The parent profile makes longer ancestor paths likely across the suite without requiring them in every query. Disabling ancestor-value installation makes a saved test fail; disabling only the helper that makes ancestor fields available to descendants still passes, so that specific code path is not protected yet.

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
