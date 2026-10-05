# Engine API Integration

## Role And Boundary

Runtime2's main-source execution layer adapts validated GraphQL Java query and mutation execution and Engine API field, node, and checker dispatchers to production Resolution. It decodes source operations into the canonical model, constructs the request-local operation state, starts Resolution, and exposes the promise-backed result graph to GraphQL Java for ordinary and incremental completion.

The `QPlanExecutionStrategy`, `QPlanWiringFactory`, `QPlanInstrumentation`, and `runQPlanFeatureTest` names are current code identifiers inherited from the earlier project; they do not mean that Runtime2 is a separate build or that production Resolution is prospective.

`Engine2` implements `Engine.execute` and handle-based `Engine.resolveSelectionSet` and is selected by `StandardViaduct` when `ENGINE2_ENABLED` is enabled. The existing engine remains the default. Direct `Engine.resolveRootFieldReference` calls are intentionally unsupported: Runtime2 instead interprets `RootFieldReference` values returned by resolvers inside Resolution. Production integration reuses the service-built `DispatcherRegistry`; field, node, and checker invocation remains dispatcher-backed and receives a request-owned `EngineExecutionContext`. The direct executor adapter remains for focused integration fixtures; copied feature tests use production service wiring.

Interoperability between engine selections across `StandardViaduct.Builder.buildWithReusedSchemas` rebuilds is not part of the Runtime2 integration contract. A deployment must not rely on rebuilding an old-engine `StandardViaduct` as an engine2 instance, or the reverse, merely because the new instance reuses the previous instance's schema objects.

## Schema And Bootstrap Boundaries

Runtime2 retains two related schemas:

- The unchanged source `GraphQLSchema` owns public parsing, validation, field collection, input coercion, and response completion.
- The canonical lowered `ViaductSchema` owns Resolution fields, selections, registry entries, conformance, and subtype reasoning.

`ViaductAndGJSchema` pairs those schemas, and `SourceSchemaAdapter` performs explicit source-to-lowered translation. A request may use a scoped executable source schema while Resolution and resolver-required selections use the full source/lowered pair. Private fields required by resolvers therefore remain available to Resolution without becoming public operation fields.

`executorRegistryInputs` accepts an `EngineSchema`, its matching schema pair, explicit field and node executor registrations, an `EngineExecutionContext`, a field-selectivity provider, and the built-in-node option. It returns canonical field definitions, node functions, and variable declarations for `resolverRegistryOf`. Duplicate registrations, unsupported batching, malformed fragments, and invalid variable declarations fail before semantic reasoning. Namespace and optional Query-node built-ins fill only unsupplied coordinates; ordinary missing resolvers are not synthesized by the main-source adapter. This direct adapter remains useful for tests.

The returned direct-adapter resolver functions retain the supplied execution context. Neither those functions nor a registry built from them is service-wide metadata safe for unrelated request contexts. The production `dispatcherRegistryInputs` path instead compiles reusable dispatcher metadata and obtains the request context from each active `ResolutionExecutionContext`, so `Engine2` can safely retain its compiled world across requests.

## Request Execution

The GraphQL execution path is:

```text
GraphQL Java parsing, validation, and input coercion
  → StandardViaduct-selected Engine2
  → QPlanExecutionStrategy
  → source operation decoding into canonical selections
  → SharedOperationContext backed by immutable Assumptions
  → Resolution.startResolve in a request-owned coroutine scope
  → live ObjectEngineResult graph with frozen cell shape and possibly pending values
  → QPlanWiringFactory immediate values or CompletionStage bridges
  → GraphQL Java completion and @defer payload delivery
  → QPlanInstrumentation request-lifetime cleanup
  → ExecutionResult or IncrementalExecutionResult
```

`Engine2.execute` uses the service-configured `CoroutineInterop.scopedFuture` to capture the caller's coroutine context at the Java/coroutine boundary and passes that context to `QPlanExecutionStrategy`. The strategy preserves caller context elements, including framework request scope and thread-local coroutine bridging, while layering the borrowed Resolution dispatcher and an independently owned request job over them. The caller's job is not retained as the request parent, so incremental execution can outlive the initial response. The strategy never closes the borrowed dispatcher. `ExecutionTestFixture` owns and closes its default dispatcher or borrows an explicitly supplied context; fixture construction also closes a newly created dispatcher if setup fails.

The strategy places a `QPlanRequestLifetime` in the GraphQL context and starts Resolution once for the complete operation demand, including deferred selections. Query completion consumes the live result graph; mutation completion waits for ordered effects and payload resolution first. The operation context owns request-local bindings, cycle state, binding declarations, observer, and dispatcher references; immutable schema and registry configuration remains in `Assumptions`. `Engine2` supplies `Dispatchers.Default` to both strategies; the configurable fixed dispatcher used by focused test fixtures is not the service dispatcher.

`QPlanWiringFactory` performs GraphQL completion rather than tenant resolution. Checked completion reads the containing field-checker result, follows raw list and object values, reads each reached OER's type-checker result, and combines applicable results at the consumer boundary. Completed values project immediately; pending value or checker promises use request-owned completion-stage bridges. List elements wait concurrently, and a terminal non-cancellation failure takes precedence over sibling cancellations.

