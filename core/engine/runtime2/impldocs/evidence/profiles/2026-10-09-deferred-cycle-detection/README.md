# Deferred runtime cycle diagnosis

Collecting dependencies without per-edge reachability checks reduces the prepared-callback overhead benchmark from **14.623 to 14.243 ms/resolution**, **2.60% less time**. Five-thread overhead improves 4.03%; the frozen property's 0.81% mean improvement has overlapping intervals and does not establish a benefit. This experiment compares the complete uncommitted change against HEAD `1b0418ee3f26d`; the earlier consolidated cycle optimization and its evidence remain intact.

## Implementation and invariant

`CycleCheckState.createDeferred()` registers writers and records the historical reader-to-writer graph without searching or rejecting cycles during reads or registration. Concurrent maps/sets, the shared monitor, pending readers only for unknown writers, task identity and cached task hashes are retained. An iterative depth-first scan returns one closed cycle in expected O(V+E) time at the request's own deadline. Successful requests perform no cycle scan. The scan removes the previous repeated-reachability contribution to quadratic chain growth; graph recording, hashing, synchronization and retained graph storage remain.

Bounded Resolution uses this collector with its existing 15-second bound. Production `Engine2` and its query/mutation strategies use a tunable 60-second default, exposed as `EngineConfiguration.runtime2RequestTimeoutMillis`. The deadline begins in the execution strategy after validated-operation conversion; GraphQL parsing/validation and synchronous selection conversion precede it. The request timer remains active through nested execution and incremental publisher completion, and normal completion/caller cancellation cancel it. Cancellation is cooperative and does not forcibly interrupt noncooperative tenant code; reporting the production deadline does not wait for canceled resolver children. The strategy scans before canceling promise-backed work, reports a detected cycle through `ResolverReadCycleException` with the timeout as its cause, and otherwise preserves the timeout. Bounded Resolution reclassifies only its own deadline, preserving resolver-local and caller timeouts. Direct unbounded startup and maintained coroutine-family controls retain the eager checker.

Validated dependencies must be acyclic. A historical cycle therefore diagnoses a framework invariant violation even if it was not the proximate cause of this timeout. The primitive-level denial regression deliberately constructs an unvalidated cyclic graph: checked materialization records a raw-value dependency, denial releases that dependency, and both values complete while the historical cycle remains. This is not a permitted validated-registry scenario. It explains why diagnosing the invariant does not require proving that the reported cycle is still a live wait cycle, or filtering completed dependencies from the graph. The requested policy scans only at the deadline and does not detect an invalid graph in a request that completes successfully.

## Controlled measurements

JMH 1.36, Corretto 21.0.4+7-LTS, compiler Blackholes, one JMH thread, `loopCount=1`, three fresh forks, five warmups and eight single-shot measured iterations per fork (24 samples). Overhead operations contain 100 resolutions; the property operation contains one frozen case with 12,763 expected resolver applications. Errors are JMH's reported 99.9% within-run confidence errors.

| Workload | Baseline ± error | Candidate ± error | Unit | Observed time saving |
| --- | ---: | ---: | --- | --- |
| One dispatcher thread | 14.623 ± 0.099 | 14.243 ± 0.126 | ms/resolution | 2.60% |
| Five dispatcher threads | 6.686 ± 0.104 | 6.417 ± 0.046 | ms/resolution | 4.03% |
| Frozen property case | 954.747 ± 14.809 | 947.049 ± 15.987 | ms/case | 0.81%; intervals overlap |

All six timing runs execute serially, with no compilation, tests, diagnostics or profiles overlapping them. Pair order is baseline/candidate for one-thread overhead and property, candidate/baseline for five-thread overhead. [Exact commands/timestamps](commands.json), all raw JMH JSON/logs, [iterations](measured-iterations.tsv) and [summary](benchmark-summary.json) are retained. Shared-host background activity and run-to-run variation limit generalization beyond these controlled observations; within-run errors do not capture all host variation. The original five-pair checker fixture and prepared callbacks are unchanged; the separate fixture-reshaping work is not included. All four workload-statistics reports are byte-identical, SHA-256 `1b3e51af0c7400c7a2d905ac03acc7b5428901f3f7484bf7400e9f14fc636f72`.

## Exact operation counts

A temporary ASM agent observes one warmup and one measured 100-query overhead invocation per variant. Both invocations give identical counts. The diagnostic durations are not timing evidence. [Counter data](cycle-counts.json), probe sources, commands and full logs are retained.

| Metric over 100 resolutions | Baseline | Candidate |
| --- | ---: | ---: |
| Read calls | 246,624 | 246,624 |
| Known-writer reads | 109,251 | 109,251 |
| Writer slots / tasks | 45,704 | 45,704 |
| Distinct dependency edges | 64,838 | 64,838 |
| Pending reader-index pairs | 102,340 | 102,340 |
| BFS searches | 64,838 | 0 |
| BFS node visits | 131,788 | 0 |
| BFS edge scans | 87,642 | 0 |
| Deadline diagnostic scans | 0 | 0 |

