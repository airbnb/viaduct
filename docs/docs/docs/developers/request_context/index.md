---
title: Request Context
description: Passing per-request data — authentication tokens, caller identity, feature flags — into resolvers.
---

Resolvers often need access to data that is scoped to the incoming request: the caller's identity, an auth token, a tenant ID, a locale. Viaduct provides two mechanisms for this. They are complementary — choose based on how much type safety and framework integration you want.

## Approach 1: Framework-scoped beans (recommended)

The cleanest approach is to store request-scoped data in a bean managed by your DI framework. Viaduct instantiates a new resolver instance per field invocation via `CodeInjector`, so your DI container's request scope applies naturally.

The StarWars demo uses this pattern. A `@RequestScope` bean holds the security context for each request:

```kotlin title="demoapps/starwars/common/src/main/kotlin/com/example/starwars/common/SecurityAccessContext.kt"
@RequestScope
open class SecurityAccessContext {
    private var securityAccess: String? = null

    open fun setSecurityAccess(securityAccess: String?) {
        this.securityAccess = securityAccess
    }

    open fun <T> validateAccess(block: () -> T): T {
        if (securityAccess != "admin") throw SecurityException("Insufficient permissions!")
        return block()
    }
}
```

The controller populates it before execution:

```kotlin title="demoapps/starwars/src/main/kotlin/.../ViaductRestController.kt"
@Post("/graphql")
suspend fun graphql(
    @Body request: Map<String, Any>,
    @Header("security-access") securityAccess: String?,
): HttpResponse<Map<String, Any?>> {
    securityAccessService.setSecurityAccess(securityAccess)
    val result = viaduct.executeAsync(createExecutionInput(request)).await()
    return HttpResponse.ok(result.toSpecification())
}
```

Resolvers declare the bean as a constructor dependency — no casting, no boilerplate:

```kotlin title="demoapps/starwars/modules/filmography/.../CreateCharacterMutation.kt"
@Resolver
class CreateCharacterMutation @Inject constructor(
    private val characterRepository: CharacterRepository,
    private val securityAccessContext: SecurityAccessContext,
) : MutationResolvers.CreateCharacter() {
    override suspend fun resolve(ctx: Context): Character =
        securityAccessContext.validateAccess {
            // securityAccessContext is already populated for this request
            characterRepository.add(...)
        }
}
```

**Why this is preferred:**

- Fully type-safe — no casts
- Lifecycle is managed by the framework — no risk of leaking state between requests
- Integrates naturally with other DI-managed beans (repositories, service clients, etc.)
- Works with any DI framework that has a request scope (Micronaut, Spring, Guice, etc.)

This approach requires a `TenantModuleBootstrapper` that wires your DI container into Viaduct. See [Dependency Injection](../../service_engineers/dependency_injection/index.md).

## Approach 2: ExecutionInput.requestContext

For simpler setups, or when you don't have a DI container with a request scope, you can attach arbitrary data directly to `ExecutionInput` and read it inside resolvers via `ctx.requestContext`.

Set it when building the input:

```kotlin
val input = ExecutionInput.create(
    operationText = request["query"] as String,
    variables = (request["variables"] as? Map<String, Any>) ?: emptyMap(),
    requestContext = mapOf(
        "callerId" to callerId,
        "locale" to locale,
    ),
)
val result = viaduct.executeAsync(input).await()
```

Access it in any resolver through the execution context:

```kotlin
@Resolver
class CharacterResolver : CharacterQueryResolverBase() {
    override suspend fun resolve(ctx: Context): Character? {
        val rc = ctx.requestContext as? Map<*, *>
        val callerId = rc?.get("callerId") as? String
        // use callerId ...
    }
}
```

`requestContext` is typed as `Any?` on `ExecutionContext` — it's whatever object you passed in. A single typed wrapper class avoids scattered casts:

```kotlin
data class AppRequestContext(
    val callerId: String,
    val locale: String,
)

// At the controller:
requestContext = AppRequestContext(callerId = callerId, locale = locale)

// In a resolver:
val rc = ctx.requestContext as? AppRequestContext
```

**When to use this approach:**

- No DI framework, or your DI framework has no request scope
- Prototyping or simple installations
- Passing a small number of values without setting up a full DI bootstrapper

**Trade-offs:**

- Requires a cast — if you pass the wrong type you get a runtime error, not a compile error
- You are responsible for ensuring the object is immutable or thread-safe — resolvers for the same request can execute concurrently

## Testing

Both approaches are testable via the resolver spec test utilities. Set `requestContext` on the spec to simulate request-scoped data:

```kotlin
val result = runFieldResolver(MyResolver()) {
    objectValue = MyType.of(ctx)
    requestContext = AppRequestContext(callerId = "user-42", locale = "en-US")
}
```

For the DI approach, pass a pre-configured mock of your request-scoped bean via the resolver's constructor instead.

## See also

- [Dependency Injection](../../service_engineers/dependency_injection/index.md) — Wiring a DI container so resolvers can declare constructor dependencies
- [Server Integration](../../service_engineers/server_integration/index.md) — How `ExecutionInput` is constructed at the entry point
- [Testing](../testing/index.md) — Testing resolvers with `requestContext`