`QPlanInstrumentation` is required because GraphQL Java creates the incremental publisher after the query strategy returns. The instrumentation keeps the Resolution request alive until the publisher completes, fails, or is cancelled. Cancelling either `StandardViaduct.executeAsync`'s public future or a coroutine running suspending `StandardViaduct.execute` cancels GraphQL completion, nested resolution, and the owning Resolution request job without cancelling concurrent requests.

## Executor Adaptation

The production adapter discovers field, node, field-checker, and type-checker dispatchers by schema coordinate and converts their declarations into the canonical registry. Field and node calls remain dispatcher-backed; selective dispatchers receive Resolution's closed successor demand converted to an `EngineSelectionSet`, while simple outputs receive no selection set. `EngineConfiguration.fieldSelectivityProvider` supplies selectivity when dispatcher metadata does not.

Runtime2's semantic output domain is stricter than the mock feature-test surface. The adapter normalizes source-shaped EODs, concrete-object maps, built-in scalar values, nested `EngineErrorData`, node references, and root-field references before they enter Resolution. A raw map cannot represent an interface or union output because it lacks an unambiguous concrete runtime type. Ingress normalization must not weaken Runtime2 carrier invariants or hide a value produced incorrectly inside Resolution.

The existing service bootstrap constructs the production dispatcher registry. When `ENGINE2_ENABLED` is enabled, registry construction presents batching-capable field and node executors as immediate non-batching dispatchers; enabling `ENGINE2_BATCHING` is rejected until batching is implemented. Runtime2 currently creates a fresh production data loader for each dispatcher invocation, so neither completed results nor in-flight work are shared across semantic occurrences and every invocation is a singleton physical call. This is a transitional physical-dispatch policy rather than a permanent semantic prohibition on reuse. Future physical batching and loader reuse must preserve Runtime2 occurrence identity, owner-local projection, and checker state.

### Required-Selection Variables

`ExecutorVariableDeclarations` consumes argument, object-field, Query-field, and function-provider declarations from a field executor. It compiles `FromArgument`, `FromObjectField`, `FromQueryField`, and `FromProvider` definitions through the same main-source fragment and path construction used by Runtime2 registries. Object and Query provider identity remains explicit even when both fragments contain identical paths.

Every variable used by either fragment requires exactly one declaration. Missing, unused, overlapping, or duplicate declarations fail registry construction. A function variables provider runs once per active resolver occurrence, must return exactly its declared names, and may supply variables consumed by either fragment. Function providers with their own additional required selections remain outside the supported declarative SPI.

Lossy abstract-type traversal in a provider path is rejected through the production-facing `InvalidVariableException`. Runtime inclusion conditions remain attached to compiled path elements so an excluded path binds null without reading its OER cell.

Production checker dispatchers expose the same normalized variable-definition categories. Runtime2 compiles each named checker input into paired object and Query fragments, preserving dispatcher execution conditions as provider-backed inclusion guards. Legacy opaque variables resolvers that carry their own required selection set cannot be represented by this conversion and fail when the dispatcher definitions are compiled.

### Mutation Execution

`Engine2` installs `QPlanExecutionStrategy` for both query and mutation operations. Mutation operation decoding grounds arguments and conditions, collects fields by response key in source order, and constructs ordered mutation forests through structural namespace edges. Completion reads response-key-qualified mutation cells from `MutationObjectEngineResult`; payload completion uses ordinary OERs and wiring. Every selected mutation executes regardless of earlier failures or nullability. The strategy calls `StartedResolution.await()` for mutation operations before ordinary asynchronous GraphQL completion; null propagation changes response shape without suppressing later effects. Mutation resolvers cannot declare object or Query required selections and use `ctx.query()` for dependent reads. Mutation namespace field and type checkers are excluded; payload checkers retain ordinary semantics.

### Nested Query And Mutation Execution

Resolution passes the concrete `FieldResolverTask` to each registry function as its `ResolutionExecutionContext`. The adapter wraps that explicit capability in an invocation-local `QPlanEngineExecutionContext`; it does not discover the current task through coroutine context. Concurrent tenant work uses structured Kotlin coroutines. Production `StandardViaduct` execution also preserves the thread-local context established by its configured `CoroutineInterop`, allowing `scopedAsync` helpers to inherit the active resolver's context and job. Direct GraphQL execution fixtures use their explicitly supplied Resolution context and do not establish that service bridge.

`EngineExecutionContext.resolveSelectionSet`, `ctx.query()`, and `ctx.mutation()` convert the requested root selection into canonical selections and delegate to the owning field-resolver task. The task creates a fresh root OER under a child dispatcher scope while sharing the logical operation's immutable world, variable bindings, cycle checker, binding declarations, and observer. Nested work is therefore a structured child of the invoking field task rather than another top-level request.

