---
title: GraphQL Operations
description: Declaring reusable, build-time-validated GraphQL operations with @GraphQLOperation and executing them via ctx.query / ctx.mutation.
---


`@GraphQLOperation` declares a GraphQL **operation** (a query or a mutation) on a Kotlin singleton `object` or Java class. Execute an instance as a [subquery](subqueries.md) with `ctx.query()` / `ctx.mutation()`. These Kotlin APIs accept annotated operation objects, not inline selection strings or `SelectionSet` instances. The operation document is validated against the schema at build time.

`@GraphQLOperation`, `QueryFromAnnotation`, and `MutationFromAnnotation` are [stable APIs](../api_stability/index.md) (`@StableApi`).

## Declaring an operation

Annotate a declaration with `@GraphQLOperation` and extend `QueryFromAnnotation` (for a query) or `MutationFromAnnotation` (for a mutation). Java examples use a reusable `INSTANCE`; put each public class in its own file:

=== "Kotlin"

    ```kotlin
    import viaduct.api.documents.GraphQLOperation
    import viaduct.api.documents.MutationFromAnnotation
    import viaduct.api.documents.QueryFromAnnotation

    @GraphQLOperation("query(\$id: ID!) { user(id: \$id) { id name } }")
    object GetUserQuery : QueryFromAnnotation()

    @GraphQLOperation("mutation(\$input: SendMessageInput!) { sendMessage(input: \$input) { success } }")
    object SendMessageMutation : MutationFromAnnotation()
    ```

=== "Java"

    ```java
    @GraphQLOperation("query($id: ID!) { user(id: $id) { id name } }")
    public final class GetUserQuery extends QueryFromAnnotation {
      public static final GetUserQuery INSTANCE = new GetUserQuery();
      private GetUserQuery() {}
    }

    @GraphQLOperation("mutation($input: SendMessageInput!) { sendMessage(input: $input) { success } }")
    public final class SendMessageMutation extends MutationFromAnnotation {
      public static final SendMessageMutation INSTANCE = new SendMessageMutation();
      private SendMessageMutation() {}
    }
    ```


Rules:

* Kotlin declarations must be singleton `object` declarations. Java declarations are classes using the `viaduct.java.api.annotations` annotation and `viaduct.java.api.documents` base classes. A singleton instance is a convenient convention, not a requirement.
* The document must contain **exactly one** operation.
* The operation type must match the base class — a `query` for `QueryFromAnnotation`, a `mutation` for `MutationFromAnnotation`. (An anonymous shorthand document like `{ ... }` counts as a query.) Subscriptions are not supported.
* Variable declarations are validated against the schema.
* The document may spread named fragments (`...FragmentName`) declared with [`@GraphQLFragment`](named_fragments.md) in the **same tenant module**. These fragments participate in build-time validation; the runtime bridge inlines reachable fragments before execution.

Operations are discovered at build time by scanning for `@GraphQLOperation` (KSP for Kotlin, annotation processing for Java). Kotlin validates against the tenant module's compilation schema. Java validates against the merged application schema supplied by the Java module plugin, matching its shared application GRT generation.

## Executing a query operation

Pass the operation **object** to `ctx.query()`, together with a map of variables. The result is a typed Query GRT. Java returns `CompletableFuture<Query>`; use `thenApply` to read it and `thenCompose` for more asynchronous work:

=== "Kotlin"

    ```kotlin
    @Resolver
    class UserLabelResolver : QueryResolvers.UserLabel() {
        override suspend fun resolve(ctx: Context): String? {
            val result = ctx.query(GetUserQuery, mapOf("id" to ctx.arguments.id))
            return result.getUserOrThrow()?.getNameOrThrow()
        }
    }
    ```

=== "Java"

    ```java
    @Resolver
    public final class UserLabelResolver extends QueryResolvers.UserLabel {
      @Override
      public CompletableFuture<String> resolve(Context ctx) {
        return ctx.query(GetUserQuery.INSTANCE, Map.of("id", ctx.getArguments().getId()))
            .thenApply(result -> {
              var user = result.getUser();
              return user == null ? null : user.getName();
            });
      }
    }
    ```


## Executing a mutation operation

`ctx.mutation()` accepts a `MutationFromAnnotation` object the same way. As always, `ctx.mutation()` is only available in a resolver on the root `Mutation` type — Kotlin prevents calling it from a query resolver at compile time. Java rejects calls outside mutation-capable resolver contexts at runtime; the inherited legacy overloads are deprecated:

