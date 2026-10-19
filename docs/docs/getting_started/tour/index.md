---
title: Touring the Application
description: Understanding the structure of a Viaduct application
---


Now that you have a running Viaduct application, let's explore its structure and understand how the pieces fit together. The Kotlin tour follows the [CLI starter](https://github.com/viaduct-dev/cli-starter). The Java tour follows the minimal CLI from [Build From Scratch](../setup/from_scratch/index.md). If you cloned the Java Star Wars demo, its app and tenant modules have these same roles; [Star Wars Architecture](../starwars/architecture/index.md) maps them to that larger project.

## Project Structure

The main elements of this application's source-code directory are:

=== "Kotlin"

    ```
    build.gradle.kts
    src/
       main/viaduct/schema/schema.graphqls
       main/kotlin/com/example/viadapp/ViaductApplication.kt
       main/kotlin/com/example/viadapp/resolvers/HelloWorldResolvers.kt
       ...
    ...
    ```

=== "Java"

    ```text
    settings.gradle.kts
    build.gradle.kts
    src/
       main/viaduct/schema/schema.graphqls
       main/java/com/example/viadapp/ViaductApplication.java
       main/java/com/example/viadapp/resolvers/GreetingResolver.java
       main/java/com/example/viadapp/resolvers/AuthorResolver.java
    ```

You can see that a Viaduct application is a Gradle project. Let's examine each key component:

## Schema Definition

**`schema.graphqls`** contains the GraphQL schema for the application. All files matching `**/*.graphqls` in that directory collectively define the schema of the application.

Here's what you'll see in the schema:


```graphql
extend type Query {
  greeting: String @resolver
  author: String @resolver
}
```


Viaduct itself has built-in definitions for the root GraphQL types `Query` and `Mutation`. Subscriptions are not yet supported; see the [roadmap](../../roadmap/index.md) for the current plan. Since `Query` is built-in, application code should extend it as illustrated above.

You'll notice that both fields have `@resolver` applied to them, meaning that a developer-provided function is needed to compute their respective value. **All fields of `Query` must have `@resolver` applied to them.**

## Application Entry Point

**`ViaductApplication.kt`** (Kotlin) or **`ViaductApplication.java`** (Java) contains the `main` function for this command-line tool. This file has three main responsibilities:

### 1. Creating the Viaduct Engine


=== "Kotlin"

    {{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/viadapp/ViaductApplication.kt", "building-viaduct", lang="kotlin") | indent(4) }}

=== "Java"

    ```java
    Viaduct viaduct = BasicViaductFactory.create();
    ```

    `BasicViaductFactory` is callable directly from Java. The factory discovers generated module configuration on the classpath and uses a no-argument injector for this minimal app. The Java Star Wars demo instead passes a Micronaut injector factory and its scoped schema descriptors.


This creates an instance of the Viaduct engine using `BasicViaductFactory.create()`, which discovers tenant modules and schemas automatically from the classpath. For more advanced setups—such as registering multiple scoped schemas or wiring in a dependency injection framework—use `ViaductBuilder` directly.

### 2. Preparing the Query


=== "Kotlin"

    {{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/viadapp/ViaductApplication.kt", "create-execution-input", lang="kotlin") | indent(4) }}

=== "Java"

    ```java
    String query = args.length > 0 ? args[0] : "{ greeting }";
    ExecutionInput input = ExecutionInput.builder()
        .operationText(query)
        .build();
    ```


This creates an `ExecutionInput` that wraps the GraphQL query to be executed. It takes the query from command-line arguments, or uses a default query if none is provided.

### 3. Executing the Query


=== "Kotlin"

    {{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/viadapp/ViaductApplication.kt", "viaduct-execute-operation", lang="kotlin") | indent(4) }}

=== "Java"

    ```java
    var result = viaduct.executeAsync(input, SchemaId.Base.INSTANCE).join();
    System.out.println(result.toSpecification());
    ```

    Java receives a `CompletableFuture<ExecutionResult>`. This CLI waits at its outer boundary; an HTTP handler should return or compose the future, as the Java Star Wars controller does.


This sends the query to the Viaduct engine and waits for the result. Kotlin callers use coroutines; Java callers use `executeAsync`.

## Resolver Implementation

**`HelloWorldResolvers.kt`** holds the Kotlin resolver classes. In the Java CLI, **`GreetingResolver.java`** and **`AuthorResolver.java`** hold them for this schema; each provides the value-producing function for a field marked with `@resolver`.

Each resolver is a class that extends a generated base class and overrides the `resolve` function:


=== "Kotlin"

    {{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/resolvers/HelloWorldResolvers.kt", "greeting-resolver", lang="kotlin") | indent(4) }}

=== "Java"

    ```java
    package com.example.viadapp.resolvers;

    @Resolver
    public final class GreetingResolver extends QueryResolvers.Greeting {
        @Override
        public CompletableFuture<String> resolve(Context context) {
            return CompletableFuture.completedFuture("Hello, world!");
        }
    }
    ```


The `@Resolver` annotation specifies which other fields this resolver depends on (in this case, none, so it's an empty string).

## Understanding the Build Configuration

At the top of `build.gradle.kts` you'll see:


=== "Kotlin"

    {{ codetag("demoapps/cli-starter/build.gradle.kts", "plugins-config", lang="kotlin") | indent(4) }}

=== "Java"

    The Gradle build remains Kotlin DSL. Apply the Java module plugin for Java tenant code:

    ```kotlin
    plugins {
        application
        `java-library`
        id("com.airbnb.viaduct.application-gradle-plugin") version "2.1.0-SNAPSHOT"
        id("com.airbnb.viaduct.module-java-gradle-plugin") version "2.1.0-SNAPSHOT"
    }
    ```

    The settings plugin registers each module’s package, and the Java module plugin generates Java GRTs and resolver bases and processes Java annotations. See [Build From Scratch](../setup/from_scratch/index.md) for the complete settings and dependency configuration.


You can see the two Viaduct plugins appearing here:

- **`viaduct.application`**: This plugin must be applied to the root project. It coordinates build processes across the entire application, including code generation.

- **`viaduct.module`** (Kotlin) / **`viaduct.module.java`** (Java): This plugin indicates that the project contains application code (resolvers). One or more projects in your build can apply this plugin.

Viaduct applications are structured as multi-project Gradle builds. The CLI starter is the simplest case — a single project where both plugins are applied to the same module. Real-world applications usually split things up: one project applies `viaduct.application` and wires the runtime, and one or more sibling projects apply `viaduct.module` to contribute schema and resolvers (one Gradle module per **tenant**). The Star Wars demo's [Architecture section](../starwars/architecture/index.md) walks through that layout end-to-end.

## What's Next

Continue to [Extending the Application](../extending/index.md) to learn how to add new functionality to your Viaduct application.
