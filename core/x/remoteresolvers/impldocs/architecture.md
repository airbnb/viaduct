# Remote Resolver Architecture

Remote resolvers replace selected Viaduct node and field resolver executors with gRPC proxies. The GraphQL engine, query planning, required-selection-set resolution, access checks, and response completion remain in the main server process. Only the tenant resolver executor call moves to a remote resolver server.

This document describes the current experimental implementation in `core/x/remoteresolvers`: bootstrap, resolver dispatch, wire formats, callback execution, process-local state, failure isolation, lifecycle, and test coverage. The module's [README](../README.md) covers configuration and running the StarWars demo.

## Terminology

- **Main server** or **RRP**: The Viaduct process that owns GraphQL execution. It replaces selected executors with `RemoteNodeProxyExecutor` or `RemoteFieldProxyExecutor` and exposes `EngineCallbackService`.
- **Remote server** or **RRS**: The process that owns `RemoteResolverService`, bootstraps tenant executors independently, and invokes them for incoming RPCs.
- **Executor ID**: A stable string used to identify the same logical resolver in both processes. Node executors use their GraphQL type name; field executors use their field coordinate (`Type.field`).
- **Handle**: A random string referring to an object in a process-local registry. Handles are temporary references, not serialized objects and not cross-process identities.
- **RSS**: A resolver's required selection set. The main engine resolves field resolver object and query RSSes before the proxy is called.
- **Re-entrant execution**: A remote resolver calling `ctx.query()` or `ctx.mutation()`, which must route back to the main engine.

## Process Architecture

```text
Main server (RRP)                              Remote server (RRS)

GraphQL request
      |
      v
DispatcherRegistry
      |
      +-- local executor
      |
      `-- Remote*ProxyExecutor
              |
              | RemoteResolverService
              | BatchResolveNode / BatchResolveField
              +---------------------------------------->
                                                   ExecutorRegistry
                                                        |
                                                        v
                                                tenant executor
                                                        |
                                                        | ctx.query() /
                                                        | ctx.mutation()
                                                        v
              EngineCallbackService              RemoteEngineExecutionContext
              ExecuteQuery / ExecuteMutation             |
              <-------------------------------------------+
                      |
                      v
              EngineExecutionContext.resolveSelectionSet()
