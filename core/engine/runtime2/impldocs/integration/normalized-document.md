# Normalized Selection Graph

Runtime2 uses the validated `NormalizedDocument` (ND) from graphql-java [PR #4562](https://github.com/graphql-java/graphql-java/pull/4562), pinned to `69c7bfbef920e5b95b7c295b60ef0652523eef3d`. The repository-wide dependency is `26.0-pr4562-69c7bfb`. This version is deliberately unpublished: CI dependency resolution cannot succeed until an upstream version is available. Local validation uses the pinned PR jar through the helper below; no published graphql-java version is mixed into the main or test classpaths.

## One Source Graph, Two Collection Operations

`Selection` retains a canonical invocation key, concrete-type applicability, inclusion condition, response key, field directives and child references. `SelectionForest` is the single source-graph API used by client demand, resolver and checker templates, variable binding, nested execution, projection and the maintained reference resolvers. `Fragment.subselections` exposes this graph directly. The former `MaterializeSelection` and `MaterializeSelectionForest` types and recursive `constructionSelections()` conversion have been removed.

`merge(type)` collects construction demand by canonical field invocation. `collectProjection(type)` collects compatible source occurrences by response key, returning a `ProjectionLayout`. Callers evaluate runtime inclusion guards before projection collection. The layout is an immediate-level index over the same children, not another recursively copied tree. Construction normalization deliberately discards alias identity when combining demands; the original source roots remain authoritative for each projection.

For `{ first: user(id: 1) { name } second: user(id: 1) { email } }`, construction requires one `user(id: 1)` with both child requirements, while projection retains `first { name }` and `second { email }`. Concatenation retains source order for projection. Two independent resolver roots may request different arguments under the same response key; their demands can be combined for construction, while each projection retains its own roots. Internal dependencies never become output fields merely because their nodes belong to the graph.

## Validated ND Backing

`NormalizedSelectionSource` interprets ND references through deferred forests. Creating a client-operation root does not decode fields or visit the selection graph. Reading a forest collects its immediate value level, iterating fragment scopes without expanding field descendants. Child forests are cached by selection-set ID and possible object types. Keys are decoded once per normalized source field within one request's variables, context, schema and locale. Caches never escape that binding environment or mutate ND.

Resolver and checker fragments are also normalized during validation. Viaduct permits standalone fragments and explicitly owned symbolic variables, so their validator disables only the unused-fragment and undefined-operation-variable rules. Literal decoding still rejects unowned variables. The PR exposes operation roots but not fragment roots; `withFragmentRoot` adds missing root/scope selection sets using validated ND fields and existing child IDs. It does not expand fragment occurrences. Unique template fields and conditions are checked at bootstrap so invalid templates fail before execution. Provider paths follow ND scopes directly, retaining nominal type-lossiness validation.

Variable qualification and resolver-occurrence instantiation create deferred, memoized views of the same graph. Static child graphs retain identity. Generated parent, checked and unchecked demands use native selection nodes in the same API because those dependencies are not fields in the client's GraphQL document. Source occurrence identity does not change resolver, result-occurrence or checker identity.

Mutations collect ND fields by response key in source order before ordered execution, including mutation namespaces. Custom `ConditionalNodeDecision` callbacks use eager ND interpretation without memoizing effectful decisions; this preserves source-occurrence callback evaluation and mutable-context observations. The ordinary path remains shared and deferred. Successor-demand computation and execution still visit the selections they need; this change removes duplicate source representations and preparation passes, not every execution traversal.

## Execution And Cache Boundary

`RequestLifetimeInstrumentation`, already required by Runtime2's GraphQL execution strategy, enables ND before validation. This covers `Engine2.execute`, direct `getGraphQL()` callers and the execution harness. Preparsing providers must retain the entire successful `PreparsedDocumentEntry`, distinguish schemas and ND creation settings, and invalidate older AST-only entries. The incremental-label-validation wrapper preserves successful entries. Missing cached ND fails explicitly; production has no AST reconstruction fallback.

The older engine uses GJ's ND-based lookahead collector and enables ND in its GraphQL configuration. `QueryComplexityLimits.NONE` already disables the replaced good-faith introspection limits. Shared schema directive calls use the PR's current APIs. Runtime2's eager AST decoder and source-world spec models remain only in unpublished test fixtures as independent controls and benchmark references.

## Local Build And Tests

Build the pinned GJ commit with `./gradlew buildFinalJar` in its own checkout. Then, from the Viaduct repository root:

```sh
core/engine/runtime2/scripts/with-graphql-java-pr.sh /path/to/graphql-java-0.0.0-HEAD-SNAPSHOT.jar test --no-configuration-cache
core/engine/runtime2/scripts/with-graphql-java-pr.sh /path/to/graphql-java-0.0.0-HEAD-SNAPSHOT.jar :core:engine:runtime2:check --no-configuration-cache
```

The helper creates an isolated temporary Maven repository and supplies it to all included builds and plugin resolution. It does not install or overwrite an artifact in the user's Maven repository. Omitting task arguments runs Runtime2's full check. The pinned dependency remains unavailable to an ordinary remote CI build.

The [benchmark guide](../testing/normalized-document-benchmark.md) distinguishes creation of the initial view from complete forest consumption. The [full migration measurement](../evidence/profiles/2026-11-16-unified-normalized-selection-graph/README.md) reports **147.726 → 6.294 ms/op** and **237.827 → 14.997 MB/op** for fresh forest creation plus complete traversal of schema 4’s large query: **95.74% less time and 93.69% less allocation**. Validation and execution are excluded. The report retains raw data, source identities, host restoration and the successful repository-wide local test run. Earlier prototype results remain historical evidence for their own source revisions.
