---
title: Viaduct API
description: Viaduct Runtime Execution API
---


This document describes how to use the **runtime entry point** `viaduct.service.api.Viaduct` interface to execute GraphQL operations, select schema variants, and interpret execution results.


## What `Viaduct` Is

A `Viaduct` instance is the **main entry point** for executing GraphQL operations against the Viaduct runtime. It is intended to be created **once at service startup** and reused for all requests.

You typically create an instance via:

- `BasicViaductFactory` — simplest way to build a working engine with defaults.
- `ViaductBuilder` — full control over SPI hooks (metrics, error reporting, flags, schema configuration, etc.).

## Creating A `Viaduct` Instance

### Option A: `BasicViaductFactory`

This is the simplest way to build an instance of Viaduct.

===! "Kotlin"

    ```kotlin
    import viaduct.service.BasicViaductFactory
    import viaduct.service.api.Viaduct

    val viaduct: Viaduct = BasicViaductFactory.create()
    ```

=== "Java"

    ```java
    Viaduct viaduct = BasicViaductFactory.create();
    ```

**When to use**

- Your service is “off-the-shelf” or you’re getting started.
- You don’t need custom metrics, flags, custom exception handlers, custom error shaping, etc.

### Option B: `ViaductBuilder`

ViaductBuilder provides full control of all the internal configurations of the Viaduct. Configure a tenant injector factory explicitly to load tenant modules; a bare builder has no tenant config sources. The Java example uses the naive factory for resolvers with no-argument constructors. For injected dependencies, supply your [DI factory](../../service_engineers/dependency_injection/index.md).

===! "Kotlin"

    ```kotlin
    import viaduct.service.SchemaScopeInfo
    import viaduct.service.ViaductBuilder

    val viaduct =
      ViaductBuilder()
        .withTenantModuleInjectorFactory(tenantModuleInjectorFactory)
        // Requires src/main/viaduct/scopes.yaml (see Scope Schemas). Registers Base plus every declared scoped schema.
        .withScopedSchemas(listOf(SchemaScopeInfo.Base) + SchemaScopeInfo.fromResources())
        // Optional platform hooks:
        // .withMeterRegistry(meterRegistry)
        // .withFlagManager(flagManager)
        // .withResolverErrorReporter(errorReporter)
        // .withDataFetcherErrorBuilder(resolverErrorBuilder)
        // .withGlobalIDCodec(globalIdCodec)
        .build()
    ```

=== "Java"

    ```java
    List<SchemaScopeInfo> schemas = new ArrayList<>(SchemaScopeInfo.fromResources());
    schemas.add(SchemaScopeInfo.Base.INSTANCE);

    Viaduct viaduct = new ViaductBuilder()
        .withTenantModuleInjectorFactory(NaiveTenantModuleInjectorFactory.INSTANCE)
        .withScopedSchemas(schemas)
        .build();
    ```

**When to use**

- You need to control observability, error reporting/shaping, feature flags, schema configuration, or global-id behavior.
- You want a single consistent “production-like” configuration used by both prod and test harnesses.

### Registering executable schema views

If you do not explicitly configure schema views, Viaduct's convenience construction paths register Base by default. This preserves the normal single-schema setup for both unscoped and scope-aware schemas.

When you call `withScopedSchemas(...)`, however, the supplied list is authoritative. Viaduct registers exactly those views:

- `SchemaScopeInfo.Base` registers `SchemaId.Base`. It contains every non-tenant-local field without filtering to specific scopes.
- `SchemaScopeInfo.Scoped` registers its named `SchemaId.Scoped`. It contains fields visible to at least one configured scope and excludes tenant-local fields.

Base is optional under explicit configuration. For example, an application can expose only a public projection:

===! "Kotlin"

    ```kotlin
    val viaduct =
      ViaductBuilder()
        .withTenantModuleInjectorFactory(tenantModuleInjectorFactory)
        .withScopedSchemas(
          listOf(
            SchemaScopeInfo.Scoped(
              id = "public",
              scopesToApply = setOf("public"),
            )
          )
        )
        .build()
    ```

=== "Java"

    ```java
    SchemaScopeInfo publicView = new SchemaScopeInfo.Scoped("public", Set.of("public"));
    Viaduct viaduct = new ViaductBuilder()
        .withTenantModuleInjectorFactory(NaiveTenantModuleInjectorFactory.INSTANCE)
        .withScopedSchemas(List.of(publicView))
        .build();

    SchemaId publicSchemaId = publicView.getSchemaId();
    ```

