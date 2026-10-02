# 2026-09-04 Resolver Output Construction Recovery

Runtime revision: `44e941921f6372ddb6a415c826ce35af4d8abbbc`; final test-only revision: `6bb476427b4858a8f3d4a33db429e91f9ebfd64b`

The runtime tree was clean at `44e941921` while the final benchmarks and profiles ran. The final revision only marks the deliberately disabled argument-bearing resolver-output rejection test as ignored. This README and the performance-log documentation were added afterward. Host details, benchmark iterations, workload statistics, controlled intermediate results, and interpretation are recorded in the [corresponding performance-log entry](../../resolver-profiling.md#2026-09-04-002258-utc).

Final validation and default benchmark controls ran serially:

```shell
./gradlew :engine:runtime2:resolutionOverheadBenchmark --console=plain
./gradlew :engine:runtime2:correctResolutionBenchmark --console=plain
./gradlew :engine:runtime2:propertyTestBenchmark --console=plain
./gradlew :engine:runtime2:test :engine:runtime2:test :engine:runtime2:test --console=plain
```

Final profiles used three prepared-workload repetitions. Equivalent commands using the default output paths:

```shell
./gradlew :engine:runtime2:resolutionOverheadProfile -PresolverBenchmarkLoopCount=3 --console=plain
./gradlew :engine:runtime2:correctResolutionProfile -PcorrectResolutionBenchmarkInputCount=50 -PcorrectResolutionBenchmarkQuerySeed=1 -PcorrectResolutionBenchmarkLoopCount=3 --console=plain
```

The reports in each profile subdirectory were produced with `qplan/export-resolver-profile.sh`. Each `recording.sha256` records the original recording's checksum.

Host and JVM: `raymie-stata-codex`; one Intel Xeon Platinum 8375C socket, 32 physical cores / 64 vCPUs, 495 GiB RAM, no swap, and one NUMA node; Corretto 21.0.4.

Corpus SHA-256:

```text
7b0f5dcbde32ebc03d10c11606325b5fa936980724732f357bae3c520f3236e4  current-profile/queries.json
c132b1694c336baed8bd07c973b1c7f4bdaf12eb0ff3196e5372c4b23fa3424c  current-profile/registry.json
563e18f0c4a6220ab1250d066a186017340db178e9990ce0f5c0196768be4010  current-profile/schema.graphqls
b48e37e1c2f3030d9fcdeed463016ca17d3fd28ad82bb8cac3643709ea352f3b  property-test/provenance.txt
7d90bd953b1106983b9c2acf91839d825a995c96f22b1ccb771315a099bf7bd8  property-test/query.graphql
4c7df97332a61a9a7a056eecc19ef7d196ec160bd6e5dda6de2ca23ca4961415  property-test/registry.json
24176584cf87433838d61e3ba7f719097a2999c659e3fe2ddbd2805cc7039367  property-test/schema.graphqls
```