=== "Kotlin"

    ```kotlin
    @Resolver
    class SendAndConfirmResolver : MutationResolvers.SendAndConfirm() {
        override suspend fun resolve(ctx: Context): Boolean {
            val result = ctx.mutation(SendMessageMutation, mapOf("input" to ctx.arguments.input))
            return result.getSendMessageOrThrow()?.getSuccessOrThrow() ?: false
        }
    }
    ```

=== "Java"

    ```java
    @Resolver
    public final class SendAndConfirmResolver extends MutationResolvers.SendAndConfirm {
      @Override
      public CompletableFuture<Boolean> resolve(Context ctx) {
        return ctx.mutation(
                SendMessageMutation.INSTANCE, Map.of("input", ctx.getArguments().getInput()))
            .thenApply(result -> {
              var sent = result.getSendMessage();
              return sent != null && Boolean.TRUE.equals(sent.getSuccess());
            });
      }
    }
    ```


Submutations run against the root `Mutation` type with standard serial semantics and share the parent request's state (data loaders, error accumulation, instrumentation, and request-scoped context such as access checks). See [Subqueries](subqueries.md#schema-and-isolation) for the isolation model.

## Runtime branching

Keep each operation document fixed at build time. If runtime data determines which fields to fetch, declare a named operation for each selection shape and choose the object at runtime. Pass runtime argument values through the variables map.

=== "Kotlin"

    ```kotlin
    @GraphQLOperation("query(\$id: ID!) { user(id: \$id) { id } }")
    object GetUserIdQuery : QueryFromAnnotation()
    ```

=== "Java"

    ```java
    @GraphQLOperation("query($id: ID!) { user(id: $id) { id } }")
    public final class GetUserIdQuery extends QueryFromAnnotation {
      public static final GetUserIdQuery INSTANCE = new GetUserIdQuery();
      private GetUserIdQuery() {}
    }
    ```


Inside a resolver, choose between `GetUserIdQuery` and the `GetUserQuery` declared above:

=== "Kotlin"

    ```kotlin
    val operation: QueryFromAnnotation = if (includeName) GetUserQuery else GetUserIdQuery
    val result = ctx.query(operation, variables = mapOf("id" to id))
    val user = result.getUserOrThrow()
    val name = if (includeName) user?.getNameOrThrow() else null
    ```

=== "Java"

    ```java
    QueryFromAnnotation operation = includeName ? GetUserQuery.INSTANCE : GetUserIdQuery.INSTANCE;
    return ctx.query(operation, Map.of("id", id))
        .thenApply(result -> {
          var user = result.getUser();
          return includeName && user != null ? user.getName() : null;
        });
    ```


Only read fields selected by the operation you executed. Both operations return a Query GRT, but `GetUserIdQuery` does not populate `name`. The same branching pattern works with `MutationFromAnnotation` objects in mutation resolvers.

## Operation text

The Kotlin base classes expose the annotation's raw document as `operationText`; Java uses `getOperationText()`. This is useful for diagnostics or test helpers that explicitly accept document text. It is not an argument to Kotlin `ctx.query()` or `ctx.mutation()`; always pass the operation object. The raw text can still contain external named-fragment spreads, which the runtime bridge resolves when executing the object.

## Choosing between operations and `@Resolver` fragments

| Approach | Use when |
|----------|----------|
| `@GraphQLOperation` + `ctx.query()` / `ctx.mutation()` | Your resolver needs imperative execution, runtime variable values, or runtime selection among named operations; each operation document is declared and validated at build time |
| `objectValueFragment` / `queryValueFragment` in `@Resolver` | The data comes from the parent object or root `Query` and is known ahead of time, so the engine can fetch and batch it before your resolver runs |

For the underlying subquery execution model, see [Subqueries](subqueries.md). For a worked, runnable example in the Star Wars demo app, see the [GraphQL Operations tutorial](../../../getting_started/starwars/core/graphql_operations.md).

Java generated contexts infer the tenant Query / Mutation GRT class. The lower-level overload also accepts that class explicitly, such as `ctx.query(GetUserQuery.INSTANCE, variables, Query.class)`. Java additionally supports selection-string overloads, but annotated operations provide build-time validation and named-fragment expansion. Pass the operation instance to retain those semantics. See [CharacterByNameOperation](https://github.com/airbnb/viaduct/blob/main/demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/operations/CharacterByNameOperation.java) and [RenameCharacterSummaryMutation](https://github.com/airbnb/viaduct/blob/main/demoapps/starwars-java/modules/filmography/src/main/java/com/example/starwars/modules/filmography/characters/mutations/RenameCharacterSummaryMutation.java). Java examples use `java.util.Map`, `java.util.concurrent.CompletableFuture`, and the Java `Resolver` annotation.
