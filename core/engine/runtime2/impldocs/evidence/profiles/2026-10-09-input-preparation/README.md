# Runtime2 input preparation and local scans — 2026-10-09 UTC

This round implements approved steps 1–6 as one combined change. Step 7 (demand normalization and parent lifting) and prepared-output projection remain deferred. The candidate is based on `d00c4ffa7e3248e0951cebba24928e344a131291` (`rstata--3perf`) in `/home/raymie_stata/repos/2ht/projects/viaduct/oss`, branch `rstata--tmp`. [Base move](base-move.txt) records the stash/apply and identical Runtime2 source trees on the original and requested bases. The final source delta is retained in [source-changes.patch](source-changes.patch); the candidate's enclosing commit is the tested implementation. The standalone patch uses zero context and can be applied with `git apply --unidiff-zero`; JSON trailing blank lines are normalized without changing measured values.

[Provenance](provenance.json) records UTC time, host/hardware, JVM, session, corpus and JAR hashes, benchmark parameters and source identity. All builds, tests, operation diagnostics, benchmarks and recordings ran serially. Prepared callbacks and the China-active checker profile are unchanged; neither corpus inputs nor guards were weakened. JMH is 1.36 on Corretto 21.0.4+7-LTS, one benchmark thread and one Resolution dispatcher thread.

## Retained changes

1. Prepare named checker templates and provider variable-name sets once per registry entry, reusing them for construction and per-application materialization.
2. Reuse variable-free forests and subtrees, and instantiate ordinary Query materialization selections once for the invocation and observer.
3. Accumulate included selections linearly, reuse singleton response groups, bypass redundant grounding for ground keys, and build EOD entries directly.
4. Reuse immutable typed empty inputs in prepared registries, including results that become empty after evaluating guards and concrete type. External/decorated registries fall back to the ordinary factory.
5. Index passive demand by canonical field once per object and replace repeated select registration with one completion queue signal per distinct requirement leaf. Ordinary error precedence, ready alternatives, request cancellation and fatal child failures are preserved.
6. Retain immutable concrete-type layouts on unconditional forests, with occurrence-scoped layouts for instantiated forests. A layout never caches materialized values or fetched bindings; named inputs, aliases, concrete-type alternatives and checker boundaries stay distinct. Guarding by Always preserves the original forest. Conditional forests are filtered per input and do not retain layouts.

## Exploratory incremental runs

These curiosity runs use three warmups, five single-shot measurements, one fork, and 100 queries/op. The first six snapshots precede the base move; the refined layout run is on the requested base. Their errors overlap, and these sequential short runs do not establish an isolated speedup for every step. In particular, empty-input caching and reusable layouts have no demonstrated individual wall-time benefit here. The layout eligibility was refined from scanning variables at collection time to reusing the already-needed unconditional metadata; both trials are retained.

| Snapshot | Mean ms/query | JMH error ms/query |
| --- | ---: | ---: |
| Baseline | 11.052 | 0.366 |
| 1: prelower checker templates | 10.494 | 0.523 |
| 2: static forest / Query reuse | 10.392 | 0.587 |
| 3: projection accumulation / entries | 9.980 | 0.229 |
| 4: typed empty inputs | 9.960 | 0.293 |
| 5: local scan repairs | 9.853 | 0.164 |
| 6: first layout trial | 9.950 | 0.266 |
| 6: refined layout eligibility | 10.014 | 0.269 |

Raw `baseline.json`, `step*.json` preserve every iteration, confidence interval and parameter; corresponding statistics files preserve the entire workload report. Temporary JARs remain outside Git under `/tmp/runtime2-input-preparation-20261009`, with hashes recorded in provenance.

## Exact local-work evidence

[Operation counts](operation-counts/operation-counts.json) assert all 24 baseline/candidate results and verify projected and passive values. Materialization includes an excluded occurrence so the conditional accumulation path is exercised. [Probe sources and runner](run-operation-counts.py) instrument existing bytecode without production counters. ASM 9.8 must precede the JMH fat JAR on the runtime classpath because that JAR bundles an older ASM.

| Width | Prefix elements copied, before → after | Passive key reads, before → after | Wait registrations → completion sends |
| ---: | ---: | ---: | ---: |
| 16 | 120 → 0 | 288 → 48 | 136 → 16 |
| 32 | 496 → 0 | 1,088 → 96 | 528 → 32 |
| 64 | 2,016 → 0 | 4,224 → 192 | 2,080 → 64 |
| 128 | 8,128 → 0 | 16,640 → 384 | 8,256 → 128 |