```

The two processes do not share an engine, injector, schema object, executor instance, or registry. They must independently load compatible tenant code and schemas. Stable executor IDs connect the two bootstraps; request data crosses the boundary through protobuf messages and JSON payloads.

The implementation has four important ownership rules:

1. The main server owns the GraphQL request and `EngineExecutionContext`.
2. The main engine resolves field resolver RSSes before the remote call.
3. The remote server owns the executor instance that runs tenant code.
4. The main engine owns any re-entrant selection execution and final GraphQL completion.

## Main-Server Bootstrap

### Transport lifecycle

`RemoteResolverConfig.fromEnvironment()` accepts an explicit enablement value and reads the node and
field allowlists, RRS endpoint, and callback port. `RemoteResolverInitializer.initialize()` then:

1. Returns `ProxyResolverFactory.NO_OP` when remote execution is disabled.
2. Creates one shaded-Netty `ManagedChannel` to the configured RRS endpoint.
3. Starts a plaintext gRPC callback server containing `EngineCallbackServiceImpl`.
4. Advertises the callback endpoint as `<resolved-local-address>:<callback-port>`.
5. Builds a `RemoteProxyResolverFactory` that owns proxy-selection predicates but not the channel lifecycle.

Initialization is synchronized, idempotent, and safe for concurrent callers. `close()` is also idempotent, waits up to five seconds for each transport sequentially, forces shutdown when necessary, and makes the initializer terminal. Calling `initialize()` after `close()` fails.

The StarWars main server binds the initializer as a singleton with `preDestroy = "close"` and passes its factory to `ViaductBuilder.withProxyResolverFactory()`.

### Resolver replacement

During engine bootstrap, `DispatcherRegistryFactory` calls `ProxyResolverFactory.proxyNode()` and `proxyField()` for every tenant executor. A non-null result replaces the original executor in the engine's dispatcher registry. The proxy is also the executor that engine validation inspects, so it must preserve the original executor's runtime contract.

`RemoteProxyResolverFactory` does that by delegating:

- Node type, batching mode, selectivity, and metadata.
- Field object RSS, query RSS, batching mode, selectivity, resolver ID, and metadata.

Before returning a proxy, the factory registers the original executor in the main process's executor registry. This registration supports same-JVM tests. In a real two-process deployment, the remote process cannot see it and must register its own compatible executor under the same stable ID.

### Proxy selection

The configuration-backed factory applies these rules:

- An empty node type set proxies all non-selective node resolvers.
- An empty field set proxies all non-selective field resolvers except the built-in `Query.node`, `Query.nodes`, and `@namespaceType` executors.
- A non-empty set narrows proxying to the listed type names or field coordinates.
- Explicitly listing a built-in field opts it in.
- `VIADUCT_REMOTE_RESOLVER_FIELDS=none`, `off`, or `-` disables field proxying while leaving node proxying unchanged.
- Selective node and field resolvers are always left local.

Selective node resolvers cannot use the current response correlation by node ID when the same ID appears with different selections. Selective field resolver semantics likewise depend on the requested sub-selection. Both proxy constructors reject selective executors as a second line of defense.

## Remote-Server Bootstrap

The StarWars `TenantBootstrapper` demonstrates the remote process bootstrap:

1. `SchemaFactory.fromResources()` loads the schema from `.graphqls` resources.
2. `SchemaRegistry` publishes that schema for schema-only remote execution contexts.
3. `ExecutionRegistryConfigSourceCollector.fromResources()` finds tenant manifests under `META-INF/viaduct/modules/<package>.json`.
4. `BootstrapperFactory` and `SharedTenantModuleInjectorFactory` construct tenant module bootstrappers using the remote process's `CodeInjector`.
5. `ViaductBuiltInResolversBootstrapper` adds built-in node and field resolvers that are not present in tenant manifests.
6. Node and field executors are registered by stable ID in `NodeExecutorRegistry` and `FieldExecutorRegistry`.

The schema filters which manifest entries are realized and supports field selection-set reconstruction. The remote process does not create a full `Viaduct` engine just to execute resolver executors.

Executor registration is later-wins. Re-registering the same instance is treated as idempotent; replacing a different instance under an existing ID logs a warning. The main and remote processes must agree on the logical resolver behind every proxied ID.

## Node Resolver Flow

`RemoteNodeProxyExecutor.resolve()` moves one engine-produced node batch across the network:

1. Register the main server's `EngineExecutionContext` in `ContextRegistry`.
2. Register each selector's `EngineSelectionSet` in `SelectionsRegistry`.
3. Build `BatchResolveNodeRequest` with the executor ID, node IDs, selection handles, context handle, and callback endpoint.
4. Call `RemoteResolverService.BatchResolveNode`.
5. Unregister the context and all selection handles in `finally`.
6. Correlate each `ResolvedNode` to the original selector by node ID.
7. Deserialize successful `EngineObjectData` and convert remote errors to `RemoteResolverException`.

On the remote server, `RemoteResolverServiceImpl.batchResolveNode()`:

1. Looks up the local node executor by stable type-name ID.
2. Builds a `RemoteEngineExecutionContext`.
3. Attempts to resolve each selection handle locally.
4. Uses `EmptyEngineSelectionSet(typeName)` on a registry miss.
5. Calls the remote process's node executor once with the reconstructed batch.
6. Serializes each successful node result independently.

Selection handles are process-local, and the node RPC does not carry a serialized selection set. Consequently, a separate RRS normally executes a non-selective node resolver with an empty selection set. The main engine still projects and completes the requested fields after the node returns. A future implementation that needs remote-side projection or selection-aware node execution must add a node selection-set wire representation.

The response uses node ID as its correlation key. This is why selective node resolvers, which may contain duplicate IDs with different selections, are unsupported.

## Field Resolver Flow

Field resolvers have a richer wire contract because their input and output types are open-ended.

### Main-side preparation

`RemoteFieldProxyExecutor` exposes the original executor's object and query RSSes. The main engine therefore resolves those RSSes before calling `batchResolve()`. For each field selector, the proxy serializes:

- The field arguments as JSON.
- The resolved object RSS value as `EngineObjectData` JSON.
- The resolved query RSS value as `EngineObjectData` JSON.
- The field's sub-selection set as both a process-local handle and a reconstructable wire form.

Field selectors have no natural unique ID and distinct selectors may compare equal. The proxy assigns the input index as `selector_key` and keeps the `(index, original selector)` pairing for response reconstruction.

Selection-set conversion is memoized by object identity within a batch. Viaduct commonly shares one `EngineSelectionSet` instance across all selectors for a field coordinate, so this avoids repeatedly registering and rendering the same fragment.

If argument, RSS value, or selection-set serialization fails, only that selector receives a failure. The remaining selectors are still sent. If every selector fails preparation, the proxy returns those failures without making an RPC.

### Remote-side reconstruction

`RemoteResolverServiceImpl.batchResolveField()`:

1. Looks up the local field executor by `Type.field`.
2. Builds a `RemoteEngineExecutionContext`.
3. Resolves the parent object type from the executor ID and the query type from the remote schema.
4. Deserializes object and query RSS values against those real schema types.
5. Deserializes arguments.
6. Uses a locally resolvable selection handle when available; otherwise reconstructs the serialized selection set against the remote schema.
7. Calls the remote field executor with all selectors that survived reconstruction.
8. Serializes each returned field value independently.

A serialized field selection set contains:

- The GraphQL type condition.
- A fragment document generated by `EngineSelectionSet.toFragment()`.
- Variables encoded as a JSON object.

A blank fragment document represents a non-null empty selection set, such as a composite field whose children were all skipped by directives. A missing `SerializedSelectionSet` represents a leaf field with no sub-selections.

Malformed arguments, RSS data, or selection documents fail only their selector. If no selector survives reconstruction, the service returns the accumulated errors without invoking the executor. This avoids calling an unbatched executor with an invalid empty batch.

### Result reconstruction

The proxy indexes the response by `selector_key`, requires one response for every sent selector, and deserializes each success against the main context's live schema. Pre-RPC failures and remote results are then combined into the map expected by `FieldResolverExecutor`.

The main engine performs normal field completion after the proxy returns. Remote execution does not move access checks, child query-plan execution, or GraphQL response completion into the RRS.

## Wire Formats

The protobuf schema defines two bidirectional services:

| Service | Direction | RPCs |
| --- | --- | --- |
| `RemoteResolverService` | Main to remote | `BatchResolveNode`, `BatchResolveField` |
| `EngineCallbackService` | Remote to main | `ExecuteQuery`, `ExecuteMutation` |

The protobuf messages use `bytes` fields for JSON payloads. Protobuf defines RPC structure and correlation; Jackson encodes resolver values.

### EngineObjectData

`EngineObjectDataSerializer` walks `fetchSelections()` and writes a JSON object keyed by selection name. Nested `EngineObjectData` and lists recurse.

The caller supplies the top-level `GraphQLObjectType` during deserialization. Nested JSON objects are rebuilt as `ResolvedEngineObjectData` under a placeholder `RemoteNestedObject` type because nested type identity is not encoded. Structural access through `fetchOrNull()` works, but nested type inspection does not.

A nested `NodeReference` is rejected instead of recursively awaiting an unresolved node. Callers convert that serialization error to a selector-level failure.

### Field values

Field resolver results use a tagged JSON envelope because `Any?` does not provide an out-of-band GraphQL type:

| Kind | Tag | Payload |
| --- | --- | --- |
| Null | `n` | None |
| Scalar | `s` | JSON scalar or JSON-friendly map |
| Node reference | `r` | Concrete type name and ID |
| Resolved object | `o` | Concrete type name and `EngineObjectData` field map |
| List | `l` | Recursively tagged elements |

`NodeReference` is checked before `EngineObjectData` because the engine's node reference implementation satisfies both interfaces. On the main side, node references are rebuilt through `EngineExecutionContext.createNodeReference()`, and objects are rebuilt against concrete object types from the live full schema.

Supported scalar leaves are strings, booleans, and numbers. Maps may contain only JSON-friendly values. Arbitrary objects, sets, sequences, custom scalar values requiring bespoke coercion, and maps containing `NodeReference` or `EngineObjectData` are rejected.

The field-value format has no protocol version and deliberately reused an existing protobuf field number when the tagged format replaced the earlier envelope. Main and remote servers must be built from compatible commits and deployed together.

## Re-entrant Query and Mutation Flow

`RemoteEngineExecutionContext` implements `resolveSelectionSet()` by calling back to the main process:

1. Register the requested `EngineSelectionSet` in `SelectionsRegistry`.
2. Send both handles to `ExecuteMutation` or `ExecuteQuery`, chosen from the operation type.
3. The main callback service looks up both handles.
4. Call the original main `EngineExecutionContext.resolveSelectionSet()`.
5. Serialize the resulting `EngineObjectData`.
6. Deserialize it in the remote process under a placeholder top-level type.
7. Unregister the remote selection handle in `finally`.

The context handle works across the callback because it was created and remains registered in the main process; the callback request merely returns that string to its owner.

The selection handle does **not** currently work across a real process boundary. It is created in the remote process, while `EngineCallbackServiceImpl` looks it up in the main process's independent `SelectionsRegistry`. Current callback integration tests use in-process gRPC and one JVM, so both services see the same singleton registry. In a separate RRS process, `ctx.query()` and `ctx.mutation()` fail with a selection-handle `NOT_FOUND` until the callback request carries a serialized selection set or another cross-process representation.

Other context behavior in a separate process is intentionally partial:

- `fullSchema`, `scopedSchema`, and `activeSchema` use `SchemaRegistry`.
- `engineSelectionSetFactory` is built from the local schema.
- `globalIDCodec` falls back to `GlobalIDCodecDefault`.
- `createNodeReference()` creates a lightweight remote reference for result serialization.
- `requestContext` and `executionHandle` are `null` without a delegate.
- `engine`, `fieldScope`, `createRootFieldReference()`, and `completeSelectionSet()` require an in-process delegate and otherwise throw.
- `hasModernNodeResolver()` returns `false` without a delegate.

Resolvers that depend on those unsupported context members are not network-compatible today.

## Registries and State Lifetime

All registries are in-memory JVM singletons:

| Registry | Key | Value | Typical owner and lifetime |
| --- | --- | --- | --- |
| `ContextRegistry` | Random UUID | `EngineExecutionContext` | Main server, one outbound RPC; removed in proxy `finally` |
| `SelectionsRegistry` | Random UUID | `EngineSelectionSet` | One outbound RPC or callback; removed in `finally` |
| `NodeExecutorRegistry` | GraphQL type name | `NodeResolverExecutor` | Both processes, bootstrap to shutdown |
| `FieldExecutorRegistry` | `Type.field` | `FieldResolverExecutor` | Both processes, bootstrap to shutdown |
| `SchemaRegistry` | Singleton slot | `ViaductSchema` | Remote process, bootstrap to shutdown |

`ContextRegistry`, `SelectionsRegistry`, and executor registries use `ConcurrentHashMap`. Every context registration returns a fresh UUID, even when concurrent RPCs use the same context, so one request's cleanup cannot remove another's entry.

Do not treat registry handles as globally resolvable. A handle lookup succeeds in integration tests because both gRPC services run in one JVM; that is not evidence that the same lookup works between deployed processes.

## Errors and Batch Isolation

The transport preserves success versus failure per selector but does not preserve the original exception object:

- `ErrorInfo` carries only the exception message and fully qualified class name.
- The main proxy reconstructs it as `RemoteResolverException`.
- Causes, stack traces, structured GraphQL errors, and framework-versus-tenant exception markers do not cross the wire.

Failure boundaries are:

- Unknown executor IDs are gRPC `NOT_FOUND` errors for the whole RPC.
- Main-side field selector serialization failures affect only that selector.
- Remote-side field selector reconstruction failures affect only that selector.
- A resolver method throwing outside its returned `Result` fails every surviving selector in that invocation.
- A failure returned for one selector affects only that selector.
- Node and field result serialization failures affect only the corresponding selector.
- Field result deserialization failures on the main side affect only that selector.
- Missing or unknown response correlation keys are treated as transport invariant violations.
- `CancellationException` is always rethrown rather than converted into a resolver result.
- gRPC transport failures and deadlines propagate through the coroutine stub.

`RemoteProxyResolverFactory` supports an optional per-request deadline. `RemoteResolverInitializer` currently does not configure one, so its default network calls rely on gRPC's unbounded default.

## Channels, Security, and Shutdown

The main initializer owns one RRS channel and one callback server. Each `RemoteResolverServiceImpl` caches one callback channel per advertised endpoint in a `ConcurrentHashMap`; creating a channel per request would create a Netty thread pool per request. The RRS host must call `shutdownChannels()` during shutdown.

Both directions currently use plaintext gRPC. There is no TLS, authentication, authorization, or request metadata propagation in this experimental transport. The callback endpoint advertised by the main process must be routable from the remote process.

The StarWars `RemoteServer` demonstrates one-shot lifecycle management:

- It starts `RemoteResolverServiceImpl` and gRPC reflection on the configured port.
- It drains callback channels before shutting down the server.
- It waits 30 seconds for graceful shutdown and then five seconds after forced shutdown.
- A JVM shutdown hook calls `stop()`.

## Build and Packaging

The remote resolver library is a separate included Gradle build under `core/x/remoteresolvers/lib`. It is source-substituted into the OSS and core composites but is not a participating publication and is not published to Maven Central.

`core/x/remoteresolvers` is a second, self-contained composite for the StarWars main and remote demo servers. It includes Viaduct core, publications, Gradle plugins, the remote resolver library, and the StarWars tenant modules from source.

The demo illustrates both sides of the integration:

- `starwars/main-server` embeds Viaduct, installs the proxy factory, and owns the callback server.
- `starwars/remote-server` loads schema and tenant manifests, supplies a Guice-backed `CodeInjector`, registers executors, and owns the RRS server.

## Testing

The test suite covers several different layers:

- `RemoteProxyIntegrationTest`: Node proxying, batching, error propagation, node serialization isolation, and callback initiation.
- `RemoteFieldProxyIntegrationTest`: Field inputs and results, RSS data, selection reconstruction, batching, per-selector isolation, and callback initiation.
- `RemoteSelectionSetWireTest`: Fragment and variable round-tripping for field sub-selections.
- `FieldValueSerializerTest` and `EmptyEngineSelectionSetTest`: Wire codec and empty-selection behavior.
- Registry tests: Stable executor registration and schema publication.
- Configuration and initializer tests: Parsing, selection policy, lifecycle, and built-in exclusions.
- StarWars remote-server tests: Tenant bootstrap and server lifecycle.

Most end-to-end library tests use gRPC's in-process transport and run both services in one JVM. They validate RPC wiring and serialization but can accidentally resolve process-local handles. Tests that explicitly pass unregistered context or selection handles exercise selected network fallbacks, especially field selection-set reconstruction, but they are not a full two-process callback test.

For a behavior that depends on process separation, add a test that prevents shared registry access or launches separate JVMs. In particular, a successful in-process callback test does not establish that re-entrant execution works over the deployed network topology.

## Key Files

- `lib/src/main/proto/remote_resolver.proto`: RPC and message definitions.
- `lib/src/main/kotlin/viaduct/remote/config/RemoteResolverConfig.kt`: Main-side configuration parsing.
- `lib/src/main/kotlin/viaduct/remote/config/RemoteResolverInitializer.kt`: Main-side transport lifecycle and proxy policy.
- `lib/src/main/kotlin/viaduct/remote/RemoteProxyResolverFactory.kt`: Bootstrap-time executor replacement.
- `lib/src/main/kotlin/viaduct/remote/RemoteNodeProxyExecutor.kt`: Main-side node RPC client.
- `lib/src/main/kotlin/viaduct/remote/RemoteFieldProxyExecutor.kt`: Main-side field RPC client and selector serialization.
- `lib/src/main/kotlin/viaduct/remote/RemoteResolverServiceImpl.kt`: Remote-side dispatch and result serialization.
- `lib/src/main/kotlin/viaduct/remote/RemoteEngineExecutionContext.kt`: Schema-only context fallbacks and callback client.
- `lib/src/main/kotlin/viaduct/remote/EngineCallbackServiceImpl.kt`: Main-side re-entrant execution service.
- `lib/src/main/kotlin/viaduct/remote/EngineObjectDataSerializer.kt`: Structural object-data JSON codec.
- `lib/src/main/kotlin/viaduct/remote/FieldValueSerializer.kt`: Tagged field-value JSON codec.
- `lib/src/main/kotlin/viaduct/remote/registry/`: Process-local state and stable executor registries.
- `starwars/remote-server/src/main/kotlin/com/example/remote/TenantBootstrapper.kt`: Reference remote tenant bootstrap.

## Related Documentation

- [Remote resolver README](../README.md): Configuration, demo commands, and user-facing limitations.
- [Selection execution via ExecutionHandle](../../../../impldocs/subquery-execution.md): Main-engine behavior behind `ctx.query()` and `ctx.mutation()`.
- [Context flow in Viaduct Engine](../../../engine/runtime/impldocs/context-flow.md): `EngineExecutionContext` and execution-handle lifecycle.
- [Execution registry KSP pipeline](../../../../impldocs/execution-registry-ksp-pipeline.md): How tenant execution manifests are generated.
