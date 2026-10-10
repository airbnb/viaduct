# Inclusion Review-Fix Benchmark Controls

These end-to-end Resolution controls compare master `67ec32572b997293ab0879a130292c15457131b9` with the complete fixed PR1+PR2 stack at `c80bcc5e5e5207dbab1c55f8e69c0ec443dfea3b`. The master control uses commit `bb5e7e7c70c2cb19eac5665c44d5bd84286f9fd5` on local branch `codex/superlinear-master-control`: only the shared benchmark harness, current corpus, and guarded diagnostic are backported. `git diff --exit-code 67ec32572b997293ab0879a130292c15457131b9 bb5e7e7c70c2cb19eac5665c44d5bd84286f9fd5 -- ':(glob)projects/viaduct/oss/**/src/main/**'` verifies no production-source changes. This is a master baseline, not a comparison against `rstata--1perf-foundation`. Both trees were clean during measurement. The final PR2 revision updates this evidence without changing the measured Kotlin sources.

Recorded 2026-10-08 06:28:19 UTC in session `01a10336-e7f9-7e63-89ed-7460ea30dbef`. Host `raymie-stata-codex`: one Intel Xeon 6975P-C socket, 32 physical cores / 64 logical CPUs, 495 GiB RAM, no swap, one NUMA node, and `cpu.max=max 100000`. These controls ran serially, without overlapping test runs, using Corretto 21.0.4+7-LTS and JMH 1.36, one fork and one JMH thread, default compiler blackholes, and no benchmark JVM heap override.

At each revision, from the OSS root:

```sh
./gradlew :core:engine:runtime2:resolutionOverheadBenchmark :core:engine:runtime2:guardedCheckerResolutionBenchmark --console=plain --no-configuration-cache
```

The two tasks executed sequentially. The broad corpus uses single-shot timing, one warmup, three measurements, and `loopCount=1`: one operation contains 100 resolutions. The guarded diagnostic uses average-time timing, three one-second warmups and five one-second measurements per depth: one operation contains one resolution. Changing guarded depth changes the registry's fixed transitive demand; it is not a fixed-registry/query-scaled witness. Neither benchmark times Engine2.execute, GraphQL Java execution, parsing, or correctness validation.

## Scores

| Benchmark | Base mean and JMH error | Fixed mean and JMH error | Work per operation | Fixed mean per resolution |
| --- | --- | --- | --- | --- |
| Fixed 100-query corpus | 3.586 +/- 2.251 s/op | 2.382 +/- 2.578 s/op | 100 resolutions | 23.820 ms |
| Guarded depth 3 | 0.247 +/- 0.006 ms/op | 0.122 +/- 0.002 ms/op | 1 resolution | 0.122 ms |
| Guarded depth 6 | 1.697 +/- 0.050 ms/op | 0.249 +/- 0.060 ms/op | 1 resolution | 0.249 ms |
| Guarded depth 9 | 22.356 +/- 0.796 ms/op | 0.423 +/- 0.014 ms/op | 1 resolution | 0.423 ms |
| Guarded depth 12 | 720.351 +/- 45.881 ms/op | 0.679 +/- 0.027 ms/op | 1 resolution | 0.679 ms |

The broad corpus mean is 33.6% lower, but its three single-shot samples have wide JMH confidence intervals; that percentage is descriptive, not evidence of statistical significance. The depth-12 diagnostic is about 1,061 times faster. The stack retains guarded demand as shared DAGs instead of materializing DNF products, processes construction closure by new frontiers, avoids redundant transformation validation, and uses symbolic forest concatenation. These are combined stack measurements against master, not isolated ablations of any one optimization or the latest review fixes.

## Every Measured Iteration

| Benchmark | Base iterations | Fixed iterations |
| --- | --- | --- |
| 100-query Resolution corpus | 3.725 s/op, 3.490 s/op, 3.543 s/op | 2.545 s/op, 2.304 s/op, 2.297 s/op |
| Guarded depth 3 | 0.248 ms/op, 0.249 ms/op, 0.247 ms/op, 0.246 ms/op, 0.245 ms/op | 0.121 ms/op, 0.122 ms/op, 0.121 ms/op, 0.122 ms/op, 0.122 ms/op |
| Guarded depth 6 | 1.718 ms/op, 1.699 ms/op, 1.685 ms/op, 1.696 ms/op, 1.687 ms/op | 0.242 ms/op, 0.239 ms/op, 0.243 ms/op, 0.277 ms/op, 0.244 ms/op |
| Guarded depth 9 | 22.673 ms/op, 22.441 ms/op, 22.301 ms/op, 22.196 ms/op, 22.170 ms/op | 0.426 ms/op, 0.420 ms/op, 0.427 ms/op, 0.420 ms/op, 0.421 ms/op |
| Guarded depth 12 | 701.008 ms/op, 733.561 ms/op, 722.590 ms/op, 724.121 ms/op, 720.474 ms/op | 0.685 ms/op, 0.674 ms/op, 0.670 ms/op, 0.684 ms/op, 0.684 ms/op |

## Corpus Identity And Statistics

Both runs used exactly the same resources and emitted identical statistics. SHA-256:

- `schema.graphqls`: `70d662d35ce271f1f5867cfbc60ea3fbdccf74a852433225448a62e10398f85e`.
- `registry.json.gz`: `525f75fd9ec08b24c379bfdd86ae802d1a777cb186570eb015129da117e18a0e`.
- `queries.json`: `5157978ea78a8c51f6359f8f07b8e240e2675432e0ca590e3b81abfef41a308c`.

```text
Resolver overhead corpus statistics (100 queries):
  fields returned: average=296.39, p90=422, max=487
  active fields returned: average=115.54, p90=165, max=202
  passive fields returned: average=180.85, p90=260, max=291
  passive fields per active field: average=1.60, p90=1.92, max=2.56
  resolvers executed: average=280.27, p90=386, max=453
  field checkers executed: average=84.94, p90=114, max=133
  type checkers executed: average=46.48, p90=64, max=74
  resolver executions with variable-bearing arguments: average=48.00, p50=49, max=88
  variable-bearing arguments per such resolver execution: average=1.42, p90=3, max=3
  maximum variable stack depth: average=1.84, p50=2, max=2
  result depth: average=7.00, p90=7, max=7
Resolver benchmark registry statistics:
  active fields per non-Query object: average=1.92, p90=3, max=4
  passive fields per non-Query object: average=14.85, p90=17, max=19
  selections per object fragment: average=4.89, p90=22, max=48
  object fragment depth: average=1.61, p90=5, max=7
Resolver benchmark feature counts:
  field resolvers: 38
  field checkers: 26
  type checkers: 13
  Query fragments: 10
  FromArgument variables: 2
  FromObjectField variables: 14
  FromQueryField variables: 15
  FromProvider variables: 25
```

The raw console logs are retained locally at `/tmp/1perf-review-master-benchmarks.log` and `/tmp/1perf-review-master-final-benchmarks.log`; all measured iterations, scores, parameters, resource hashes, and emitted corpus statistics are preserved above.