Nested mutation execution uses the same ordered, depth-first namespace traversal as a primary mutation operation. Each call waits for its task children and payload resolution before materializing response-key-qualified cells. Later effects still execute after earlier errors or null values. Explicitly concurrent calls own independent roots and may overlap; each call preserves its own selection order. Nested execution shares request cancellation and is available through handles owned by the active field task.

A nested query is distinct from a declared Query required selection. Nested execution owns an independent Query root and returns response-keyed projected values. A declared Query fragment contributes to the containing orchestration's singular associated Query OER and supplies an owner-local resolver-input projection from that shared scope.

### Root References And Nodes

Source `RootFieldReference` values normalize recursively into `RootFieldReferenceData`, including references inside objects and lists. The adapter does not ask the old engine to resolve them. Resolution gives every reference occurrence and direct-result tail hop a fresh empty Query-rooted invocation identity while retaining publication at the original consumer occurrence. Equivalent descriptors are not semantically deduplicated.

Reference targets must have empty object required selections and no `FromObjectField` variables. Namespace-relative dependencies are expressed as Query required selections with the Query-to-namespace path prefixed. Target Query fragments and `FromQueryField` variables retain their ordinary independently rooted lifecycle.

Node-valued fields retain their source coordinates. A source `NodeReference` becomes a reference to the built-in `Query.node`; its internal identity preserves the concrete type and authoritative original ID. `Query.node` dispatches to the corresponding node executor, while `Query.nodes` returns a list of independent `Query.node` references. Selective node executors receive one-shot node-owned demand. Their output is normalized to demanded top-level fields with registered field-resolver fields removed; an omitted demanded nullable field is represented as null by the feature-test wrapper. The authoritative ID is restored when demanded even if the executor payload omits or contradicts it.

## Supported Alpha Surface

The execution layer supports:

- `StandardViaduct` selection through `ENGINE2_ENABLED`, using its production dispatcher registry and generated tenant-module bootstrap;
- `Engine.execute` and handle-based `Engine.resolveSelectionSet`, with handles confined to their owning `Engine2` instance;
- query operations with selective and non-selective field and node dispatchers, and ordered mutation operations through structural namespaces;
- field- and type-checker dispatchers, including named inputs, supported variable definitions, execution conditions, denial, and errors;
- object- and Query-rooted required selections, aliases, arguments, fragments, transitive demand, and occurrence-local variables;
- `FromArgument`, supported singular `FromObjectField` and `FromQueryField` paths, and no-RSS function providers;
- synchronous scalar, enum, list, object, error, node-reference, and root-field-reference outputs;
- namespace traversal, built-in `Query.node` and `Query.nodes`, and canonical `__typename` lowering;
- scoped public schemas with full-schema resolver inputs;
- nested `ctx.query()` and `ctx.mutation()` execution;
- GraphQL Java 26 `@defer`, conditional defer, nested deferred values, deferred errors, and incremental publisher lifetime ownership;
- generic field-directive context for checker applicability, without built-in policy-specific directive meaning.

The execution layer rejects or does not provide:

- physically batched dispatch; `ENGINE2_BATCHING` is invalid until this support exists;
- checker variables resolvers with their own required selection sets and other legacy checker input graphs that cannot be losslessly converted to Runtime2's variable definitions;
- field-checker enforcement on `@parent` backedges and along `RootFieldReference` target paths;
- mutation namespace field and type checkers, and object or Query required selections on mutation resolvers;
- inline object materialization for Node-valued fields;
- object required selections or `FromObjectField` variables on reference targets;
- function variables providers with their own required selections;
- subscriptions, custom scalars, `@stream`, EOD aliases, asynchronous EOD variants, `EngineExecutionContext.completeSelectionSet`, and direct `Engine.resolveRootFieldReference` calls.

Unsupported input fails explicitly during registry construction, operation decoding, or execution. Runtime2 does not retry an operation on the old engine after Resolution begins.

## Feature-Test Boundary

`EngineTestModule.runQPlanFeatureTest` is a test-only wrapper in `src/test/fixtures` that runs through production `StandardViaduct` wiring with `ENGINE2_ENABLED`. It bootstraps the mock module's field, node, and checker executors through module configs and the production dispatcher registry, and executes against an optionally scoped GraphQL schema. The wrapper alone supplies fixture conveniences such as missing Query defaults, nullable-node completion, and synthetic inline Node IDs.

Copied old-engine tests live under `src/test/kotlin/viaduct/engine/runtime2/execution/viaductfeaturetests`. [Feature tests](feature-tests.md) defines how those tests are preserved and how intentional differences are recorded. Adapter-specific tests cover execution strategy, completion, cancellation, defer, schema scoping, registry construction, selection conversion, and variable declaration compilation.

## Resolver Observation

`QPlanExecutionStrategy` accepts an optional `ResolverObserver` under the `ResolverObserver::class.java` GraphQL-context key; the default is a no-op. The observer follows resolver invocation and declared Query-fragment preparation through nested execution without attaching callbacks to model resolver values. Observation is diagnostics and test evidence, not part of the resolver relation or a source of demand.