The old work is respectively n(n−1)/2, n²+2n, and n(n+1)/2. The candidate performs zero prefix copies, 3n key reads, zero select registrations and n completion signals. This establishes the repaired local loops; it does not establish a linear bound on full demand closure or arbitrary condition DAG propagation.

```sh
python3 run-operation-counts.py BASELINE.jar CANDIDATE.jar --output COUNTS
```

## Controlled final measurements

Final controls use five warmups, eight measurements and three fresh forks (24 measured samples per variant), one fixed 100-query batch per operation. This intentionally increases precision beyond the default single-fork controls. Raw JMH JSON and full emitted statistics are preserved for both variants.

```sh
java -jar JAR 'viaduct\.engine\.runtime2\.resolution\.ResolverBenchmark\.overhead' -p loopCount=1 -wi 5 -i 8 -f 3 -foe true -rf json -rff RESULT.json -jvmArgsAppend '-DresolverBenchmarkReportFile=STATISTICS.txt'
```

| Variant | Mean s/op ± JMH error | Mean ms/query ± JMH error |
| --- | ---: | ---: |
| Requested baseline | 1.087279 ± 0.008248 | 10.873 ± 0.082 |
| Final candidate | 0.987149 ± 0.004077 | 9.871 ± 0.041 |

The final combined change saves **1.001 ms/query (9.21% elapsed time)**, or 10.14% greater throughput at this configuration. Every emitted corpus statistic is byte-identical. [Comparison JSON](final-comparison.json) preserves the arithmetic; final-baseline.json and final-candidate.json preserve all 24 measured iterations each. The earlier refined-layout control, prior to the fatal-sibling preservation correction, is retained separately as step6-refined-control.json and is not the final candidate measurement.

## Profiles and validation

The initial 5,000-query recording is preserved as [baseline-profile](baseline-profile). Its source is identical to the requested base, as proven by the source-tree identities in base-move.txt. The initial raw recording remains `/tmp/runtime2-local-scans-20261009/detail.jfr`; its exact invocation and corpus identities are retained in [initial provenance](initial-provenance.json). Final recording exports and comparative analysis are added below. Profiles diagnose attribution, while JMH controls establish elapsed-time savings. Inclusive shares overlap; weighted allocation samples are approximate and exclude the first allocation sample on each thread to reduce recording-start bias.

The final [measured-body recording export](final-profile) covers 5,000 queries in 51.322 seconds, with 2,848 CPU samples; the initial recording covers the same queries in 56.318 seconds with 3,067 samples. These single profile durations include recording overhead and are not the reported speedup. The common [analyzer](analyze-materialization.py) was run on both raw recordings; [profile comparison](profile-comparison.json) preserves the resulting percentages.

| Inclusive CPU component | Baseline % | Candidate % |
| --- | ---: | ---: |
| Input projection | 8.44 | 7.13 |
| Materialization instantiation | 7.83 | 1.09 |
| Union of projection and instantiation | 16.27 | 8.22 |
| Checker template lowering | 4.21 | 0.00 |
| Construction closure | 26.87 | 30.16 |
| Successor demand | 28.53 | 29.60 |
| Selection normalization | 26.54 | 28.51 |
| Prepared-output projection | 5.77 | 6.39 |

The union of input projection and instantiation falls from 16.27% to 8.22% of CPU samples, and from 19.15% to 6.51% of startup-filtered weighted allocation samples. Repeated checker lowering disappears from the measured body. Construction closure, successor demand and normalization remain dominant overlapping components; their larger shares do not establish an absolute regression. Empty and layout cache benefits remain unisolated in the wall-time curiosity runs. Weighted allocation shares are sampled attribution, not exact allocated-byte measurements.

The complete Runtime2 check passes: 266 suites, 2,525 tests, zero failures/errors, 166 skips. [Validation](validation.json) records the exact command and focused five-thread materialization/activation tests. [Full-check output](full-check.txt), [focused output](final-focused-build.txt), and [fatal regression output](fatal-sibling-before.txt) retain validation results. The [initial full-check output](full-check-static-first.txt) records the static-analysis rejection of catching Throwable and its narrowly explained suppression. A directed regression fails before the completion-queue child rethrows a fatal error and passes after; a ready true alternative still masks ordinary sibling Exceptions but cannot hide a completed fatal sibling. No coverage guard was weakened, and no known-coverage failure occurred in this final check. No full checks ran between the six exploratory steps.

The raw final JFR remains `/tmp/runtime2-input-preparation-20261009/final-profile.jfr`. Exact commands are in provenance.json; exported profile checksums allow the raw recordings to be verified. All changes and evidence form one commit; no push is performed.
