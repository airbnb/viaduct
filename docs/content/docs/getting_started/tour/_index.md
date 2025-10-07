---
title: Touring the Application
description: Understanding the structure of a Viaduct application
weight: 4
---

Now that you have a running Viaduct application, let's explore its structure and understand how the pieces fit together.

## Project Structure

The main elements of this application's source-code directory are:

```
build.gradle.kts
src/
   main/viaduct/schema/schema.graphqls
   main/kotlin/com/example/viadapp/ViaductApplication.kt
   main/kotlin/com/example/viadapp/resolvers/HelloWorldResolvers.kt
   ...
...
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

Viaduct itself has built-in definitions for the root GraphQL types `Query` and `Mutation` (Viaduct doesn't yet support subscriptions). Since `Query` is built-in, application code should extend it as illustrated above.

You'll notice that both fields have `@resolver` applied to them, meaning that a developer-provided function is needed to compute their respective value. **All fields of `Query` must have `@resolver` applied to them.**

## Application Entry Point

**`ViaductApplication.kt`** contains the `main` function for this command-line tool. This file has three main responsibilities:

### 1. Creating the Viaduct Engine

```kotlin
val viaduct = BasicViaductFactory.create(
    schemaRegistrationInfo = SchemaRegistrationInfo(
        scopes = listOf(SchemaScopeInfo(SCHEMA_ID))
    ),
    tenantRegistrationInfo = TenantRegistrationInfo(
        tenantPackagePrefix = "com.example.viadapp"
    )
)
```

This creates an instance of the Viaduct engine. The `SchemaRegistrationInfo` defines which schemas are available (in this case, our "helloworld" schema), and the `TenantRegistrationInfo` tells Viaduct where to find your resolver code.

### 2. Preparing the Query

```kotlin
val executionInput = ExecutionInput.create(
    schemaId = SCHEMA_ID,
    operationText = (
        argv.getOrNull(0)
            ?: """
                 query {
                     greeting
                 }
            """.trimIndent()
    ),
    variables = emptyMap(),
)
```

This creates an `ExecutionInput` that wraps the GraphQL query to be executed. It takes the query from command-line arguments, or uses a default query if none is provided.

### 3. Executing the Query

```kotlin
val result = runBlocking {
    coroutineScope {
        withThreadLocalCoroutineContext {
            viaduct.execute(executionInput)
        }
    }
}
```

This sends the query to the Viaduct engine and waits for the result. Viaduct uses Kotlin coroutines for asynchronous execution.

## Resolver Implementation

**`HelloWorldResolvers.kt`** contains the "application logic" for our schema—the code that defines how to resolve the fields of the schema.

Each resolver is a class that extends a generated base class and overrides the `resolve` function:

```kotlin
@Resolver("")
class GreetingResolver : QueryResolvers.Greeting() {
    override suspend fun resolve(ctx: Context): String {
        return "Hello, World!"
    }
}
```

The `@Resolver` annotation specifies which other fields this resolver depends on (in this case, none, so it's an empty string).

## Understanding the Build Configuration

At the top of `build.gradle.kts` you'll see:

```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.viaduct.application)
    alias(libs.plugins.viaduct.module)
    application
}
```

You can see the two Viaduct plugins appearing here:

- **`viaduct.application`**: This plugin must be applied to the root project. It coordinates certain build processes across the entire application, including code generation.

- **`viaduct.module`**: This plugin indicates that the project contains application code (resolvers). One or more projects in your build can apply this plugin.

Viaduct applications are structured as multi-project Gradle builds. As this example shows, a Viaduct application can be as simple as a single project build, in which case both plugins are applied to that one project.

## What's Next

Continue to [Extending the Application](../extending) to learn how to add new functionality to your Viaduct application.
