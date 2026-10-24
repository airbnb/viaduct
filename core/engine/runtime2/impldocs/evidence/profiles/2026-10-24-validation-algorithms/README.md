# Validation algorithms and traversal fusion

This round profiles all stack levels of the frozen `propertyTestBenchmark` and retains one traversal fusion. On `raymie-stata-codex` (Intel Xeon Platinum 8375C at 2.90 GHz, 32 cores/64 logical CPUs), serial unprofiled controls show **4.89% less whole-case time**, confirmed by **3.52% less time with the variant order reversed**. Errors below are JMH's 99.9% confidence half-widths; intervals overlap. No measurements were discarded. The diagnostic profiles show combined validation changing from **307.646 to 279.147 ms/case**, including recording overhead.

## Workload and reproduction

The worktree is `/home/raymie_stata/repos/1ht`, branch `rstata--validate`, parent revision `a59cc0d5cec425`, session `01a16f8d-0ac7-7fb3-805d-8fe2daf78f79`. Commands run from `/home/raymie_stata/repos/1ht/projects/viaduct/oss`. The JVM is Corretto 17.0.11+9-LTS, JMH is 1.36, and blackholes are compiler blackholes. One property case includes one Resolution and all correctness, identity and binding oracles, with 12,777 resolver applications. The executable corpus is unchanged. [source-state.json](source-state.json) records source/corpus/JAR hashes, timestamps and exact profile commands. The baseline JAR is preserved from `c86257f45b0b4f`; executable sources equal the parent, whose changes add evidence only. The candidate was measured uncommitted; reproduce it from the parent plus [source.patch](source.patch). Its new mutation test was added after building the timing JAR; the support implementation did not change, and final focused testing found support compilation up-to-date.

Build the candidate with `./gradlew :core:engine:runtime2:jmhJar --no-configuration-cache --console=plain`. Both controls use one case/op, five warmup iterations, eight measured iterations per fork, three forks and one JMH/Resolution thread. [fusion-comparison.json](fusion-comparison.json) and [fusion-reverse-comparison.json](fusion-reverse-comparison.json) preserve exact Java commands, hashes and all 24 measured iterations per variant. The matching console logs and raw JMH JSON are beside them. **In the reverse comparison, `before` is the candidate and `after` is the baseline**; its script-generated reduction field consequently has the opposite meaning. Builds, probes, tests and profiles were outside the serial unprofiled comparisons.

| Order | Baseline, s/case | Fused, s/case | Mean reduction |
| --- | ---: | ---: | ---: |
| Baseline then candidate | 0.516944236 ± 0.025407664 | 0.491662014 ± 0.017876986 | 4.89% |
| Candidate then baseline | 0.507884384 ± 0.021991471 | 0.490016017 ± 0.015946476 | 3.52% |

Each profile uses one unrecorded 100-case warmup, one recorded 100-case iteration, one fork and stack depth 256. The baseline measured iteration is 55.231143712 s/op, or 552.311437 ms/case; the candidate is 52.386359644 s/op, or 523.863596 ms/case. These have no JMH error estimate and include JFR overhead. Raw recordings remain outside Git at `/tmp/runtime2-validation-algorithms-20261024/current.jfr` and `fusion.jfr`. [before-profile](before-profile) and [after-profile](after-profile) retain standard exports and recording checksums. Standard stack exports use depth 64; the all-level analysis uses the full 256-depth export. Maximum validation stack depths are 107 and 124 frames, respectively, with no samples reaching 256.

To reproduce the full-stack analysis, export each recording with `jfr print --json --events qplan.PropertyTestPhase,jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ObjectAllocationSample,jdk.GCPhasePause --stack-depth 256 RECORDING.jfr > EVENTS.json`, then run `python3 analyze.py EVENTS.json PREFIX`. Run `python3 inspect-stacks.py BEFORE_PREFIX-samples.json AFTER_PREFIX-samples.json OUTPUT.json` for the inclusive groups. [all-level-analysis.json](all-level-analysis.json) retains the resulting group counts, hot leaves and callers; [current-analysis.json](current-analysis.json) and [fusion-analysis.json](fusion-analysis.json) retain every phase duration and phase-specific CPU/allocation summaries. Samples are assigned by phase-event time and require a Runtime2 frame; allocation summaries exclude the first carry-in allocation sample per thread. Deferred GC CPU is outside the validation CPU denominator. Group shares include callees and overlap; they must not be added or interpreted as possible savings.

## Findings at all stack levels

The baseline contains 2,359 validation CPU samples: 1,636 correctness, 572 identity and 151 binding. Hot leaves include equality (`Intrinsics.areEqual`, 170 samples/7.21%), map lookup (`HashMap.getNode`, 161/6.83%), cell activation checks (155/6.57%), variable enumeration (111/4.71%) and selection conformance (109/4.62%). Their callers distribute these costs across several traversals; optimizing a leaf alone would miss the duplicated algorithmic work.

