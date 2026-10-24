# Validation helpers within sampled stacks

This fresh profile examines the retained duplicate-replay and output-demand-cache implementation. No implementation changes or probes were introduced in this round. The 100-case recording starts at 2026-10-24 21:25:55 UTC on `raymie-stata-codex`, Intel Xeon Platinum 8375C at 2.90 GHz, 32 cores/64 logical CPUs, in session `01a16f8d-0ac7-7fb3-805d-8fe2daf78f79`. Source is `f44da2cfb48962f36fb5d29cd9cf044a4f9804fd` plus [source.patch](source.patch). The tree was uncommitted; that revision alone is insufficient to reproduce it. [source-state.json](source-state.json) retains source, corpus, recording and JAR hashes. All previously recorded candidate source hashes and the JAR hash match the preceding demand-cache round.

The benchmark uses Corretto 17.0.11+9-LTS, JMH 1.36, compiler blackholes, one JMH thread, one Resolution thread, one fork, one unrecorded warmup and one recorded measurement, each with `loopCount=100`. One operation contains 100 frozen property cases and top-level resolutions, each with 12,777 resolver applications and all validators enabled. Warmup is 58.011 s/op; the measured iteration and final JMH score are 61.349 s/op, or 0.613490 s/case, with no error estimate. These are diagnostic profile timings, not a controlled performance comparison. The successful invocation is retained in [profile-run.txt](profile-run.txt).

Commands from `/home/raymie_stata/repos/1ht/projects/viaduct/oss`:

```sh
./gradlew :core:engine:runtime2:propertyTestProfile --console=plain --no-configuration-cache -PpropertyTestBenchmarkLoopCount=100 -PpropertyTestProfileOutput=/tmp/runtime2-validation-20261024/middle-stack.jfr
core/engine/runtime2/export-resolver-profile.sh /tmp/runtime2-validation-20261024/middle-stack.jfr /tmp/runtime2-validation-20261024/middle-stack-export
jfr print --json --events qplan.PropertyTestPhase,jdk.ExecutionSample,jdk.NativeMethodSample --stack-depth 256 /tmp/runtime2-validation-20261024/middle-stack.jfr > /tmp/runtime2-validation-20261024/middle-stack-cpu.json
python3 /tmp/runtime2-validation-20261024/analyze-middle.py /tmp/runtime2-validation-20261024/middle-stack-cpu.json /tmp/runtime2-validation-20261024/middle-stack-analysis
python3 /tmp/runtime2-validation-20261024/inspect-middle.py
python3 /tmp/runtime2-validation-20261024/analyze-blocking.py /tmp/runtime2-validation-20261024/middle-stack-analysis
```

The analysis scripts are retained here as [analyze-cpu.py](analyze-cpu.py), [inspect-helpers.py](inspect-helpers.py) and [analyze-blocking.py](analyze-blocking.py). The helper script reads the raw directory above. Raw JFR, full event JSON, compact phase-attributed stacks and the measured JAR remain outside Git in that directory/worktree. [profile](profile) contains the maintained exporter output, including recording checksum, phase events and aggregated stacks. Its standard stack views use depth 64; the separate CPU analysis uses the full recorded depth of 256. Standard allocation exports retain carry-in allocation weights; this round makes no allocation claims.

## Phase and sampling context

| Phase | Mean ms/case | Runtime2 CPU samples |
| --- | ---: | ---: |
| Request preparation | 4.552 | 34 |
| Resolution | 261.542 | 1,334 |
| Witness snapshot | 0.013 | 0 |
| Correctness judgment | 231.686 | 1,758 |
| Application identity oracle | 85.394 | 699 |
| From-field binding oracle | 30.141 | 243 |

Combined validation is 347.221 ms/case in this diagnostic recording. The three validation phases contain 2,700 CPU samples, all on the benchmark worker; Resolution samples are on `resolution-1-1`. Samples are attributed using non-overlapping phase-event timestamps, retaining only stacks with Runtime2 frames. No retained stack is marked truncated, and the deepest has 123 frames. [cpu-analysis.json](cpu-analysis.json) retains every method's inclusive, self and middle-stack counts without the earlier top-70 cutoff. A method or helper family counts once per sample even when recursive. JVM method names aggregate overloads. Nested inclusive shares overlap and must not be added.

## Best next target: blocking bridges in validation

The outer benchmark uses `runBlocking`, so its naive inclusive count covers essentially all validation and cannot identify overhead. Instead, [blocking.json](blocking.json) isolates samples executing nested bridge plumbing: locate the innermost `BuildersKt__BuildersKt.runBlocking` implementation, require no Runtime2 frame above it, and identify its Runtime2 caller below it. Samples executing Runtime2 coroutine bodies are excluded. This finds **389/2,700 samples (14.41%)** in context construction, continuation interception, coroutine lifecycle and event-loop work.

| Bridge call site | Plumbing samples | Share of all validation |
| --- | ---: | ---: |
| Object-cell activation in occurrence walker | 111 | 4.11% |
| List-cell activation in occurrence walker | 79 | 2.93% |
| Identity input materialization/fingerprinting | 72 | 2.67% |
| Correctness relation replay | 66 | 2.44% |
| Correctness object-input materialization | 61 | 2.26% |

A representative sampled chain, caller to leaf, is:

