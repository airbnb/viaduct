# Engine API Integration

## Role And Boundary

Runtime2's main-source execution layer adapts validated GraphQL Java query and mutation execution and Engine API field, node, and checker dispatchers to production Resolution. It decodes source operations into the canonical model, constructs the request-local operation state, starts Resolution, and exposes the promise-backed result graph to GraphQL Java for ordinary and incremental completion.

The `QPlan` prefix is inherited from the upstream `qplan` development branch and build layout; see the [upstream source context](../evidence/profiles/README.md#upstream-source-context). The names still identify current Runtime2 code: [`QPlanExecutionStrategy`](../../src/main/kotlin/viaduct/engine/runtime2/execution/QPlanExecutionStrategy.kt), [`QPlanWiringFactory`](../../src/main/kotlin/viaduct/engine/runtime2/execution/QPlanWiringFactory.kt), [`QPlanInstrumentation`](../../src/main/kotlin/viaduct/engine/runtime2/execution/QPlanInstrumentation.kt), and [`runQPlanFeatureTest`](../../src/test/fixtures/viaduct/engine/runtime2/execution/testing/QPlanFeatureTest.kt).

`Engine2` implements `Engine.execute` and handle-based `Engine.resolveSelectionSet` and is selected by `StandardViaduct` when `ENGINE2_ENABLED` is enabled. The existing engine remains the default. Direct `Engine.resolveRootFieldReference` calls are intentionally unsupported: Runtime2 instead interprets `RootFieldReference` values returned by resolvers inside Resolution. Production integration reuses the service-built `DispatcherRegistry`; field, node, and checker invocation remains dispatcher-backed and receives a request-owned `EngineExecutionContext`. The direct executor adapter remains for focused integration fixtures; copied feature tests use production service wiring.

Interoperability between engine selections across `StandardViaduct.Builder.buildWithReusedSchemas` rebuilds is not part of the Runtime2 integration contract. A deployment must not rely on rebuilding a `StandardViaduct` using the [`runtime`](../../../runtime) engine as an `Engine2` instance, or the reverse, merely because the new instance reuses the previous instance's schema objects.

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

The production execution strategies explicitly use eager cycle checking and have no built-in request deadline. Caller cancellation and incremental publisher termination release the independently owned request job. Long-running query, mutation, and incremental work can complete after any service-defined deadline unless the caller cancels it. The separately bounded blocking Resolution entry point retains its 15-second deadline and deferred cycle diagnosis; that bound does not configure Engine API execution.

`QPlanWiringFactory` performs GraphQL completion rather than tenant resolution. Checked completion reads the containing field-checker result, follows raw list and object values, reads each reached OER's type-checker result, and combines applicable results at the consumer boundary. Completed values project immediately; pending value or checker promises use request-owned completion-stage bridges. List elements wait concurrently, and a terminal non-cancellation failure takes precedence over sibling cancellations.

`QPlanInstrumentation` is required because GraphQL Java creates the incremental publisher after the query strategy returns. The instrumentation keeps the Resolution request alive until the publisher completes, fails, or is cancelled. Cancelling either `StandardViaduct.executeAsync`'s public future or a coroutine running suspending `StandardViaduct.execute` cancels GraphQL completion, nested resolution, and the owning Resolution request job without cancelling concurrent requests.

## Executor Adaptation

The production adapter discovers field, node, field-checker, and type-checker dispatchers by schema coordinate and converts their declarations into the canonical registry. Registration and field lookup require definitions from the same canonical lowered schema, including checker targets; the adapter supplies those definitions, and direct registry callers hold the same precondition. Field and node calls remain dispatcher-backed; selective dispatchers receive Resolution's closed successor demand converted to an `EngineSelectionSet`, while simple outputs receive no selection set. `EngineConfiguration.fieldSelectivityProvider` supplies selectivity when dispatcher metadata does not.

Runtime2 requires executor results to conform to `ResolverOutputData`: ordinary objects recursively use any compliant `EngineObjectData.Sync` implementation, scalar values already have the supported engine representations, and references are canonical descriptors or Runtime2-created compatibility handles carrying one. Conformance to the consuming output type is the executor's output obligation and a precondition for Resolution; EOD construction, passive resolution, and reference following rely on it without repeating invariant checks. The [construction contract](../architecture/model.md#construction) records these obligations, including abstract reference compatibility. Successful executor results pass directly to Resolution without recursive normalization, scalar coercion, or conversion into new EODs. Legacy mock maps, noncompliant EOD values, and scalar representations are adapted explicitly by feature-test wrappers. Passive resolution obtains canonical types from the active schema and reads stored values without rebuilding the source object. Demand projection still constructs selected output where required, preserving absence, present nulls, and error identity.

The existing service bootstrap constructs the production dispatcher registry and preserves each executor's batching capability. Runtime2 creates a fresh production data loader for each dispatcher invocation and supplies `Runtime2DispatchStrategy`, which currently chooses immediate dispatch for every field and node resolver. Each invocation calls the resolver with one item in the calling coroutine, without collecting work or scheduling a new dispatch coroutine. Neither completed results nor in-flight work are shared across semantic occurrences. The shared loaders default to `RuntimeDispatchStrategy`, which collects calls only when the resolver supports batching. Enabling `ENGINE2_BATCHING` remains rejected until multi-item batching is implemented. This is a transitional physical-dispatch policy rather than a permanent semantic prohibition on reuse. Future physical batching and loader reuse must preserve Runtime2 occurrence identity, owner-local projection, and checker state; singleton dispatch does not establish those guarantees.

### EOD Contract And Migration

[The FER EOD contract](../architecture/model.md#fer-contract-for-engineobjectdata) defines immutable stores of materialized values, synchronous reads, absence, public erroneous-field exceptions, raw error-marker reads, and composition through behavioral subtypes. This is the intended migration contract; the shared Engine API still exposes asynchronous getters, and Runtime2's `outputValue` is currently a helper rather than a shared interface operation. Public Runtime2 reads still use `EngineErrorDataReadException` pending the transition to `ErroneousFieldException` and a settled causal-information contract.

Runtime2 accepts any Sync EOD implementation as resolver output when it exposes materialized, recursively compliant values. Tenant resolver EODs must contain no field-error markers, either `EngineErrorData` or exceptions, including within lists and nested objects; getters must not throw to model field errors. Runtime2's `outputValue` helper uses a direct raw read for Runtime2-owned objects and `get` for all other Sync objects, without catching or translating getter exceptions. The external `get` path needs no erroneous-field handling because those values are error-free. Tenant `toBuilder()` overlays are supported when their delegates obey this output contract; present nulls mask the base. Runtime2-owned raw reads remain useful for internal error-bearing data and algorithmic validation, and preserve a path to future fine-grained tenant error reporting.

Configured resolver instrumentation currently uses `InstrumentedEngineObjectData.Sync` around object and Query inputs and nested-selection results. That compatibility behavior extends EOD reads and needs an observability replacement outside EODs. It is separate from `QPlanInstrumentation`'s GraphQL request-lifetime management and from Resolution's explicit observer events. The synchronous value contract does not permit hidden instrumentation callbacks as a permanent EOD feature.

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

Runtime2's Engine API contexts create `NodeReference` and `RootFieldReference` compatibility handles with a canonical `RootFieldReferenceData` descriptor immediately. Construction validates the canonical target and coerces its arguments; node descriptors retain the concrete type and authoritative original ID. These handles preserve the Engine API object shape for tenant and mock constructors without using the `runtime` engine's lazy reference implementations. Resolver invocation and passive resolution unwrap existing descriptors as they encounter handles, including references inside objects and lists. Handles from other implementations are outside the resolver-output contract. Resolution gives every reference occurrence and direct-result tail hop a fresh empty Query-rooted invocation identity while retaining publication at the original consumer occurrence. Equivalent descriptors are not semantically deduplicated.

The handles are a migration accommodation, not FER EODs: they implement the asynchronous Engine API object surface but are not `EngineObjectData.Sync`, and their getters do not execute resolution. After migration, Tenant APIs will return `RootFieldReferenceData` directly and retire `NodeReference` and `RootFieldReference`; the descriptor itself is not an EOD subtype and never appears in materialized resolver inputs.

Reference targets must have empty object required selections and no `FromObjectField` variables. Namespace-relative dependencies are expressed as Query required selections with the Query-to-namespace path prefixed. Target Query fragments and `FromQueryField` variables retain their ordinary independently rooted lifecycle.

Node-valued fields retain their source coordinates. A Runtime2-created `NodeReference` carries a reference to the built-in `Query.node`; its internal identity preserves the concrete type and authoritative original ID. `Query.node` dispatches to the corresponding node executor, while `Query.nodes` returns a list of independent `Query.node` references. Selective node executors receive one-shot node-owned demand. Their output is projected to demanded top-level fields with registered field-resolver fields removed; an omitted demanded nullable field is represented as null by the feature-test wrapper. The authoritative ID is restored when demanded even if the executor payload omits or contradicts it.

## Supported Alpha Surface

The execution layer supports:

- `StandardViaduct` selection through `ENGINE2_ENABLED`, using its production dispatcher registry and generated tenant-module bootstrap;
- `Engine.execute` and handle-based `Engine.resolveSelectionSet`, with handles confined to their owning `Engine2` instance;
- query operations with selective and non-selective field and node dispatchers, and ordered mutation operations through structural namespaces;
- field- and type-checker dispatchers, including named inputs, supported variable definitions, execution conditions, denial, and errors;
- object- and Query-rooted required selections, aliases, arguments, fragments, transitive demand, and occurrence-local variables;
- `FromArgument`, supported singular `FromObjectField` and `FromQueryField` paths, and no-RSS function providers;
- synchronous scalar, enum, list, object, error, node-reference, and root-field-reference outputs, including `Byte`, `Short`, `Long`, `BigInteger`, `BigDecimal`, `Date`, `DateTime`, `JSON`, and opaque internal `BackingData`;
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
- subscriptions, application-defined custom scalars, `@stream`, EOD aliases, asynchronous EOD variants, `EngineExecutionContext.completeSelectionSet`, and direct `Engine.resolveRootFieldReference` calls.

Unsupported input fails explicitly during registry construction, operation decoding, or execution. Runtime2 does not retry an operation on the `runtime` engine after Resolution begins.

## Feature-Test Boundary

`EngineTestModule.runQPlanFeatureTest` is a test-only wrapper in `src/test/fixtures` that runs through production `StandardViaduct` wiring with `ENGINE2_ENABLED`. It bootstraps the mock module's field, node, and checker executors through module configs and the production dispatcher registry, and executes against an optionally scoped GraphQL schema. The wrapper converts legacy mock concrete-object maps, synchronous EOD implementations, and scalar representations into `ResolvedEngineObjectData` and compliant scalar values before returning executor results; Runtime2-owned EODs pass through unchanged. It also supplies fixture conveniences such as missing Query defaults, nullable-node completion, and synthetic inline Node IDs.

Copied `runtime` engine tests live under `src/test/kotlin/viaduct/engine/runtime2/execution/viaductfeaturetests`. [Feature tests](feature-tests.md) defines how those tests are preserved and how intentional differences are recorded. Adapter-specific tests cover execution strategy, completion, cancellation, defer, schema scoping, registry construction, selection conversion, and variable declaration compilation.

## Resolver Observation

`QPlanExecutionStrategy` accepts an optional `ResolverObserver` under the `ResolverObserver::class.java` GraphQL-context key; the default is a no-op. The observer follows resolver invocation and declared Query-fragment preparation through nested execution without attaching callbacks to model resolver values. Observation is diagnostics and test evidence, not part of the resolver relation or a source of demand.
