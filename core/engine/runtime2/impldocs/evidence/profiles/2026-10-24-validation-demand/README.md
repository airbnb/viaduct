# Caching completed-output demand during validation

A fresh 60-case profile after eliminating duplicate replay identified completed-output-demand reconstruction as 26.02% of correctness-validation CPU samples. A temporary one-case probe counted 96,029 composite subtree visits over 13,094 distinct object/list results; most results were visited seven to nine times. The retained change memoizes these reconstructed selection forests by result identity in the existing `ResolverApplicationCache`. Ordinary resolver replay and root-reference replay share this cache within a judgment. Every fresh judgment creates fresh state. The cache still derives demand from completed result cells, excludes parent backedges and combines demand from every list member.

The controlled comparison is **0.600746462 ± 0.016700623 → 0.569965530 ± 0.013514065 s/case**, a **5.12% time reduction** beyond duplicate-replay elimination. One JMH operation is one frozen property case with 12,777 resolver applications and all validators enabled. Each variant uses three forks, five warmups and eight measurements per fork, one JMH thread, one Resolution thread, Corretto 17.0.11+9-LTS, JMH 1.36 and compiler blackholes. These deliberate iteration/fork overrides improve precision over the short default task. JMH errors are 99.9% confidence half-widths. All 24 measurements per variant are retained in [before.json](before.json) and [after.json](after.json), with console output in the corresponding text files.

The host is `raymie-stata-codex`, Intel Xeon Platinum 8375C at 2.90 GHz, 32 cores/64 logical CPUs. The fresh baseline profile ended at 2026-10-24 20:53:02 UTC, the probe at 20:54:39, the serial controls at 21:00:41 and 21:01:12, and the candidate profile at 21:02:57. Builds, tests, probes and profiles ran outside the controlled pair. The source revision is `f44da2cfb48962f36fb5d29cd9cf044a4f9804fd` plus [before.patch](before.patch) or [after.patch](after.patch); both measured trees are uncommitted, so that revision alone cannot reproduce them. The baseline already includes duplicate-replay elimination and the corrected stored application count. Executable corpus resources match across the pair. [source-state.json](source-state.json) records source, corpus, JAR and recording hashes, parameters, probe counts and session `01a16f8d-0ac7-7fb3-805d-8fe2daf78f79`. Documentation/evidence added after measurement does not affect executable inputs.

Commands run from `/home/raymie_stata/repos/1ht/projects/viaduct/oss`:

```sh
./gradlew :core:engine:runtime2:propertyTestProfile --console=plain --no-configuration-cache -PpropertyTestBenchmarkLoopCount=60 -PpropertyTestProfileOutput=/tmp/runtime2-validation-20261024/post-replay.jfr
./gradlew :core:engine:runtime2:jmhJar --console=plain --no-configuration-cache
/opt/mise/installs/java/corretto-17.0.11.9.1/bin/java -Dfile.encoding=UTF-8 -Duser.country -Duser.language=en -Duser.variant -jar /tmp/runtime2-validation-20261024/next-baseline.jar 'viaduct.engine.runtime2.resolution.PropertyTestBenchmark.propertyTest' -p loopCount=1 -wi 5 -i 8 -f 3 -foe true -rf json -rff /tmp/runtime2-validation-20261024/demand-cache-before.json
/opt/mise/installs/java/corretto-17.0.11.9.1/bin/java -Dfile.encoding=UTF-8 -Duser.country -Duser.language=en -Duser.variant -jar /tmp/runtime2-validation-20261024/demand-cache-candidate.jar 'viaduct.engine.runtime2.resolution.PropertyTestBenchmark.propertyTest' -p loopCount=1 -wi 5 -i 8 -f 3 -foe true -rf json -rff /tmp/runtime2-validation-20261024/demand-cache-after.json
./gradlew :core:engine:runtime2:propertyTestProfile --console=plain --no-configuration-cache -PpropertyTestBenchmarkLoopCount=60 -PpropertyTestProfileOutput=/tmp/runtime2-validation-20261024/demand-cache.jfr
core/engine/runtime2/export-resolver-profile.sh /tmp/runtime2-validation-20261024/post-replay.jfr /tmp/runtime2-validation-20261024/post-replay-profile-export
core/engine/runtime2/export-resolver-profile.sh /tmp/runtime2-validation-20261024/demand-cache.jfr /tmp/runtime2-validation-20261024/demand-cache-profile-export
./gradlew :core:engine:runtime2:test --tests 'viaduct.engine.runtime2.correctresolution.*' --tests 'viaduct.engine.runtime2.contract.CheckerQueryResolverWitnessTest' --tests 'viaduct.engine.runtime2.resolution.ResolverOccurrenceWitnessTest' --tests 'viaduct.engine.runtime2.resolution.RootFieldReferenceResolutionTest' --tests 'viaduct.engine.runtime2.resolution.FullBenchmarkReplayTest' --no-configuration-cache --console=plain
./gradlew :core:engine:runtime2:check
```

