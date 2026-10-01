# 2026-08-22 Performance Recovery

Runtime revision: `5193dec7ba656b6c748d8a39bd28a431b31d60e2`

The runtime tree was clean at this revision while the final tests, benchmarks, and profiles ran. This README and the performance-log documentation were added afterward. Host details, benchmark iterations, workload statistics, controlled intermediate results, and interpretation are recorded in the [corresponding performance-log entry](../../resolver-profiling.md#2026-08-22-174908-utc).

Final validation and default benchmark controls ran serially:

```shell
./gradlew :engine:runtime2:test :engine:runtime2:test --console=plain
./gradlew :engine:runtime2:resolutionOverheadBenchmark --console=plain
./gradlew :engine:runtime2:correctResolutionBenchmark --console=plain
./gradlew :engine:runtime2:propertyTestBenchmark --console=plain
```

Final profiles used three prepared-workload repetitions. Equivalent commands using the default output paths:

```shell
./gradlew :engine:runtime2:resolutionOverheadProfile -PresolverBenchmarkLoopCount=3 --console=plain
./gradlew :engine:runtime2:propertyTestProfile -PpropertyTestBenchmarkLoopCount=3 --console=plain
./gradlew :engine:runtime2:correctResolutionProfile -PcorrectResolutionBenchmarkLoopCount=3 --console=plain
```

The reports in each profile subdirectory were produced with `qplan/export-resolver-profile.sh`. Each `recording.sha256` records the original recording's checksum.

Host and JVM: `raymie-stata-codex`; KVM guest with one Intel Xeon 6975P-C socket, 48 physical cores / 96 vCPUs, 371 GiB RAM, no swap, and two NUMA nodes; Corretto 21.0.4.

Corpus SHA-256:

```text
7b0f5dcbde32ebc03d10c11606325b5fa936980724732f357bae3c520f3236e4  current-profile/queries.json
c132b1694c336baed8bd07c973b1c7f4bdaf12eb0ff3196e5372c4b23fa3424c  current-profile/registry.json
563e18f0c4a6220ab1250d066a186017340db178e9990ce0f5c0196768be4010  current-profile/schema.graphqls
45c7c9d424b1083e8cccf858745e9e8625f84b91585b36337ec6cb5452d48a11  property-test/provenance.txt
7d90bd953b1106983b9c2acf91839d825a995c96f22b1ccb771315a099bf7bd8  property-test/query.graphql
4c7df97332a61a9a7a056eecc19ef7d196ec160bd6e5dda6de2ca23ca4961415  property-test/registry.json
24176584cf87433838d61e3ba7f719097a2999c659e3fe2ddbd2805cc7039367  property-test/schema.graphqls
```
