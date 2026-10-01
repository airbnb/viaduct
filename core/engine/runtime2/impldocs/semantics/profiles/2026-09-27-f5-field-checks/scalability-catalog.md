# F5 Scalability Catalog

This catalog distinguishes unresolved workload limits handled by reducing generated inputs from implementation costs repaired without shrinking the reproducer. All runs used the qplan Gradle project in `/home/raymie_stata/repos/1rv/qplan`; the request timeout remained 15 seconds. Counts below describe observed work before cancellation, not a completed graph or a supported-size limit.

## Input reductions

| ID | Original workload and reproducer | Observed limit | Input reduction | Remaining limitation |
| --- | --- | --- | --- | --- |
| F5-S1 | Runtime checker denial, seed `4984216598120621281`, product `10:3:5`, case `6:1:2`; resolver fragments at depth two, every resolver coordinate checked, and two independently bound named pairs copying each resolver's full object/Query fragments | A single resolution timed out after 15 seconds; the counting probe observed 129,788 resolver invocations and 52,321 associated Query OERs after 15,379 ms including cancellation | The broad Resolution success, denial, and mixed generator profiles now use `ResolverFragmentDepth=1`. Product sizes, the timeout, per-case correctness and exact application oracles, and required activated signatures are unchanged. | Depth-two generated workloads can still exceed the request budget. Distinct symbolic owners must remain distinct even when arguments ground equally, so duplicate pair dependencies amplify the actual occurrence graph. This is an operational scalability limit, not a claim that depth-two semantics are incorrect or now fast. |

The depth limit applies to random resolver fragments, not to the model or runtime API. Generated parent spines still exercise multilevel parent demand; deterministic contracts cover nested checker fragments, lists, independently bound named pairs, parent paths, and local inclusion. The grounded Resolver23 profiles retain depth two. The existing checker-free Resolution deep stress also retains its original workload: 10,000 cases at seed `424242` passed, verifying 951,771 resolver applications and a minimum depth of four. A future scalability investigation should measure both final occurrence counts and expansion costs as fragment depth, dependency fan-out, named-pair count, and runtime variable ownership vary independently. Merely increasing the timeout would not explain that growth.

The initial denial JFR and source state are preserved in [`denial-timeout`](./denial-timeout). Its CPU samples contain closure/parent-demand expansion, concrete argument reconstruction, forest concatenation/merging, and cycle-graph work; the evidence does not support classifying this as an idle dependency hang. The counting probe ran after the concrete-key reuse optimization and still timed out, so that optimization alone did not resolve F5-S1.

### Reproducing F5-S1

Use a disposable checkout of `a3dd6fa65` and apply the preserved patch from the repository root. The compressed patch contains the complete intermediate F5 source, including untracked additions; its applicability to that base was checked with a temporary Git index. The probe patch adds concrete-key reuse and the exact temporary counters used above. Do not apply these historical patches on top of the delivered F5 tree.

```shell
gzip -dc /absolute/path/to/denial-timeout/source.patch.gz | git apply
git apply /absolute/path/to/denial-timeout/counting-probe.patch
cd qplan
./gradlew :engine:runtime2:resolverPropertyReplay \
  -PresolverPropertyClass=viaduct.engine.runtime2.resolution.FieldCheckerGeneratedTest \
  -PresolverPropertyProfile=resolution-field-checker-denial \
  -PresolverPropertySeed=4984216598120621281 \
  -PresolverPropertySize=10:3:5 -PresolverPropertyCase=6:1:2 \
  --console=plain -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=1g' --max-workers=2
```

The probe writes its counts to the test XML's `system-err` section. These are diagnostics, not assertions retained in the test suite. Replaying those coordinates in the delivered tree uses the new depth-one generator and therefore does **not** recreate the original input.

## Repairs without input reductions

| ID | Evidence | Repair and verification | Limit of the evidence |
| --- | --- | --- | --- |
| F5-P1 | Success profile, seed `424242`, product `50:5:10`, case `22:1:4`, exceeded the request timeout with the initial F5 successor expansion | Restored memoization by resolver/checker boundary, excluding stack-dependent recursion-cut results. The original depth-two case then passed. Recursive concrete-key compaction also bounds duplicate passive producer forests, covered by a shared-dependency diamond regression. | The complete replay took 54 seconds including generation, two executions, and independent oracle work; this is not a per-request timing or controlled benchmark speedup. It does not establish that all depth-two workloads fit the request budget. |
| F5-P2 | Repeated symbolic-ancestry hashing, owner-by-owner replay of shared Query results, and repeated coercion of already-concrete keys appeared during investigation | Cache immutable occurrence hashes, share result-identity replay caches within one correctness judgment, and reuse canonical concrete keys. Semantic identity, owner-local projection checks, and abstract-field retargeting remain intact. | These changes reduce redundant work but did not eliminate F5-S1. No controlled before/after benchmark was captured. |
