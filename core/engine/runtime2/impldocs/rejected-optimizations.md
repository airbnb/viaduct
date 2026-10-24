# Rejected Optimizations

Optimizations evaluated against the resolver-only overhead benchmark (100 queries
× 20 loops, precomputed construction demand, baseline ~27.3s / ~19.57 GB allocated)
and rejected for failing to clear a 0.75% threshold in wall-clock time or allocated
bytes. Each was either implemented and measured or rejected on profiling evidence
without implementation.

- **Intern possibleTypes singleton sets** — `setOf(type)` at merge sites. Implemented,
  benchmarked: no measurable impact. `Collections.SingletonSet` is ~32 bytes; a few
  thousand per pass is negligible against 19.6 GB total.

- **`===` reference equality on schema fields in key equals** — Replaced
  `field == other.field` with `field === other.field` in all key types. Probes
  confirmed 100% of field comparisons already use reference-equal objects. Measurable
  in the JFR profile (Intrinsics.areEqual dropped from 6.3% to 5.3%) but wall-clock
  was flat (27.6s both ways) — the JIT already inlines the round trip. Reverted
  because `==` is the idiomatic Kotlin way to express semantic equality.

- **Eliminate iterator allocation in AbstractSelectionForest.forEach** — Replaced
  `occurrences.forEach(action)` with an indexed loop. `ArrayList$Itr` allocation
  dropped 25%, total runtime allocation dropped 297 MB (1.5%). Wall clock flat.
  Reverted — below the 0.75% significance bar.

- **Cons-cell path representation** — Replace `List<PathComponent>` with linked cons
  cells for O(1) append. JFR showed only 2 of 1,293 CPU samples (0.15%) in path-copy
  `Arrays.copyOf`. CycleTask and ResolverOccurrenceId already cache their path
  hashCodes. Not implemented — well below threshold.

- **Vended operation-scoped integer identifiers** — Replace ResolverOccurrenceId,
  VariableInstanceId, and CycleTask with interned vended ints. Implemented end-to-end
  with a dual structural/vended architecture. Benchmarked: 27.66s / 19.71 GB vs
  baseline 27.28s / 19.58 GB — a wash or slight regression. The interning
  ConcurrentHashMap lookups cost as much as the structural equality they replace,
  because structural comparison is load-bearing (independently created IDs for the
  same logical occurrence must match in the binding map).
