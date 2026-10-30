# Plan cache, second step

This round measures the second step of the [plan cache](../../../architecture/plan-cache.md): operation `@skip`/`@include` decoded per variable values, and closure templates whose FromArgument variables, in required-selection guards and argument expressions, are renamed to each occurrence. It compares `ENGINE2_PLAN_CACHE` off and on through `StandardViaduct` with `planCacheBenchmark -PplanCacheBenchmarkProfile=step2`.

## Source and environment

The measured tree is commit `bcd036525` (the first step) plus the uncommitted second-step change; the recorded commit alone does not reproduce the run. Host: Apple M1 Pro, 10 cores, 32 GiB, macOS 27.2, a laptop that was otherwise idle but not isolated. JMH 1.36 forks used the Gradle toolchain JVM, OpenJDK 17.0.19.

## Validation

- `PlanCacheTest`: 5 of 5 pass, including FromArgument `@include` guards and argument expressions over a list of abstract types, compared with uncached resolution on results, `correctResolution`, resolver application counts, and the demand supplied to every tenant resolver; and the exclusion of provider-path variables.
- `OperationSelectionCacheTest`: 4 of 4 pass, including per-value caching of conditional documents.
- `:core:engine:runtime2:test` with `usePlanCache` temporarily defaulted to true in both `Assumptions.of` and `Engine2`: 279 suites, 3,816 tests, 0 failures, 0 errors, 168 skipped.
- Every `planCache=on` trial compared all 100 responses' data with an uncached engine over the same workload and directive values during setup; all agreed.

## Workloads

`PlanCacheWorkload.generate(seed, 100, profile = STEP2)` with list size 2: the first step's profile plus generated FromArgument resolver variables, which pass owner arguments into required-selection arguments. Trial setup declares `$c0: Boolean!` and `$c1: Boolean!` on every operation and guards every seventh field with `@include(if: $c0)` and every eleventh other field with `@skip(if: $c1)`. With `directiveValues=fixed` every request includes every guarded field; with `rotating` requests cycle through all four combinations, so each operation keeps four decoded forests.

| Seed | Valid operations | Response fields, mean / max | Response objects, mean | FromArgument variables in the registry |
| --- | ---: | ---: | ---: | ---: |
| 7 | 100 / 100 | 233.5 / 404 | 44.3 | 16 |
| 3 | 100 / 100 | 271.5 / 508 | 51.4 | 17 |

## Results

Seed 7 used `-PresolverBenchmarkLoopCount=2 "-PplanCacheBenchmarkJmhOptions=-wi 15 -i 15 -f 3"`: single-shot operations of 200 requests, 45 measured samples per configuration. Seed 3 costs about 37 ms per request, so its run used `-PresolverBenchmarkLoopCount=1 "-PplanCacheBenchmarkJmhOptions=-wi 5 -i 10 -f 2"`: operations of 100 requests, 20 measured samples per configuration. Both used `documents=shared`. Errors are JMH's 99.9% confidence errors; the raw results are [`jmh-seed7.json`](jmh-seed7.json) and [`jmh-seed3.json`](jmh-seed3.json).

| Seed | Directive values | Off, ms/op | On, ms/op | Per request, ms | Change |
| --- | --- | ---: | ---: | ---: | ---: |
| 7 | fixed | 409.0 ± 8.8 | 323.2 ± 16.6 | 2.045 → 1.616 | −21.0% |
| 7 | rotating | 373.9 ± 18.4 | 264.0 ± 12.1 | 1.870 → 1.320 | −29.4% |
| 3 | fixed | 3709.1 ± 90.8 | 3680.6 ± 43.2 | 37.091 → 36.806 | −0.8% |
| 3 | rotating | 3123.7 ± 56.5 | 3102.7 ± 40.8 | 31.237 → 31.027 | −0.7% |

On seed 7 the cache removes 0.43 ms per request with fixed directive values and 0.55 ms with rotating values, which keep four decoded forests per operation and still hit for every combination after warmup; this round did not attribute the larger rotating saving. On seed 3 the differences lie within the confidence intervals: neither a gain nor a regression is established. Its requests cost about eighteen times seed 7's, so the planning work the cache removes is a small share of them; this round did not attribute the remaining time. An interrupted run of seed 3 at seed 7's settings recorded 45 samples for fixed values with the cache off before it was stopped, averaging 8,120 ms per 200 requests; it is not reported as a comparison.

## Seed 3 profile and fixture correction

A JFR recording of one measured seed-3 batch with the cache on, taken with `planCacheProfile` after five warmup operations, attributed 92.3% of execution samples to the fixture's prepared-output construction, 55.4% to node references that construction creates through `createNodeReference`, 0.8% to closure, 0.2% to successor demand, 2.7% to passive resolution, and 1.2% to cycle checking. Shares are inclusive and overlap. A temporary counter then showed why: fixture field and node resolvers rebuilt their complete, non-selective prepared outputs on every call, creating 273,044 node references per seed-3 request, against 886 for seed 7, while 138 node resolvers run per request. Engine2 projected nearly all of them away. Seed 3 also invokes 1,073 field and 138 node resolvers per request, against 121 and 26 for seed 7.

The benchmark adapter now builds a fixture field resolver's output only for the fields in the selection set Engine2 supplies, which is the demand Engine2 projects the output to. Non-selective node executors receive only an `id` placeholder and Engine2 reads every field they return, so prepared node bodies are still built completely. The correction preserves every response: hashes of all 100 responses matched the original adapter for step-2 seeds 7 and 3 and step-1 seeds 7 and 12, resolver callback counts were unchanged, and `StandardViaductBenchmarkTest` still found both engines agreeing on all 100 engine-comparison queries. Seed 3 then creates 83,103 node references per request and costs about 18 ms per request with the cache off.

Re-measured with the corrected adapter (`-PresolverBenchmarkLoopCount=1 "-PplanCacheBenchmarkJmhOptions=-wi 10 -i 15 -f 3"`: operations of 100 requests, 45 measured samples per configuration; [`jmh-seed3-selected-fixture-outputs.json`](jmh-seed3-selected-fixture-outputs.json)), the measured tree being `ab4876b3a` plus the uncommitted adapter correction:

| Seed | Directive values | Off, ms/op | On, ms/op | Per request, ms | Change |
| --- | --- | ---: | ---: | ---: | ---: |
| 3 | fixed | 1814.3 ± 22.3 | 1768.6 ± 13.3 | 18.143 → 17.686 | −2.5% |
| 3 | rotating | 1535.1 ± 21.1 | 1475.9 ± 18.4 | 15.351 → 14.759 | −3.9% |

The confidence intervals no longer overlap. The cache removes 0.46 and 0.59 ms per request, close to seed 7's 0.43 and 0.55 ms; seed 3's relative gain stays small because the fixture's complete node bodies dominate its requests. Every cached trial's responses matched an uncached engine.

