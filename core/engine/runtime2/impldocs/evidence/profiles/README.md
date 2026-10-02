# Resolver Profile Evidence

Each directory preserves the text evidence for one profiling round. The corresponding entry in [`../resolver-profiling.md`](../resolver-profiling.md) owns the interpretation and benchmark results.

Create reports from each raw JFR recording with:

```shell
./export-resolver-profile.sh RECORDING.jfr impldocs/semantics/profiles/ROUND/TARGET
```

The exporter retains the recording summary, property phase events when present, flat hot-method and allocation reports, GC pauses, the top aggregated execution and allocation stacks, and the raw recording checksum. Raw JFR recordings are optional and are not checked in.