Reads, writers, distinct edges and pending index entries are unchanged. All 64,838 eager searches, 131,788 node visits and 87,642 edge scans disappear, and successful requests execute no deadline diagnostic scans. The result is a modest current-corpus saving because collection still incurs hashing, allocations and synchronization; no allocation-byte or contention saving is inferred from these counters.

## Remaining CPU costs

The candidate profile records only the measured overhead body after three unrecorded warmups: one fresh fork, one measured invocation, `loopCount=10` (1,000 resolutions). Its instrumented duration is not a speedup comparison. [Profile command](profile-command.json), [exports](candidate-overhead-profile/summary.txt), raw recording checksum, analysis scripts and [component counts](candidate-overhead-profile/components.json) preserve 954 execution samples. Inclusive memberships overlap and cannot be added or interpreted as disjoint milliseconds.

| Component | Inclusive samples | Share |
| --- | ---: | ---: |
| Construction-demand closure | 235 | 24.63% |
| Resolver-input materialization | 194 | 20.34% |
| Successor-demand analysis | 189 | 19.81% |
| Selection normalization | 168 | 17.61% |
| Cycle bookkeeping | 47 | 4.93% |
| Resolver relation evaluation | 32 | 3.35% |

There are zero fixture-output construction samples and zero deferred diagnostic-scan samples. Weighted allocation exports retain the first-sample startup distortion; the derived first-sample-excluded sites are diagnostic, not measured allocation totals. Remaining larger candidates are reducing repeated input projections, sharing or precompiling registry-driven selection work, and reducing selection normalization allocations; they require separate experiments rather than attribution to this cycle change.

## Validation

Focused cycle/startup tests pass before measurement (46 tests); the dedicated virtual-time timeout tests pass (11 tests). New collector tests cover DAGs, self/multi-hop cycles, late registration, duplicate reads, completed dependencies, kinds/roots, writer uniqueness, 128 concurrent registration rounds and an exact 20,000-node iterative chain/cycle. Virtual-time tests cover actual injected waits, configured/default deadlines, query/mutation cancellation, successful timer disposal, caller/local timeouts, and incremental lifetime. No fixture generator or coverage guard is weakened.

| Validation stage | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| timeouts | 11 | 0 | 0 | 0 |
| resolution-threads5 | 620 | 0 | 0 | 4 |
| full-check | 2511 | 0 | 0 | 166 |
| wiring | 44 | 0 | 0 | 0 |

The exact Runtime2 full check passed. [Validation commands](validation-commands.json), console logs, per-class JSON, new-test XML and any failure XML retain the outcomes. Full check is exactly `./gradlew :core:engine:runtime2:check`; the five-thread Resolution suite uses seed `424242`. Wiring checks validate the configuration/factory edits.

## Provenance and reproduction

Recorded 2026-10-09 02:19:52 UTC in `/home/raymie_stata/repos/3ht/projects/viaduct/oss`, branch `rstata--2perf`, session `01a11ce9-fb33-7b50-aa1f-35ab16fbef2e`. [Environment](environment.json) records host `raymie-stata-codex`, KVM Intel Xeon 6975P-C, 32 cores / 64 logical CPUs, approximately 495 GiB RAM, no swap and Corretto 21.0.4. Default heap settings are retained.

The baseline JAR is the preserved complete consolidated implementation; its recorded main/support/JMH tree identities match HEAD. The candidate is deliberately uncommitted for review: the commit alone is insufficient to reproduce it. [Candidate source metadata](candidate-source.json) records every changed/new source file hash, corpus hashes, and JAR SHA-256/normalized ZIP-content SHA-256. The compressed [source patch](candidate-source.patch.gz) includes tracked and new source files relative to the OSS root; it excludes documentation and evidence, which do not affect benchmark compilation. Final source hashes match the measured snapshot. [JAR comparison](jar-content-diff.json) retains every changed entry; changes arise from the collector, bounded Resolution, Engine2/strategy wiring and the recompilation of their call sites. Support/JMH source and corpus files are unchanged. JARs, raw JFR and unmodified output originals remain outside Git under `/tmp/runtime2-edge-collection-20261009`; archived text removes only trailing whitespace and redundant EOF blank lines. No commit or push is performed.

To reproduce, build/preserve `baseline.jar` from HEAD, apply the candidate source patch in a separate checkout, build/preserve `candidate.jar`, and place the scripts/probe/manifest with both JARs in a temporary directory. Use the six exact Java commands in `commands.json`, adjusting the JAR/output paths for the new checkout. The archived `measure.py` preserves the original orchestration, including host-specific checks against the earlier preserved baseline JAR and metadata in `/tmp/runtime2-cycle-consolidated-20261009`; those checks must be adapted for fresh rebuilds. Normalized ZIP-content hashes permit comparison when ZIP timestamps differ. `run-counters.py` accepts the OSS root; its ASM dependency path is host-specific. `run-profile.py` and the analysis scripts reproduce the candidate-only profile. Build with `./gradlew :core:engine:runtime2:jmhJar --no-configuration-cache --console=plain`, and keep all timing runs serial and separate from builds/tests/diagnostics.
