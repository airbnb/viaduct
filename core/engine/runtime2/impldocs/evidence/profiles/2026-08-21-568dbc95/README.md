# 2026-08-21 Closing Profiles

> **Historical snapshot.** Commands, paths, source names, and measurements below describe this recorded round and are not current operating guidance.

Recorded base revision: `568dbc95d6b93342ed94b770814c95624d5fd291`

This round predated the clean-tree protocol. Its performance-log entry records profiling and corpus changes in the worktree, including a query snapshot absent from the base revision, so the commit alone does not reproduce the exact runtime tree. The preserved reports are authoritative evidence for the recorded profiles. Host, JVM, benchmark iterations, workload statistics, and interpretation are recorded in the [corresponding performance-history entry](../performance-history.md#2026-08-21-143331-utc).

The recordings used three prepared-workload repetitions. The raw JFR files were recovered from `qplan/../core/engine/runtime2/build/reports/resolver-benchmarks` in a sibling worktree.

Corpus SHA-256 at the later checked-in state:

```text
7b0f5dcbde32ebc03d10c11606325b5fa936980724732f357bae3c520f3236e4  current-profile/queries.json
c132b1694c336baed8bd07c973b1c7f4bdaf12eb0ff3196e5372c4b23fa3424c  current-profile/registry.json
563e18f0c4a6220ab1250d066a186017340db178e9990ce0f5c0196768be4010  current-profile/schema.graphqls
45c7c9d424b1083e8cccf858745e9e8625f84b91585b36337ec6cb5452d48a11  property-test/provenance.txt
7d90bd953b1106983b9c2acf91839d825a995c96f22b1ccb771315a099bf7bd8  property-test/query.graphql
4c7df97332a61a9a7a056eecc19ef7d196ec160bd6e5dda6de2ca23ca4961415  property-test/registry.json
24176584cf87433838d61e3ba7f719097a2999c659e3fe2ddbd2805cc7039367  property-test/schema.graphqls
```
