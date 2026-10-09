# Resolver Profile Evidence

This directory is an archive of dated Runtime2 profiling snapshots. Each round preserves the commands, source names, task paths, environment, and measurements that applied when it was recorded; historical details are intentionally not rewritten to match the current build. The corresponding entry in [Performance History](performance-history.md) owns the interpretation and benchmark results. Use [Performance Testing](../../testing/performance.md) for current benchmark, profiling, reporting, and export instructions.

Raw reports and checksums are evidence, not current performance expectations. Compare rounds only when their recorded host, JVM, parameters, corpus, and semantic workload are compatible.

## Upstream Source Context

The commit links in this archive refer to the public [`airbnb/viaduct`](https://github.com/airbnb/viaduct) repository. Use that repository when checking out a recorded revision. Some rounds also require preserved patches or describe uncommitted changes; their individual READMEs state whether the commit alone reproduces the measured source.

`qplan` was the development branch and build layout used before Runtime2 moved into `core/engine/runtime2`. The import came from [`rstata/viaduct` at `3ded35076`](https://github.com/rstata/viaduct/tree/3ded35076f053302bcfb0c9e8f57dd2f1896fd63). Historical `qplan/`, `semantics/`, and `:engine:runtime2` paths and task names below describe that upstream layout; they are preserved for reproduction against the recorded revisions. Current source lives in [`core/engine/runtime2`](../../..), and current commands are in the [performance guide](../../testing/performance.md). For numbered reference resolver names, use the [family comparison and source links](../../architecture/resolver-families.md#comparison-grid).

## Exporting Reports

Create reports from each raw JFR recording with:

```shell
core/engine/runtime2/export-resolver-profile.sh \
  RECORDING.jfr \
  core/engine/runtime2/impldocs/evidence/profiles/ROUND/TARGET
```

The exporter retains the recording summary, property phase events when present, flat hot-method and allocation reports, GC pauses, the top aggregated execution and allocation stacks, and the raw recording checksum. Raw JFR recordings are optional and are not checked in.