This application must execute requests using the corresponding `SchemaId.Scoped`; `SchemaId.Base` is not registered. The internal Full schema still exists for planning and execution support, but it is not a client execution target.

`SchemaScopeInfo` selects executable views; it does not declare which scope IDs are valid. For a schema loaded from resources, Viaduct reads those from the scope definitions the build packaged, and infers them from the schema when no definitions are present. A Scoped view requires one or the other. See [Scope Schemas](../../service_engineers/scope_schemas/index.md) for declaring scope definitions in `scopes.yaml` and reading them with `SchemaScopeInfo.fromResources`.

## Choosing The Schema: `SchemaId`

Each execution targets a **schema variant** identified by a `SchemaId`:

- `SchemaId.Base` — base schema and the default execution target, when Base is registered.
- `SchemaId.Scoped(id, scopeIds)` — a schema variant derived from the full schema by applying *scope IDs*.
- `SchemaId.None` — sentinel “non-existent schema” (typically not used for normal execution).

===! "Kotlin"

    ```kotlin
    import viaduct.service.api.SchemaId

    val base = SchemaId.Base

    val internalOnly = SchemaId.Scoped(
      id = "INTERNAL",
      scopeIds = setOf("internal", "admin")
    )
    ```

=== "Java"

    ```java
    SchemaId base = SchemaId.Base.INSTANCE;
    SchemaId internalOnly =
        new SchemaScopeInfo.Scoped("INTERNAL", Set.of("internal", "admin")).getSchemaId();
    ```


### How Scoped Schemas Relate To Access Control

`SchemaScopeInfo.Scoped(...).getSchemaId()` is the Java way to obtain the ID you registered with `withScopedSchemas()`. Use the same ID for execution; `SchemaId.Base.INSTANCE` addresses the base view only when it is registered.

A scoped schema is a structural way to **hide types/fields** from a client by removing them from the executable schema. This is complementary to runtime checks inside resolvers.

To implement or audit access-control, you can ask `Viaduct` what scopes were applied for a schema.

## Building An `ExecutionInput`

`ExecutionInput` is a container for:

- `operationText` — GraphQL document text.
- `operationName` — nullable, required only when the document contains multiple operations.
- `variables` — variable map used by the operation.
- `operationId` — stable identifier for instrumentation (auto-generated by default).
- `executionId` — unique request identifier (auto-generated by default).
- `requestContext` — arbitrary, deployment-specific context object exposed to execution contexts.

### Recommended Construction

Prefer the builder for clarity and explicitness:

===! "Kotlin"

    ```kotlin
    import viaduct.service.api.ExecutionInput

    val input =
      ExecutionInput.builder()
        .operationText(
          """
          query User(${"$"}id: ID!) {
            user(id: ${"$"}id) { id name }
          }
          """.trimIndent()
        )
        .variables(mapOf("id" to "123"))
        .requestContext(MyRequestContext(requestId = "req-1", auth = authInfo))
        .build()
    ```

=== "Java"

    ```java
    ExecutionInput input = ExecutionInput.builder()
        .operationText("query User($id: ID!) { user(id: $id) { id name } }")
        .variables(Map.of("id", "123"))
        .requestContext(yourRequestContext)
        .build();
    ```

### Convenience Factory

For simple cases:

===! "Kotlin"

    ```kotlin
    val input =
      ExecutionInput.create(
        operationText = "query { health }",
        variables = emptyMap(),
        requestContext = null
      )
    ```

=== "Java"

    ```java
    ExecutionInput input = ExecutionInput.create("query { health }");
    ```

**Notes**

- If you don’t provide `operationId`, Viaduct generates one from the operation text and name (a hash-like value).
- If you don’t provide `executionId`, Viaduct generates a random UUID string.

## Executing Operations

`Viaduct` exposes these methods:

===! "Kotlin"

    ```kotlin
    interface Viaduct {
      // Kotlin / coroutine entry point: suspends until the operation completes.
      suspend fun execute(input: ExecutionInput, schemaId: SchemaId = SchemaId.Base): ExecutionResult

      // Java-friendly entry point: returns a future and runs on the given Executor
      // (defaults to ForkJoinPool.commonPool()).
      fun executeAsync(
        input: ExecutionInput,
        schemaId: SchemaId = SchemaId.Base,
        executor: Executor = ForkJoinPool.commonPool(),
      ): CompletableFuture<ExecutionResult>
      fun getAppliedScopes(schemaId: SchemaId): Set<String>?
    }
    ```

