# Plan cache, first step

This round measures the first step of the [plan cache](../../../architecture/plan-cache.md) through `StandardViaduct` on Engine2. It compares `ENGINE2_PLAN_CACHE` off and on over generated workloads restricted to field and node resolvers with object and Query fragments, lists, interfaces, and unions; the workloads have no `@skip`/`@include`, `@parent`, nested operations, root-field references, sometimes-passive fields, access checkers, or resolver variables, and their operations declare no variables.

## Source and environment

The measured tree is base revision `1c7799922` plus the uncommitted plan-cache change; the recorded commit alone does not reproduce the run. Host: Apple M1 Pro, 10 cores, 32 GiB, macOS 27.2, a laptop that was otherwise idle but not isolated. JMH 1.36 forks used the Gradle toolchain JVM, OpenJDK 17.0.19. The diagnostic breakdown below used Corretto 25.0.2 outside JMH.

## Validation

- `PlanCacheTest`: 3 of 3 pass; `OperationSelectionCacheTest`: 3 of 3 pass.
- `:core:engine:runtime2:test` with `usePlanCache` temporarily defaulted to true in both `Assumptions.of` and `Engine2`: 278 suites, 3,810 tests, 0 failures, 0 errors, 168 skipped.
- Every `planCache=on` trial compared all 100 responses' data with an uncached engine over the same workload during setup; all agreed.

## Workloads

`PlanCacheWorkload.generate(seed, 100)` with list size 2. Every generated operation validated after alias repair.

| Seed | Valid operations | Response fields, mean / max | Response objects, mean / max | Responses with errors |
| --- | ---: | ---: | ---: | ---: |
| 7 | 100 / 100 | 233.5 / 404 | 44.3 / 75 | 2 |
| 12 | 100 / 100 | 255.5 / 430 | 48.6 / 81 | 0 |

## Results

Controlled runs used `-PresolverBenchmarkLoopCount=2 "-PplanCacheBenchmarkJmhOptions=-wi 15 -i 15 -f 3"`: single-shot operations of 200 requests, 15 warmup and 15 measured operations per fork, three forks, 45 measured samples per configuration. Errors are JMH's 99.9% confidence errors. The raw results are [`jmh-seed7.json`](jmh-seed7.json) and [`jmh-seed12.json`](jmh-seed12.json).

| Seed | Documents | Off, ms/op | On, ms/op | Per request, ms | Change |
| --- | --- | ---: | ---: | ---: | ---: |
| 7 | shared | 786.1 ± 6.5 | 592.3 ± 5.9 | 3.930 → 2.961 | −24.7% |
| 7 | fresh | 792.6 ± 12.6 | 797.7 ± 16.0 | 3.963 → 3.988 | +0.6% |
| 12 | shared | 183.9 ± 6.0 | 119.7 ± 3.2 | 0.919 → 0.598 | −34.9% |
| 12 | fresh | 183.7 ± 3.7 | 186.5 ± 5.3 | 0.918 → 0.933 | +1.6% |

With shared documents the cache removes 0.97 ms and 0.32 ms per request, 24.7% and 34.9% of request time. With fresh documents the differences lie within the overlapping confidence intervals; any cost of recording unused templates is below about 2%. An earlier short run at the default parameters (two forks, five warmup and ten measured operations of 100 requests; [`jmh-seed7-short.json`](jmh-seed7-short.json)) gave 407.8 ± 8.1 and 323.8 ± 19.0 ms/op for seed 7 with shared documents, 20.6% less time, and no significant fresh-document difference.

## Where the time goes

A temporary diagnostic, removed before the measured runs, timed the three planning components per request after 40 warm batches over 20 measured batches of 100 requests. Component times are summed across dispatcher threads; wall time is elapsed per request.

| Seed | Cache | Documents | Wall | Decode | Closure | Successor demand | Closures per request (hits) | Successor demands per request (hits) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 7 | off | shared | 3.147 ms | 0.193 ms | 0.777 ms | 1.154 ms | 354 | 253 |
| 7 | on | shared | 2.361 ms | 0.005 ms | 0.341 ms | 0.071 ms | 354 (354) | 253 (66) |
| 7 | on | fresh | 3.140 ms | 0.207 ms | 0.550 ms | 0.933 ms | 354 (305) | 253 (52) |
| 12 | off | shared | 0.784 ms | 0.149 ms | 0.172 ms | 0.180 ms | 48 | 50 |
| 12 | on | shared | 0.485 ms | 0.001 ms | 0.059 ms | 0.014 ms | 48 (48) | 50 (6) |
| 12 | on | fresh | 0.826 ms | 0.174 ms | 0.196 ms | 0.189 ms | 48 (13) | 50 (0) |

Successor demands without hits are mostly empty scalar demand, which is not memoized. With fresh documents most closure lookups still hit on seed 7, but the expensive analyses are the top-level ones over the whole operation, which are unique to each request; reuse within one request therefore saves little thread time and no wall time. The gain comes from reuse across requests, which the first step provides only for repeated documents with equal variable values.

## Caveats

The workloads are synthetic and generated rather than production traffic. Fixture resolvers return prepared outputs, so engine overhead dominates request time; real tenant work would dilute the relative gain while leaving the absolute saving per request unchanged. Operations with variables are cached only per coerced value set in this step, so production operations whose variable values vary behave like the `fresh` rows until symbolic operation variables are added.
