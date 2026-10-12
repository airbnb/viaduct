# China-active overhead checker fixture

The fixed-corpus overhead benchmark now uses one always-active China/privacy fixture instead of the generated correctness checker fragments. Production shapes were traced in Treehouse `common/viaduct` and tenant SDL under `projects/viaduct/modules`; no production engine code changed. This replaces benchmark demand, so the timing reduction is a workload correction, not an engine optimization.

## Identity and reproduction

Measured in `~/repos/2ht/projects/viaduct/oss`, branch `rstata--tmp`, at baseline revision `b21ddcf895392fb97fac5004c752a48e8df88215`. Baseline was clean; candidate is uncommitted. [Metadata](metadata.json) records host, CPU, affinity, JVM, corpus and JAR checksums. The retained [candidate patch](candidate.patch) contains tracked and new implementation/test/documentation files relative to the Treehouse root. The [measured source patch](measured-candidate.patch) predates the additional client-value regression and documentation clarification; measured benchmark implementation files are identical in both patches. Evidence files in this directory are supplemental to those patches. JARs and raw JFR remain under `/tmp/runtime2-china-active-20261008`; raw JFR checksum is in the exported bundle. Session identifier from the environment: `01a11dd6-5044-74b1-905c-aa3e3e50222d`. Host `raymie-stata-codex`, KVM Intel Xeon 6975P-C (32 cores / 64 logical CPUs), Corretto 21.0.4+7-LTS for JMH, JMH 1.36. Measurements and Gradle work ran serially.

Build each artifact from the OSS root with:

```sh
./gradlew :core:engine:runtime2:jmhJar --no-configuration-cache --console=plain
```

Copy the resulting `~/repos/2ht/dist/gradle/viaduct-oss/core/build/engine/runtime2/libs/engine-runtime2-2.1.0-SNAPSHOT-jmh.jar` before changing the source. For each of `baseline` and `candidate`, the exact measurement command was:

```sh
java -jar /tmp/runtime2-china-active-20261008/WORKLOAD.jar \
  'viaduct\.engine\.runtime2\.resolution\.ResolverBenchmark\.overhead' \
  -p loopCount=1 -wi 5 -i 8 -f 3 -foe true -rf json \
  -rff /tmp/runtime2-china-active-20261008/WORKLOAD.json \
  -jvmArgsAppend '-DresolverBenchmarkReportFile=/tmp/runtime2-china-active-20261008/WORKLOAD-statistics.txt'
```

One operation resolves all 100 queries. Multiply seconds/op by 10 for milliseconds/query. [Raw iterations](measured-iterations.tsv), JSON results, logs and workload reports are retained for both artifacts.

## Controlled results

| Workload | ms/query, JMH 99.9% error | Relative reduction |
| --- | ---: | ---: |
| Previous correctness-oriented fragments | 14.972 ± 0.093 | Baseline |
| China-active fixture | 11.050 ± 0.097 | 3.922 ms / 26.20% |

| Mean per query | Previous | China active |
| --- | ---: | ---: |
| Field checker executions | 84.94 | 161.27 |
| Type checker executions | 46.48 | 34.07 |
| Named field checker inputs | 424.70 | 154.42 |
| Requested field checker projections (two per named pair) | 849.40 | 308.84 |
| All named checker inputs | Not instrumented | 182.94 |
| Populated object/Query checker roots | Not instrumented | 157.73 |
| Resolver executions, including hidden demands | 280.27 | 115.60 |
| Constructed result fields, including hidden demands | 296.39 | 189.57 |
| Variable-bearing resolver executions | 48.00 | 48.77 |
| Variable stack depth, median | 2 | 2 |
| Result depth, maximum | 7 | 7 |

Field checker projection requests fall 63.64% while field checker executions rise 89.86%. The old five named pairs requested projections even when a root was empty; the new count uses the same definition. The 100 client query sources and original resolver input templates remain fixed. The report traverses constructed OER keys, including hidden input demands, so its field counts do not describe the final client response. A regression test compares client-selected value fingerprints across every query both during discovery and using fresh prepared registries.

## Realized fixture

There are 235 field checkers and 13 privacy-only type checkers. Business field policies include 64 Query-plus-object-ID China rules, five named-null China rules, and 152 default-block rules. Privacy adds 97 constituent policies, and Query has twelve fragment-bearing privacy policies. Default block alone uses zero named pairs; inside a composite it retains a named empty pair. Named inputs per installed field checker average 1.03 with P90=2 and maximum=3; type checkers average 0.85 with P90=1 and maximum=2. Hash and policy helper types have no checkers.

The source census found 898 Query-plus-ID, 57 named-null, and one object-list candidate among 956 custom-China field declarations in the `viaduct` context, after classification exclusions. Their privacy composition was 553 with no additional privacy policy, 396 with one, seven with two. Those are pre-merge/pre-scope declarations, not traffic weights. The fixture applies custom China to about a third of business fields, informed by 956 eligible declarations among 3,123 fields on custom-rule types. Keeping fragmentless default-block checks on the remaining business fields is deliberately conservative; it does not reproduce classification bypass or denial semantics. The thirteen policy lookup outputs model nested user, listing, reservation and two-user-list shapes with passive identifiers, guarded ID providers, active skip providers and an unconditional Query typename. They are built during registry assembly; callbacks return prepared constants. The bounded list tail and the type privacy Query tail are conservative synthetic choices, not fitted production proportions.