```text
visitRegisteredResolverOccurrences$visit:112
  runBlocking
    AbstractCoroutine.start
      startCoroutineCancellable
        ContinuationImpl.intercepted
          DispatchedContinuation.<init>
            threadContextElements
              CoroutineContext.fold
                ThreadContextKt$countAll$1.invoke
```

The strongest first experiment is [RegisteredResolverOccurrence.kt](../../../../src/support/kotlin/viaduct/engine/runtime2/contract/RegisteredResolverOccurrence.kt):112 and :144. Each object/list cell starts a fresh blocking coroutine solely to call `fetchActivated()`. Together these sites account for **190 samples (7.04% of validation)**, including 101/243 binding-oracle samples. Make the recursive walk suspending internally and bridge once at its synchronous entry point, preserving waiting for undecided activation, skipping false activation and all value/binding checks. `checkActivated()` is not an equivalent replacement: false activation is valid here. Further experiments could fuse the materialization/relation bridges within each replay and move identity materialization under a coarser suspending boundary. The 14.41% aggregate is observed plumbing cost, not a promised speedup or a saving attributable entirely to the walker.

## Other helpers hidden by leaf rankings

[helpers.json](helpers.json) and [helpers.txt](helpers.txt) retain phase breakdowns, direct callers, descendant leaves, overlap counts and representative stacks.

| Helper family | Inclusive samples / all validation | Self samples in family | Specific opportunity |
| --- | ---: | ---: | --- |
| Input materialization | 341 / 12.63% | 50 | Reduce repeated projection work, path copying and small object/map construction; investigate reuse only for identical result, selection and consumer semantics. |
| Structural selection conformance | 313 / 11.59% | 114 | Probe repeated `(result, normalized selections)` checks within one judgment; examine path-list allocation. |
| `generatedHashObject` | 245 / 9.07% | 1 | Resolve canonical hash-object type/field descriptors when assembling the plan; build each input-dependent hash output directly from those descriptors. |
| Variable enumeration | 165 / 6.11% | 74 | Avoid constructing variable sets for ground arguments and use one collector for recursive symbolic arguments/forests. |
| Materialized-value equality | 130 / 4.81% | 61 | Avoid reflexive traversal of the first observed invocation; reduce selection-set allocation. |
| Stored-key lookup | 122 / 4.52% | 23 | Reduce repeated contextual-grounding scans while preserving exact symbolic-key preference and grounded fallback. |
| Input fingerprints | 120 / 4.44% | 2 | Reduce intermediate strings and sorting while preserving the exact structural encoding and bounds. |

These are opportunities for probes, not measured candidate implementations:

- [ArgumentExpressions.kt](../../../../src/main/kotlin/viaduct/engine/runtime2/model/ArgumentExpressions.kt):336 builds sets recursively through `flatMapTo`; its ground case currently scans values too. Of 165 variable-enumeration samples, 116 are called by `isContextuallyGrounded`. Ground tuples cannot contain variables by their type invariant, making a ground fast path a concrete hypothesis. [GroundObjectKey.kt](../../../../src/main/kotlin/viaduct/engine/runtime2/resolution/framework/GroundObjectKey.kt):15 checks grounding again when called after an explicit grounding check. Probe argument kinds and repeat counts before changing this shared engine helper. Variable enumeration overlaps stored-key lookup in 47 samples.
- [ResolverInputReplay.kt](../../../../src/support/kotlin/viaduct/engine/runtime2/correctresolution/ResolverInputReplay.kt):48 checks every invocation, including the first, against `first.input` and `first.queryValue`. There is no object/list identity shortcut. Of 130 equality samples, 54 come from these invocation-consistency comparisons and 76 from actual-versus-expected conformance. Preserve independently reconstructed expected values, exact field-denial alternatives, response keys and list positions; observed inputs cannot stand in for independent expectations. An identity shortcut or skipping the first consistency comparison would address only the reflexive part, not all equality work.
- [RegistryGenerator.kt](../../../../src/support/kotlin/viaduct/engine/runtime2/arbitrary/RegistryGenerator.kt):4224 recursively builds the small hash chain through the general `objectOf` DSL. The helper has only one self sample, but its callees include name/coordinate lookup, schema output lowering and map construction across 245 samples. This is fixture output construction during deterministic correctness replay, not the correctness predicate itself. Any preparation must preserve input-dependent hashes, fresh values where required and the frozen corpus; precomputing constant callback outputs would change the measured workload.
- [ConformsToSelections.kt](../../../../src/support/kotlin/viaduct/engine/runtime2/correctresolution/ConformsToSelections.kt):47 is entered from closure validation (119 samples), resolver conformance (65), identity reconstruction (125) and the direct judgment (4). Its path is copied through recursion but is not used to decide conformance. The 313-sample inclusive cost also includes 71 stored-key lookup samples and 59 activation-access samples; removing path copies alone cannot eliminate that whole cost. A cache would require evidence of repeated identical obligations and fresh state per judgment.

No speedup is claimed for this round. The benchmark/profile completed with all correctness oracles enabled. Executable source hashes are unchanged; only profiling evidence/history were added. Artifact JSON parsing, sample/count consistency, local documentation links and `git diff --check` were verified. No test suite was rerun for these evidence-only changes.
