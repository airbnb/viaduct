# Resolver Profile Evidence

This directory is an archive of dated Runtime2 profiling snapshots. Each round preserves the commands, source names, task paths, environment, and measurements that applied when it was recorded; historical details are intentionally not rewritten to match the current build. The corresponding entry in [Performance History](performance-history.md) owns the interpretation and benchmark results. Use [Performance Testing](../../testing/performance.md) for current benchmark, profiling, reporting, and export instructions.

Raw reports and checksums are evidence, not current performance expectations. Compare rounds only when their recorded host, JVM, parameters, corpus, and semantic workload are compatible.

Create reports from each raw JFR recording with:

```shell
core/engine/runtime2/export-resolver-profile.sh \
  RECORDING.jfr \
  core/engine/runtime2/impldocs/evidence/profiles/ROUND/TARGET
```

The exporter retains the recording summary, property phase events when present, flat hot-method and allocation reports, GC pauses, the top aggregated execution and allocation stacks, and the raw recording checksum. Raw JFR recordings are optional and are not checked in.
