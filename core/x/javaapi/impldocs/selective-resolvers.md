# Java selective resolvers

Selective resolution is temporarily disabled in the Java Tenant API. Kotlin selective resolution
remains supported. Java retains scaffolding for future implementation, but its public build and
bootstrap paths reject activation.

## Enforcement boundaries

- `GraphQLSchemaParser` rejects `@resolver(isSelective: true)` and the legacy
  `@resolver(selective: true)` on fields and nodes, with or without batching. The error names the
  field coordinate or node type.
- `JavaResolverParamsExtractor` reports a compilation error for resolver bases annotated with
  `@ResolverFor(isSelective = true)` or `@NodeResolverFor(isSelective = true)`, including bases
  inherited through intermediate classes. It emits no descriptor for the rejected resolver.
- `ViaductJavaExecutorFactory` throws `TenantModuleException` for selective field and node entries
  before loading resolver classes or constructing executors. This protects against stale or
  externally produced registries that bypass the build-time gates.

The APT and bootstrap errors include the field coordinate or node type. Shared registry models
still carry `isSelective` because Kotlin supports it; the restriction belongs to the Java producer
and consumer, not to the shared wire model.

## Retained scaffolding

Paths below are relative to `core/x/javaapi`.

| Layer | Retained structures and current behavior |
| --- | --- |
| API annotations and contexts | `api/src/main/java/viaduct/java/api/annotations/{ResolverFor,NodeResolverFor}.java` retain `isSelective`. `api/src/main/java/viaduct/java/api/context/SelectiveFieldExecutionContext.java` exposes `Object getSelections()`; `api/src/main/java/viaduct/java/api/context/SelectiveNodeExecutionContext.java` exposes `Object selections()`. These are placeholder selection types. |
| Resolver generation | `codegen/src/main/java/viaduct/x/javaapi/codegen/{ResolverModel,NodeResolverModel}.java` retain selectivity. The templates in `JavaResolverGenerator` and `JavaNodeResolverGenerator` still emit selective annotations and context wrappers for models built directly. Positive generator tests preserve this inactive path. |
| Java APT metadata | `registry-apt/src/main/kotlin/viaduct/java/registry/apt/JavaResolverParamsExtractor.kt` reads annotations and produces the shared `ResolverParams` descriptors. Its validation gate prevents selective descriptors from reaching `JavaRegistryExtractorProcessor` and the shared module-config assembler. |
| Executor construction | `runtime/src/main/kotlin/viaduct/java/runtime/bootstrap/ViaductJavaExecutorFactory.kt` retains `isSelective` propagation to `JavaFieldResolverExecutorImpl`, `FieldBatchResolverExecutorImpl`, `JavaNodeResolverExecutorImpl`, and `NodeBatchResolverExecutorImpl`. Its gate currently permits only `false`. |
| Execution contexts | `runtime/src/main/kotlin/viaduct/java/runtime/bridge/{SimpleFieldExecutionContext,SimpleNodeExecutionContext}.kt` implement the selective interfaces but do not retain the engine selector's selections. `getSelections()` and `selections()` throw `FrameworkException`. |

## Reenable checklist

Complete these steps in order before exposing selective declarations to Java tenants:

1. Define the Java `SelectionSet` API and replace the placeholder `Object` return types in the
   selective interfaces and generated context wrappers.
2. Carry engine selector selections through all four executor variants into
   `SimpleFieldExecutionContext` and `SimpleNodeExecutionContext`.
3. Implement `getSelections()` and `selections()` with the required selection semantics instead of
   throwing `FrameworkException`.
4. Add contract tests for field and node resolvers, batched and unbatched execution, repeated
   selective invocation, result merging, and error attribution. Exercise the downstream path with
   test scaffolding while the public schema gate remains in place.
5. Remove the Java APT and bootstrap gates and replace their rejection tests with activation tests.
6. Remove the schema-parser rejection **last**, once every downstream layer is ready. Update the
   codegen and user documentation to advertise support and exercise public codegen end to end.
