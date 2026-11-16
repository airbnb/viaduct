# Selection Forest Creation on Hetzner, 2026-11-15

This investigation measures Runtime2's conversion of an already validated graphql-java operation into its construction `SelectionForest`, with the decoding and materialization stages measured separately. The maintained benchmark description and reproduction instructions are in [Selection Forest Creation Benchmark](../../../testing/selection-forest-creation-benchmark.md). These results measure the current implementation; they do not measure a graphql-java normalized-document integration.

The source is Viaduct `a2483562b1ef964d9f5d2ad6206a771b78594dbf` plus the benchmark and parser-extraction changes in the working tree. The graphql-java fixtures are from commit `69c7bfbef920e5b95b7c295b60ef0652523eef3d`. The run used the Hetzner performance server, OpenJDK 25.0.5.0.3, JMH 1.36, one benchmark thread, two forks per case, two 30-second warmup iterations and three 60-second measurement iterations per fork, with the JMH GC profiler. The JVM used `-Xms2g -Xmx8g -XX:+UseG1GC`. Errors below are JMH 99.9% confidence half-widths; allocation is the GC profiler's normalized bytes per operation.

| Fixture | Method | Time (µs/op) | Allocation (B/op) |
| --- | --- | ---: | ---: |
| schema2-small | decodeOperation | 5.054 ± 0.066 | 6,488 |
| schema2-small | createMaterializeForest | 6.771 ± 0.094 | 5,272 |
| schema2-small | createConstructionForest | 0.401 ± 0.016 | 1,504 |
| schema2-small | createSelectionForest | 12.444 ± 0.194 | 13,756 |
| schema4-small | decodeOperation | 0.748 ± 0.135 | 1,064 |
| schema4-small | createMaterializeForest | 0.223 ± 0.004 | 1,032 |
| schema4-small | createConstructionForest | 0.042 ± 0.002 | 296 |
| schema4-small | createSelectionForest | 0.986 ± 0.018 | 2,376 |
| schema4-full | decodeOperation | 77,188.083 ± 2,418.270 | 108,307,265 |
| schema4-full | createMaterializeForest | 33,888.497 ± 460.905 | 99,706,048 |
| schema4-full | createConstructionForest | 16,898.133 ± 545.131 | 20,706,344 |
| schema4-full | createSelectionForest | 150,233.702 ± 913.663 | 229,423,977 |

For the upstream schema-4 stress query, the current complete conversion takes about 150.2 ms and allocates 229.4 MB per operation. The isolated decode, materialization and construction measurements are independent JMH cases, so their means and allocation counts should not be summed to reconstruct the complete case. The two schema-4 fixtures use the same schema and show that the query's expanded operation size drives the large cost: the tiny derived query completes conversion in 0.986 µs and allocates 2,376 B. Parsing, validation, schema creation, variable coercion, resolver execution and response construction are outside the timed region. Graphql-java counts 259,832 field occurrences after fragment expansion; Runtime2's construction forest contains 187,215 selections after introspection fields are erased. This query should not be treated as representative production traffic.

The original three-fixture run began at 2026-11-15T09:56:40Z and its JMH phase took 1:06:50. Its derived `schema4-small` query contained a field invalid for its parent type, so all four methods for that fixture failed during trial setup; the eight other method/fixture combinations completed and are retained in [original results](results/results.json) and [original log](results/jmh.log). The corrected `schema4-small` query was rerun alone beginning at 2026-11-15T11:06:27Z; its JMH phase took 0:33:49 and all four results completed without failures, retained in [corrected results](results-schema4-small-corrected/results.json) and [corrected log](results-schema4-small-corrected/jmh.log). The two JMH phases total 1:40:39; build, transfer and the gap between runs are excluded. A clean full matrix schedules exactly 96 minutes of JMH iterations, plus setup and teardown.

The original run's JAR SHA-256 is `6e9cc02ab252e2e33712542d22fe99dcc574766b1453ba50896576cc6499e997`; the corrected run's is `dfa196fb11b4e2d978aa3c4dee266e1d9c65a1800274b879ccfeb9ca9f6933dd`. The [original metadata](results/metadata.txt) and [corrected metadata](results-schema4-small-corrected/metadata.txt) record the JDK, JAR path, host kernel and exact scheduling. In both runs, saved and restored CPU governor and update-service state files match byte-for-byte: [original state](results/host-original-state.json), [original restored state](results/host-restored-state.json), [corrected original state](results-schema4-small-corrected/host-original-state.json) and [corrected restored state](results-schema4-small-corrected/host-restored-state.json).