The one-case probe used source revision plus [probe.patch](probe.patch), a temporary identity-counting map and an explicit report at the end of the property case. After building that JAR, it ran the same JMH command with `-p loopCount=1 -wi 0 -i 1 -f 1 -foe true`, without JSON output. [probe.txt](probe.txt) preserves its histogram. Its cold instrumented timing is not a performance control. All instrumentation was removed before the retained candidate was built.

Both diagnostic profiles contain one recorded iteration of 60 cases after one unrecorded 60-case warmup. Before and after measured iterations are 38.856 and 36.831 s/op, or 0.647600 and 0.613850 s/case. Profile timings include recording overhead and have no JMH error estimate. Mean phase durations are:

| Phase | Before, ms/case | After, ms/case |
| --- | ---: | ---: |
| Request preparation | 5.350 | 5.051 |
| Resolution | 261.403 | 263.290 |
| Witness snapshot | 0.013 | 0.014 |
| Correctness judgment | 268.009 | 231.402 |
| Application identity oracle | 83.645 | 84.272 |
| From-field binding oracle | 28.961 | 29.611 |

Combined validation falls from 380.615 to 345.285 ms/case. Output-demand reconstruction falls from 346/1,330 to 19/1,020 correctness CPU samples, or 26.02% → 1.86%; [demand-cpu.json](demand-cpu.json) retains these counts, including cache-wrapper frames after the change. Inclusive CPU shares overlap. Adjusted sampled correctness allocation is approximately 235.3 → 206.4 MB/case; these are weighted estimates, not exact counters. Exclude the first allocation sample per thread because its weight includes pre-recording allocations. The maintained exporter's allocation views retain those unadjusted weights.

[before-profile](before-profile) and [after-profile](after-profile) retain phase events, hot methods, allocation sites, stacks, GC summaries and recording checksums. [before-analysis.json](before-analysis.json) and [after-analysis.json](after-analysis.json) retain phase-attributed CPU/allocation summaries. [analyze.py](analyze.py) consumes `jfr print --json --events qplan.PropertyTestPhase,jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ObjectAllocationSample,jdk.GCPhasePause --stack-depth 64 RECORDING.jfr` output and excludes first allocation samples. Raw JFR files, event JSON and JARs remain in `/tmp/runtime2-validation-20261024` on the recorded host.

All 80 focused tests passed. New cases cover combined demand from different list elements, exclusion of parent backedges, reuse of reconstructed subtrees and fresh reconstruction after a pending field completes between judgments. Existing tests cover resolver/checker Query roots, references and corrupt observations. [focused-tests.txt](focused-tests.txt) and [focused-results.json](focused-results.json) retain the results. `./gradlew :core:engine:runtime2:check` passed in five minutes with 3,686 reported tests, 168 skipped and zero failures or errors; [full-check.txt](full-check.txt) and [full-check-results.json](full-check-results.json) retain its output and suite counts.
