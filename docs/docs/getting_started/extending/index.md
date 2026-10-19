---
title: Extending the Application
description: Learn how to add new functionality to your Viaduct application
---


Let's extend the CLI application to tie its two existing fields together. The Java version follows [Build From Scratch](../setup/from_scratch/index.md); if you cloned the Java Star Wars demo, use its [field resolver exercise](../index.md#writing-a-resolver) or create the small CLI first. Viaduct is a "schema first" GraphQL server, meaning you write your schema first, and then generate classes to write your code against.

## Extending the Schema

The starter ships with two unrelated `Query` fields, `greeting` and `author`. We'll add a third field, `attributedGreeting`, that combines them — giving us a chance to see how a resolver reads sibling fields and how Viaduct generates code for object types. Open `schema.graphqls` and extend it as follows:

```graphql
extend type Query {
  greeting: String @resolver
  author: String @resolver
  attributedGreeting: AttributedGreeting @resolver
}

type AttributedGreeting {
  greeting: String
}
```

We're modeling `attributedGreeting` as an object type rather than a plain `String` so the example exercises Viaduct's generated builder and `getXOrThrow()` helpers (the GraphQL Representational Types, or GRTs, covered later on this page).

> **Codegen tip:** the next section references generated classes like `QueryResolvers.AttributedGreeting` and `AttributedGreeting.Builder` that don't exist yet. Run `./gradlew build` (or your IDE's Gradle task) so the codegen step produces them; otherwise your IDE will show red squiggles.

## Writing the Resolver

Now you need to write a resolver for the new field. Place the resolver in the `com.example.viadapp.resolvers` package configured by this application. Kotlin can add it to `HelloWorldResolvers.kt`; Java uses a separate file for each public class.

Create `AttributedGreetingResolver.kt` (Kotlin) or `AttributedGreetingResolver.java` (Java) in the resolver source directory:

=== "Kotlin"

    ```kotlin
    package com.example.viadapp.resolvers

    import viaduct.api.resolver.Resolver
    import com.example.viadapp.resolvers.resolverbases.QueryResolvers
    import viaduct.api.grts.AttributedGreeting

    @Resolver("""
      greeting
      author
    """)
    class AttributedGreetingResolver : QueryResolvers.AttributedGreeting() {
        override suspend fun resolve(ctx: Context): AttributedGreeting {
            val greeting = ctx.getObjectValue().getGreetingOrThrow()
            val author = ctx.getObjectValue().getAuthorOrThrow()
            return AttributedGreeting.Builder(ctx)
                .greeting("$author says: \"$greeting\"")
                .build()
        }
    }
    ```

=== "Java"

    ```java
    package com.example.viadapp.resolvers;

    @Resolver(objectValueFragment = "greeting author")
    public final class AttributedGreetingResolver extends QueryResolvers.AttributedGreeting {
        @Override
        public CompletableFuture<AttributedGreeting> resolve(Context context) {
            String greeting = context.getObjectValue().getGreetingOrThrow();
            String author = context.getObjectValue().getAuthorOrThrow();
            AttributedGreeting result = AttributedGreeting.builder(context)
                .greeting(author + " says: \"" + greeting + "\"")
                .build();
            return CompletableFuture.completedFuture(result);
        }
    }
    ```

## Understanding the Resolver

Let's break down what's happening in this resolver:

### The @Resolver Annotation

=== "Kotlin"

    ```kotlin
    @Resolver("""
      greeting
      author
    """)
    ```

=== "Java"

    ```java
    @Resolver(objectValueFragment = "greeting author")
    ```

The `@Resolver` annotation indicates that this resolver needs access to the `greeting` and `author` fields. If the annotation didn't mention the `author` field, for example, then the attempt to read `ctx.getObjectValue().getAuthorOrThrow()` would fail at runtime.

This is an important feature of Viaduct: it allows you to declare dependencies between fields, ensuring efficient query execution.

### Accessing Field Values

=== "Kotlin"

    ```kotlin
    val greeting = ctx.getObjectValue().getGreetingOrThrow()
    val author = ctx.getObjectValue().getAuthorOrThrow()
    ```

=== "Java"

    ```java
    String greeting = context.getObjectValue().getGreetingOrThrow();
    String author = context.getObjectValue().getAuthorOrThrow();
    ```

`ctx` is the per-resolver execution context — it gives you access to the parent object, arguments, and request-scoped utilities. Inside a `Query` field resolver, `ctx.getObjectValue()` represents the `Query` root, so `getGreetingOrThrow()` and `getAuthorOrThrow()` return the values produced by the sibling `GreetingResolver` and `AuthorResolver`. Viaduct ensures those run before this resolver because the `@Resolver` annotation declared a dependency on them.

### Building the Result

=== "Kotlin"

    ```kotlin
    return AttributedGreeting.Builder(ctx)
        .greeting("$author says: \"$greeting\"")
        .build()
    ```

=== "Java"

    ```java
    AttributedGreeting result = AttributedGreeting.builder(context)
        .greeting(author + " says: \"" + greeting + "\"")
        .build();
    return CompletableFuture.completedFuture(result);
    ```

To create an instance of the `AttributedGreeting` GraphQL type, we use the generated builder class. All GraphQL object types have a corresponding builder for type-safe construction.

## Understanding Viaduct's Code Generation

Viaduct generates two main types of code:

### 1. GraphQL Representational Types (GRTs)

For every GraphQL type, Viaduct generates a Kotlin interface or class, or a Java class, to represent it in code. We call these **GraphQL Representational Types**, or **GRTs** for short. Kotlin GRTs are in `viaduct.api.grts`; Java GRTs are in `viaduct.java.grts`.

For example, our `AttributedGreeting` GraphQL type has a corresponding `AttributedGreeting` GRT that we import:

=== "Kotlin"

    ```kotlin
    import viaduct.api.grts.AttributedGreeting
    ```

=== "Java"

    ```java
    ```

### 2. Resolver Base Classes

Viaduct also generates a **resolver base class** for writing resolvers. For each field `Type.field` with an `@resolver` directive in the schema, we generate a base class `Type.Field`.

As illustrated by our example, to write a resolver for that field, you subclass this base class and override the `resolve` function:

=== "Kotlin"

    ```kotlin
    class AttributedGreetingResolver : QueryResolvers.AttributedGreeting() {
        override suspend fun resolve(ctx: Context): AttributedGreeting {
            // Implementation
        }
    }
    ```

=== "Java"

    ```java
    public final class AttributedGreetingResolver extends QueryResolvers.AttributedGreeting {
        @Override
        public CompletableFuture<AttributedGreeting> resolve(Context context) {
            return CompletableFuture.completedFuture(
                AttributedGreeting.builder(context).greeting("Hello!").build());
        }
    }
    ```

## Testing the New Field

Save your files and run:

```shell
./gradlew -q run --args="'{ attributedGreeting { greeting } }'"
```

You should see the appropriate response with the author attribution!

## What's Next

**StarWars Deep Dive.** The StarWars application comes with a deep dive document describing Viaduct features in some detail. [View the StarWars documentation](../starwars/index.md).

**Documentation.** Explore our [full documentation site](../../index.md).

**Building your own application.** Pick the structure that fits your project — single project, two-project (root plus one module), or multi-module — and start from one of the [starter applications](../setup/clone/index.md) (CLI, Micronaut, or Star Wars). Customize the schema, resolvers, and any wiring you need.