| Inclusive region | Baseline validation samples | Fused validation samples |
| --- | ---: | ---: |
| Demand-closure walk, including replay | 1,406 / 59.60% | 0 |
| Value-conformance walk; includes demand after fusion | 226 / 9.58% | 1,337 / 64.75% |
| Resolver replay | 1,137 / 48.20% | 1,058 / 51.23% |
| Occurrence walks, including callbacks | 681 / 28.87% | 682 / 33.03% |
| Input materialization | 342 / 14.50% | 317 / 15.35% |
| Selection-presence checking | 309 / 13.10% | 299 / 14.48% |
| Checked-selection validation | 67 / 2.84% | 68 / 3.29% |
| Variable enumeration | 186 / 7.88% | 170 / 8.23% |
| Input fingerprints | 125 / 5.30% | 123 / 5.96% |
| Stored-key lookup | 108 / 4.58% | 119 / 5.76% |

The fused profile has 2,065 validation samples: 1,343 correctness, 564 identity and 158 binding. Its largest leaves remain equality (145/7.02%), map lookup (128/6.20%), activation checks (124/6.00%), selection conformance (96/4.65%) and map insertion (93/4.50%). The conformance region changes scope after fusion and cannot be compared directly with its former 9.58% share. Root-reference witness lookup has no samples in either profile; that is not evidence of zero cost in other workloads.

## Retained fusion

Previously `correctResolution` ran demand closure and then resolver-value conformance. Both recursively classified source ownership, walked completed objects/lists, grounded keys, read cells and consulted the same replay cache. A temporary one-case probe found **8,176 objects and 13,408 keys in each walk**, with exactly the same object identities and no duplicate objects within either walk. [fusion-probe.txt](fusion-probe.txt) and [fusion-probe.patch](fusion-probe.patch) preserve that probe. All counters were removed before measurements.

The retained change performs demand checks inside the existing value-conformance walk. Each reached object's type-checker raw demand and each completed field's checker raw demand are checked; active registered resolver input demand is checked before its replay. Existing recursive conformance preserves source-supplied field ownership, parent backedges, grounding, argument errors, null/error/list shape, nested Query validation and root-reference witnesses. Standalone demand and value predicates remain available, and the fused path shares their extracted demand checks. Deterministic relation replay and independent identity reconstruction remain enabled.

| Profile phase | Baseline, ms/case | Fused, ms/case |
| --- | ---: | ---: |
| Preparation | 4.667 | 4.485 |
| Resolution | 239.818 | 240.045 |
| Correctness | 218.294 | 187.702 |
| Identity | 71.959 | 72.761 |
| Binding | 17.392 | 18.684 |
| Combined validation | 307.646 | 279.147 |

The diagnostic correctness phase is **14.01% shorter** and combined validation is **9.26% shorter**. Resolution is essentially unchanged. Performance claims for the complete case come from the unprofiled controls above.

## Remaining fusion candidates

1. **Presence, checker validation and input materialization.** `fragmentsSatisfiedBy`, `conformsToCheckedSelectionsAt` and `materializeResult` revisit selected input trees in resolver replay. Identity reconstruction also checks presence before materializing. This is the strongest remaining traversal-fusion hypothesis. Construction selections and materialization selections have different carriers: a combined validator/projector must retain aliases, inclusion guards, symbolic-key lookup, raw checker inputs, denial alternatives and rejection of missing demand before resolver callbacks. The probe counted 66,090 selection-object visits but only 23,455 object/forest identity pairs; these totals include empty forests and do not establish a nonempty-cache hit rate.
2. **Materialization and fingerprinting in the identity oracle.** The oracle constructs a temporary EOD and immediately traverses it to fingerprint it. A fused projected fingerprint could avoid intermediate objects, provided canonical sorted field encoding, response keys, type tags, error/null/list handling and node/character bounds remain identical. Expected identities must still be reconstructed independently from completed results rather than copied from invocation observations.
3. **Identity and binding occurrence enumeration.** Both enumerate occurrences, so sharing traversal mechanics may help, while keeping their expectations independent. Binding is only 18.684 ms/case in the final profile; much of the occurrence-walk inclusive cost belongs to callbacks, which sharing a walk would retain. This has a smaller demonstrated opportunity than input preparation.

Previously discarded variable-enumeration and materialized-equality changes remain low priority: their earlier controls showed small, inconclusive gains. The current leaf costs do not overturn that evidence.

## Validation

Focused `*correctresolution.*` tests pass, including the new nested-list cases for valid results, missing resolver demand, wrong active output, wrong passive source and wrong list size. They check literal outcomes, separate predicates, the fused predicate and public correctness validation. Existing tests cover checker inputs and denials, symbolic bindings, argument errors, Query owners, references, parent backedges, missing-input rejection before callbacks and per-judgment replay. `./gradlew :core:engine:runtime2:check` passes: **3,688 reported tests, 168 skipped, no failures/errors**. [fusion-focused-final.txt](fusion-focused-final.txt), [fusion-check.txt](fusion-check.txt) and [test-results.json](test-results.json) retain the results. There are no new production source files or changes to `src/main`.
