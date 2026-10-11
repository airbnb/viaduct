# Runtime2 cycle-check optimization

The whole change reduces the prepared-callback overhead benchmark from **15.000 to 14.618 ms/resolution**, **2.55% less time**. This collection compares baseline `b21ddcf895392` with the complete retained implementation; it contains no individual-stage timing claims. All production edits are local to `CycleCheckState.kt`. The prepared callbacks and original fixed corpus are unchanged.

## Retained implementation

A shared instance monitor covers writer registration, pending-reader indexing, dependency insertion and traversal. Known-writer reads add their dependency directly; unknown-writer reads enter the pending index and are connected during later writer registration. None of these operations suspends, waits for a promise or invokes resolver callbacks. Maps and adjacency/pending sets remain concurrent collections.

A duplicate edge skips BFS only while the graph is acyclic. Rejected edges remain recorded, preserving historical behavior; after the first cycle rejection, every edge attempt resumes checking, including previously accepted duplicates. Completed-slot dependencies, task/slot identities and cycle error publication are preserved. Task hashes are cached from the kind, root reference identity and copied structural path. The BFS algorithm itself is unchanged.

The original registration implementation was not demonstrated to lose an edge: both sides publish their own state before looking up the other side. Synchronization establishes one explicit invariant for the retained shortcuts; it is not claimed as a reproduced baseline race repair.

## Controlled before/after measurements

Every run uses JMH 1.36, compiler Blackholes, three fresh forks, five warmups and eight single-shot measured iterations per fork: 24 measured samples, one JMH thread and `loopCount=1`. Overhead operations contain 100 resolutions; property operations contain one frozen case with 12,763 expected resolver applications; correctness operations contain 50 judgments with input count 50 and seed 1. Errors are JMH's reported 99.9% within-run confidence errors.

| Benchmark | Baseline ± error | Final ± error | Unit | Observed saving |
| --- | ---: | ---: | --- | --- |
| Resolution overhead, one dispatcher thread | 15.000 ± 0.080 | 14.618 ± 0.057 | ms/resolution | 2.55% less time |
| Resolution overhead, five dispatcher threads | 6.609 ± 0.062 | 6.594 ± 0.087 | ms/resolution | 0.22% less time; intervals overlap |
| Frozen property case | 977.283 ± 15.677 | 952.218 ± 14.789 | ms/case | 2.56% less time; intervals overlap |
| Correctness replay | 3.959 ± 0.042 | 3.968 ± 0.041 | ms/judgment | 0.23% more time; intervals overlap |

[Raw iterations](measured-iterations.tsv), [summary](benchmark-summary.json), all eight raw JMH JSON/log pairs and [exact commands and timestamps](measurement-commands.json) retain the measurements. Runs execute serially, with no build, test or diagnostic overlap. One-thread and property pairs run baseline then final; five-thread and correctness pairs run final then baseline. The shared host permits unrelated background activity; within-run errors do not capture all run-to-run variation. Overlapping intervals do not establish a benefit or regression. These results apply to the unchanged five-pair checker fixture, not the fixture being reshaped in another worktree.

## Exact graph-operation counts

A temporary ASM agent counts operations only within each overhead benchmark invocation. One warmup and one measured invocation give identical counts across 100 resolutions for each variant. Instrumented durations are not timing evidence. [Counter data](cycle-counts.json), full logs, Java probe sources, manifest and diagnostic commands are retained.

| Metric over 100 resolutions | Baseline | Final |
| --- | ---: | ---: |
| Read calls | 246,624 | 246,624 |
| Reads with a known writer | 109,251 | 109,251 |
| Writer slots | 45,704 | 45,704 |
| Distinct tasks | 45,704 | 45,704 |
| Distinct dependency edges | 64,838 | 64,838 |
| BFS searches | 109,251 | 64,838 |
| BFS node visits | 211,157 | 131,788 |
| BFS edge scans | 131,143 | 87,642 |
| Maximum nodes visited in one search | 45 | 45 |
| Reader-index task/slot pairs | 167,178 | 102,340 |
| Cycle failures | 0 | 0 |