=== "Java"

    ```java
    CompletableFuture<ExecutionResult> executeAsync(ExecutionInput input);
    CompletableFuture<ExecutionResult> executeAsync(ExecutionInput input, SchemaId schemaId);
    CompletableFuture<ExecutionResult> executeAsync(
        ExecutionInput input, SchemaId schemaId, Executor executor);
    Set<String> getAppliedScopes(SchemaId schemaId);
    ```

The Java signatures above are a summary of the callable API. Imports come from `viaduct.service.api`, `java.util.Set`, and `java.util.concurrent`. In Kotlin, `execute()` uses coroutines; Java callers compose `executeAsync()` with `thenApply()` or `thenCompose()` rather than blocking the HTTP handler with `join()`.

In both cases the returned `ExecutionResult.errors` are **sorted** by GraphQL path and then message.

Both methods default to `SchemaId.Base`. If explicit schema configuration omitted Base, callers must pass a registered `SchemaId.Scoped`; using the default returns the existing schema-not-found execution result.

### `execute(...)`

`execute` is a `suspend` function that returns the `ExecutionResult` directly. It is the idiomatic entry point for Kotlin and coroutine-based servers, and it runs in the caller's coroutine context.

===! "Kotlin"

    ```kotlin
    import viaduct.service.api.SchemaId

    suspend fun handleRequest(viaduct: Viaduct, input: ExecutionInput): Map<String, Any?> {
      val result = viaduct.execute(input, SchemaId.Base)
      return result.toSpecification()
    }
    ```

=== "Java"

    ```java
    public CompletableFuture<Map<String, Object>> handleRequest(
        Viaduct viaduct, ExecutionInput input) {
        return viaduct.executeAsync(input).thenApply(result -> result.toSpecification());
    }
    ```

**When to use**

- You’re already in a coroutine (`suspend`) context, or can launch one (`runBlocking`, etc.).
- You want execution to inherit the caller’s coroutine context.

### `executeAsync(...)`

`executeAsync` is a **non-suspending** function that returns a `CompletableFuture<ExecutionResult>`. Following the standard Java idiom, it accepts an `Executor` that controls the threads execution runs on (defaulting to `ForkJoinPool.commonPool()`). It is the idiomatic entry point for Java callers and other non-coroutine contexts.

===! "Kotlin"

    ```kotlin
    val result = viaduct.executeAsync(input, SchemaId.Base).join()
    val response = result.toSpecification()
    ```

=== "Java"

    ```java
    CompletableFuture<ExecutionResult> result = viaduct.executeAsync(input, schemaId);
    CompletableFuture<Map<String, Object>> response = result.thenApply(ExecutionResult::toSpecification);
    ```

**When to use**

- You are in a non-coroutine context (a Java caller, a servlet thread, a CLI `main`).
- You want to control the `Executor` that runs the operation.

#### Interpreting `ExecutionResult`

`ExecutionResult` is Viaduct’s wrapper (it does not expose GraphQL Java types directly):

- `getData(): Map<String, Any?>?`
- `errors: List<GraphQLError>` (Java: `getErrors()`, sorted by path then message)
- `extensions: Map<Any, Any?>?` (Java: `getExtensions()`)
- `toSpecification(): Map<String, Any?>` (ready to JSON-serialize for HTTP)

#### Data vs Errors

GraphQL supports **partial results**:

- A nullable field can error and become `null` while siblings still resolve.
- Errors in non-nullable fields may bubble up and null out parents.

You should:

- Always serialize both `"data"` (possibly `null`) and `"errors"` when errors exist.
- Treat `errors.isNotEmpty()` in Kotlin, or `!result.getErrors().isEmpty()` in Java, as “something went wrong” even if data exists.

The access-control example below uses application-owned handlers, `handleUnscopedRequest()` and `rejectInternalAccess()`.


### `getAppliedScopes(schemaId)`

This is primarily for **authorization / access-control** and auditing:

===! "Kotlin"

    ```kotlin
    val scopes: Set<String>? = viaduct.getAppliedScopes(schemaId)

    if (scopes == null) {
      // base schema (no scopes)
    } else if ("internal" !in scopes) {
      // reject or downgrade capabilities
    }
    ```

=== "Java"

    ```java
    Set<String> scopes = viaduct.getAppliedScopes(schemaId);
    if (scopes == null) {
        handleUnscopedRequest();
    } else if (!scopes.contains("internal")) {
        rejectInternalAccess();
    }
    ```

#### Semantics

- Returns `null` when no scopes are configured for the schema.
- Returns the set of scope IDs applied to the schema when it is scoped.