Production references from Treehouse root:

- `common/viaduct/tenant/runtime/src/main/kotlin/com/airbnb/viaduct/tenant/runtime/privacy/checker/CheckerProviderFactoryImpl.kt` composes privacy and China for fields; type checkers use privacy only.
- `common/viaduct/tenant/runtime/src/main/kotlin/com/airbnb/viaduct/tenant/runtime/privacy/checker/ChinaAccessCheckConfig.kt` owns eligibility/default-block configuration.
- `common/viaduct/tenant/runtime/src/main/kotlin/com/airbnb/viaduct/tenant/runtime/execution/ClassicVariablesResolver.kt` constructs guarded object-ID providers.
- `common/viaduct/tenant/runtime/src/main/kotlin/com/airbnb/viaduct/tenant/runtime/bootstrap/ClassicDerivedFieldProviderDynamicLoader.kt` wraps China Query fragments with skip guards and typename.
- `core/engine/runtime2/src/main/kotlin/viaduct/engine/runtime2/bootstrap/ExecutorRegistryInputs.kt` recursively combines provider fragments into checker inputs on both roots.

Ordinary generated worlds and the full benchmark retain their duplicated correctness fixture and input assertions. No JMH mode parameter was added.

## Current profile

The measured body was recorded after two unrecorded warmup invocations:

```sh
java -jar /tmp/runtime2-china-active-20261008/candidate.jar \
  'viaduct\.engine\.runtime2\.resolution\.ResolverBenchmark\.overhead' \
  -p loopCount=10 -wi 2 -i 1 -f 1 -foe true \
  -jvmArgsAppend '-DresolutionOverheadProfileOutput=/tmp/runtime2-china-active-20261008/candidate.jfr -DresolverBenchmarkReportFile=/tmp/runtime2-china-active-20261008/profile-statistics.txt -XX:FlightRecorderOptions=stackdepth=256'
core/engine/runtime2/export-resolver-profile.sh \
  /tmp/runtime2-china-active-20261008/candidate.jfr \
  core/engine/runtime2/impldocs/evidence/profiles/2026-10-08-china-active-checkers/candidate
python3 core/engine/runtime2/impldocs/evidence/profiles/2026-10-08-post-cache/analyze-profile.py \
  /tmp/runtime2-china-active-20261008/candidate.jfr \
  core/engine/runtime2/impldocs/evidence/profiles/2026-10-08-china-active-checkers/candidate
python3 core/engine/runtime2/impldocs/evidence/profiles/2026-10-08-post-cache/analyze-components.py \
  /tmp/runtime2-china-active-20261008/candidate.jfr \
  core/engine/runtime2/impldocs/evidence/profiles/2026-10-08-china-active-checkers/candidate/components.json
```

There are 623 execution samples. Inclusive families overlap and are not an additive wall-time partition:

| Inclusive family | Samples | Share |
| --- | ---: | ---: |
| Construction-demand closure | 174 | 27.93% |
| Selection normalization/merge | 142 | 22.79% |
| Successor demand entry | 129 | 20.71% |
| Input materialization | 63 | 10.11% |
| Resolver relation | 55 | 8.83% |
| Nonselective output projection (`snipToDemand`) | 49 | 7.87% |
| Runtime cycle checks | 22 | 3.53% |
| Output conformance/lowering | 20 | 3.21% |

No fixture `ObjectPlan` output construction was sampled. One generated prepared field callback appeared in a sample; the relation share is largely engine output projection. Hot leaves and allocation sites still center on selection forests and temporary list/map/set collections. The profile recorded 18 GC pauses totaling 93.4 ms across 1,000 queries; profiling elapsed time is not the controlled timing result. Demand/forest reuse and cheaper engine-owned output projection are the next candidates to investigate on this corrected workload. This round changes no engine implementation.

## Validation

The initial focused run passed all six tests (three profile-specific and three prepared-output regressions). The full check then passed all four profile tests, including equality of every client-selected value across the 100 queries both during discovery and with fresh prepared registries, and all three prepared-output tests. Full-check result: 2,485 tests, one failure, 166 skipped. The only failure is the acknowledged randomized aggregate type-checker coverage issue: denying worlds missed `ERROR_ARGUMENT_IN_TYPE_INPUT`, seed `8742323394940950761`. Its assertions and generation remain unchanged. The full-check log and focused/failed XML reports are retained. KtLint, Detekt, package-layer and documentation-label checks pass; the separate static-check log is retained.

```sh
./gradlew :core:engine:runtime2:test --tests '*ChinaActiveBenchmarkCheckersTest' --tests '*PrecomputedResolverOutputsTest' :core:engine:runtime2:ktlintFormat :core:engine:runtime2:jmhJar --no-configuration-cache --console=plain
./gradlew :core:engine:runtime2:check :core:engine:runtime2:ktlintFormat --no-configuration-cache --console=plain
./gradlew :core:engine:runtime2:ktlintCheck :core:engine:runtime2:detekt :core:engine:runtime2:checkPackageLayers :core:engine:runtime2:checkDocumentationLabels --no-configuration-cache --console=plain
```