BFS searches fall 40.65%, node visits 37.59%, edge scans 33.17%, and reader-index pairs 38.78%. Read calls, tasks and distinct dependency edges are unchanged. All four emitted overhead statistics reports are byte-identical.

Distinct-edge acyclic chains of 16/32/64/128 edges still visit 136/528/2,080/8,256 nodes and scan 120/496/2,016/8,128 edges in both variants. This is quadratic BFS growth; cached hashes do not repair it. No reachability cache, topological ordering, task interning or lifecycle change is included. No allocation-byte or monitor-contention saving is inferred from these counters.

## Correctness validation

The focused cycle suite passes **21/21** tests with zero failures/errors. It covers skipped duplicate traversal using endpoint equality rather than hash counting or timing, cycle closure after duplicates, repeated rejected edges and self-cycles, accepted-edge rereads after rejection, historical completed-slot reads, independent slots and roots, writer uniqueness and cached hashes over a copied path. Concurrent tests assert graph presence by adding reverse edges after overlapping operations: 128 reader/writer rounds, 64 multiple-reader rounds, 32 duplicate-read rounds, competing registrations and concurrent cycle-closing edges.

The five-thread Resolution suite reports **606 tests, 0 failures, 0 errors, 4 skips**, using seed `424242`. The exact `./gradlew :core:engine:runtime2:check` reports **2490 tests, 0 failures, 0 errors, 166 skips**. The exact full check passes. [Validation summary](validation-summary.json), per-class JSON, focused XML and command logs retain the outcomes. No fixture generator or coverage guard is weakened.

## Source provenance and reproduction

Recorded 2026-10-09 01:12:12 UTC in `/home/raymie_stata/repos/3ht/projects/viaduct/oss` on `rstata--2perf`. [Environment](environment.json) records host `raymie-stata-codex`, KVM Intel Xeon 6975P-C, 32 cores / 64 logical CPUs, approximately 495 GiB RAM, no swap, and Corretto 21.0.4+7-LTS. Default heap settings are retained.

[Baseline source](baseline-source.json) and [final source](final-source.json) record commit, immutable production/support/JMH tree identities, cycle-source blob, JAR SHA-256, normalized ZIP-content SHA-256 and corpus hashes. The final measured commit is the clean source snapshot before evidence is added by amendment; the final squashed commit retains those exact source trees. Support code, benchmark sources and corpus are identical between variants. The [JAR content comparison](jar-content-diff.json) changes only classes compiled from `CycleCheckState.kt`; all other JAR entries are byte-identical. Binary JARs and unmodified console/JSON originals remain outside Git under `/tmp/runtime2-cycle-consolidated-20261009`; checked-in text removes only trailing whitespace and redundant blank EOF lines.

Copy the benchmark and diagnostic scripts plus Java probes and manifest to a temporary directory outside Git. Build the benchmark JAR from each source, preserving it there as `baseline.jar` or `final.jar`, then return to the final source checkout and execute the benchmark script with the OSS root as its argument:

```sh
./gradlew :core:engine:runtime2:jmhJar --no-configuration-cache --console=plain
python3 run-benchmarks.py /path/to/viaduct/oss
```

`measurement-commands.json` records the exact Java invocation for every run. `run-diagnostics.py` takes the same root argument; its recorded ASM dependency path is host-specific. For validation:

```sh
./gradlew :core:engine:runtime2:test --tests 'viaduct.engine.runtime2.resolution.framework.CycleCheckStateTest' :core:engine:runtime2:jmhJar --no-configuration-cache --console=plain
./gradlew :core:engine:runtime2:test --tests 'viaduct.engine.runtime2.resolution.*' -Pviaduct.resolution.threadcount=5 -PresolverPropertySeed=424242 --no-configuration-cache --console=plain
./gradlew :core:engine:runtime2:check
```
