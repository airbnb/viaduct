# Reusing validated reference ownership

The property-test benchmark's identity oracle replayed resolver conformance to establish reference ownership, then `correctResolution` replayed the same resolver relations. The candidate performs full correctness validation first and passes its validated reference observations to identity reconstruction. Ordinary application identities and input fingerprints are still reconstructed independently. The standalone identity oracle retains its independent ownership replay. Missing or duplicate reference observations still fail correctness validation; an empty observed reference list cannot justify skipping replay.

On `raymie-stata-codex` (Intel Xeon Platinum 8375C at 2.90 GHz, 32 cores/64 logical CPUs), the serial controlled comparison improves **0.801599258 ± 0.018607549 → 0.598834811 ± 0.016978228 s/case**, a **25.29% time reduction**. Both variants use Corretto 17.0.11+9-LTS, JMH 1.36, compiler blackholes, one JMH thread, one Resolution thread, five warmups and eight measurements in each of three forks. One operation is one frozen property case with 12,777 resolver applications. The extra warmups, measurements and forks replace the short task defaults to reduce uncertainty. Errors are JMH's 99.9% confidence half-widths. Every measurement is retained in [before.json](before.json) and [after.json](after.json), with console output in the corresponding text files.

Both measured trees are dirty: source revision `f44da2cfb48962f36fb5d29cd9cf044a4f9804fd` plus [baseline.patch](baseline.patch) or [candidate.patch](candidate.patch). The recorded revision alone cannot reproduce either run. The baseline patch corrects the stale stored application count from 12,763 to 12,777, as requested; the executable registry recipe, schema and query remain unchanged. The candidate patch includes all six changed tracked files at measurement. This evidence directory and the history entry were added afterward. [source-state.json](source-state.json) records source, corpus, JAR and recording SHA-256 identities, host, parameters and session `01a16f8d-0ac7-7fb3-805d-8fe2daf78f79`. Measurements and profiles were taken on 2026-10-24 between 20:20 and 20:34 UTC; exact case timestamps are in the phase exports.

Commands run from `/home/raymie_stata/repos/1ht/projects/viaduct/oss`:

```sh
./gradlew :core:engine:runtime2:propertyTestBenchmark --console=plain --no-configuration-cache
./gradlew :core:engine:runtime2:propertyTestProfile --console=plain --no-configuration-cache -PpropertyTestBenchmarkLoopCount=30 -PpropertyTestProfileOutput=/tmp/runtime2-validation-20261024/property-standard.jfr
./gradlew :core:engine:runtime2:jmhJar --console=plain --no-configuration-cache
/opt/mise/installs/java/corretto-17.0.11.9.1/bin/java -Dfile.encoding=UTF-8 -Duser.country -Duser.language=en -Duser.variant -jar /tmp/runtime2-validation-20261024/duplicate-replay-baseline.jar 'viaduct.engine.runtime2.resolution.PropertyTestBenchmark.propertyTest' -p loopCount=1 -wi 5 -i 8 -f 3 -foe true -rf json -rff /tmp/runtime2-validation-20261024/duplicate-replay-control-before.json
/opt/mise/installs/java/corretto-17.0.11.9.1/bin/java -Dfile.encoding=UTF-8 -Duser.country -Duser.language=en -Duser.variant -jar /tmp/runtime2-validation-20261024/duplicate-replay-candidate.jar 'viaduct.engine.runtime2.resolution.PropertyTestBenchmark.propertyTest' -p loopCount=1 -wi 5 -i 8 -f 3 -foe true -rf json -rff /tmp/runtime2-validation-20261024/duplicate-replay-control-after.json
./gradlew :core:engine:runtime2:propertyTestProfile --console=plain --no-configuration-cache -PpropertyTestBenchmarkLoopCount=30 -PpropertyTestProfileOutput=/tmp/runtime2-validation-20261024/duplicate-replay-candidate.jfr
core/engine/runtime2/export-resolver-profile.sh /tmp/runtime2-validation-20261024/property-standard.jfr /tmp/runtime2-validation-20261024/profile-export
core/engine/runtime2/export-resolver-profile.sh /tmp/runtime2-validation-20261024/duplicate-replay-candidate.jfr /tmp/runtime2-validation-20261024/duplicate-replay-profile-export
```

The preserved baseline JAR matches the SHA-256 recorded before implementation. The controlled before/after runs ran serially; builds, tests and profiles ran outside that pair. The initial default baseline, retained in [default-baseline.txt](default-baseline.txt), was 0.813 ± 0.157 s/case, with measured iterations 0.868, 0.837, 0.809, 0.762 and 0.792 s/case. Its wide error makes the longer controlled pair the basis of the improvement claim.

The diagnostic profiles each record 30 cases after one unrecorded 30-case warmup. Their single measured iterations are 26.338 and 20.034 s/op respectively, or 0.877933 and 0.667800 s/case. They include JFR overhead and supply no JMH error estimate. Mean phase durations are:

| Phase | Before, ms/case | After, ms/case |
| --- | ---: | ---: |
| Request preparation | 7.161 | 7.546 |
| Resolution | 278.907 | 276.095 |
| Witness snapshot | 0.014 | 0.014 |
| Correctness judgment | 245.762 | 270.150 |
| Application identity oracle | 318.512 | 84.882 |
| From-field binding oracle | 27.287 | 28.797 |

Combined validation falls from 591.561 to 383.829 ms/case. The candidate profile has no samples through `ownedRootFieldReferenceInvocations`; the engine phase remains close to its baseline. Correctness now precedes identity reconstruction, which can affect JIT/GC attribution between those phases. The timing claim comes from the controlled unprofiled JMH runs.

The [before-profile](before-profile) and [after-profile](after-profile) directories retain the maintained exporter output, including every phase duration and recording checksum. Raw JFR files and benchmark JARs remain in `/tmp/runtime2-validation-20261024` on the recorded host. [before-analysis.json](before-analysis.json) and [after-analysis.json](after-analysis.json) contain phase-attributed CPU and allocation summaries. [analyze.py](analyze.py) consumes `jfr print --json --events qplan.PropertyTestPhase,jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ObjectAllocationSample,jdk.GCPhasePause --stack-depth 128 RECORDING.jfr` output and writes those summaries. Inclusive method shares overlap. Allocation estimates exclude the first allocation sample per thread because its weight includes pre-recording allocations; unadjusted exporter allocation views retain that weight. Adjusted sampled validation allocation is approximately 531.7 → 330.2 MB/case, not an exact allocated-byte measurement.

[regression-red.txt](regression-red.txt) records the expected failure with duplicate replay: a combined judgment reapplied the relation twice instead of once. [focused-tests.txt](focused-tests.txt) records 78 passing tests, including fresh-judgment independence, standalone replay, mismatched result/operation rejection, corrupted values, real reference identities and missing/duplicate reference rejection. `./gradlew :core:engine:runtime2:check` passed in 4m 59s: 3,684 reported tests, 168 skipped and zero failures or errors. [full-check.txt](full-check.txt) and [full-check-results.json](full-check-results.json) retain its output and suite counts. The documentation-label check also passed after adding this evidence.
