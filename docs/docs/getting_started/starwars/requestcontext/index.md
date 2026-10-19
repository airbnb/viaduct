---
title: Request Context
description: Managing request-scoped data and context in Viaduct applications.
---


Viaduct provides comprehensive support for managing request-specific context throughout your GraphQL operations. This is essential for handling authentication, authorization, tenant isolation, and other request-scoped concerns.

## Request context approaches

Viaduct supports multiple approaches to managing request context, allowing you to choose the pattern that best fits your application architecture.

### 1. Framework-based request scoping

The recommended approach is to leverage your web framework's built-in request scoping mechanisms. This provides automatic lifecycle management and prevents context leakage between requests.

#### Example with Micronaut

Compare how the demos hold per-request security state:


=== "Kotlin"

    {{ codetag("demoapps/starwars/common/src/main/kotlin/com/example/starwars/common/SecurityAccessContext.kt", "request_context", lang="kotlin") | indent(4) }}

=== "Java"

    The Java demo uses an immutable value passed in `ExecutionInput.requestContext`, rather than a Micronaut request-scoped bean. Its helper validates the context entry before use; the next section explains this route.

    {{ codefile("demoapps/starwars-java/common/src/main/java/com/example/starwars/common/SecurityAccessContext.java", lang="java") | indent(4) }}


Populate the context in your GraphQL controller:


=== "Kotlin"

    {{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductRestController.kt", "viaduct_graphql_controller", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/src/main/java/com/example/starwars/service/viaduct/ViaductRestController.java", start=31, end=58, lang="java") | indent(4) }}


Use the context in your resolvers:


=== "Kotlin"

    {{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/mutations/CreateCharacterMutation.kt", "create_example", lang="kotlin") | indent(4) }}

=== "Java"

    The Java resolver receives repository dependencies through Micronaut but reads security state from `context.getRequestContext()`. This avoids depending on a framework request scope during asynchronous execution.

    {{ codefile("demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/mutations/CreateCharacterMutation.java", lang="java") | indent(4) }}


**Framework-scoping benefits (Kotlin example):**

- Automatic lifecycle management
- Type-safe access to context data
- Prevents data leakage between requests
- Leverages framework's dependency injection
- Clean separation of concerns

### 2. ExecutionInput request context

For simpler installations or when you need to pass arbitrary context without setting up framework scoping, you can use `ExecutionInput.requestContext`.

#### Setting the request context

The `ExecutionInput` interface includes a `requestContext` property:


When creating your `ExecutionInput`, include the request context:


=== "Kotlin"

    {{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductRestController.kt", "create_execution_input", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/src/main/java/com/example/starwars/service/viaduct/ViaductRestController.java", start=43, end=53, lang="java") | indent(4) }}


The context can carry application-specific values; construct it explicitly when preparing the execution input:

=== "Kotlin"

    ```kotlin
     val requestContext = mapOf(
      "securityAccess" to securityAccess,
      "tenantId" to tenantId
     )
     val executionInput = ExecutionInput.create(
      operationText = request["query"] as String,
      variables = (request["variables"] as? Map<String, Any>) ?: emptyMap(),
      requestContext = requestContext
     )
    ```

=== "Java"

    ```java
    Map<String, Object> requestContext = Map.of(
        SecurityAccessContext.REQUEST_CONTEXT_KEY,
        new SecurityAccessContext(securityAccess));
    ExecutionInput executionInput = ExecutionInput.builder()
        .operationText(query)
        .variables(variables)
        .requestContext(requestContext)
        .build();
    ```

    The controller creates a fresh context for each execution. Do not store request credentials in a shared singleton.

#### Accessing request context in resolvers

Inside your resolvers, access the request context through the resolver context:

=== "Kotlin"

    ```kotlin
    @Resolver
    class FooQuery : FooResolver.Character() {
        override suspend fun resolve(ctx: Context): Foo? {
            val requestContext = ctx.requestContext as? Map<*, *>
            ...
        }
    }
    ```

=== "Java"

    ```java
    SecurityAccessContext security =
        SecurityAccessContext.from(context.getRequestContext());
    return CompletableFuture.completedFuture(
        security.validateAccess(() -> characterRepository.delete(characterId)));
    ```

    This illustrates the request-context access used by `DeleteCharacterMutation.java`; the full resolver also removes relationships and handles a missing character. The context is also propagated to subqueries and submutations in the same execution.

**Benefits:**

- Simple to set up
- No additional framework configuration needed
- Direct access to context data
- Flexible for passing arbitrary data

**Considerations:**

- Requires manual type casting
- Less type-safe than framework approach
- No automatic lifecycle management
- Context must be manually threaded through execution

## Choosing an approach

For production applications with complex authorization needs, authentication, or multi-tenancy, use framework-based request scoping. It provides automatic lifecycle management and type safety.

For simpler installations or prototypes where you need to pass arbitrary context without framework configuration, use `ExecutionInput.requestContext`.

> For the complete `ExecutionInput` API and runtime execution model, see the [Viaduct API developer reference](../../../docs/developers/viaduct_api/index.md).

