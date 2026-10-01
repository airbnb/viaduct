# 2026-08-22 Regression Investigation

Runtime revision: `91303870b87fe08cbb030ac4f28f4f7b0edbbe24`

The runtime tree was clean at this revision while the benchmarks and profiles ran. The performance-log documentation was added afterward. Host, JVM, benchmark iterations, workload statistics, interpretation, and controlled historical runs are recorded in the [corresponding performance-log entry](../../resolver-profiling.md#2026-08-22-171204-utc).

Profiles used three prepared-workload repetitions. Equivalent commands using the default output paths:

```shell
./gradlew :engine:runtime2:propertyTestProfile -PpropertyTestBenchmarkLoopCount=3 --console=plain
./gradlew :engine:runtime2:resolutionOverheadProfile -PresolverBenchmarkLoopCount=3 --console=plain
./gradlew :engine:runtime2:correctResolutionProfile -PcorrectResolutionBenchmarkLoopCount=3 --console=plain
```

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
