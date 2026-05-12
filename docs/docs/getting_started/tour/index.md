---
title: Touring the Application
description: Understanding the structure of a Viaduct application
---


Now that you have a running Viaduct application, let's explore its structure and understand how the pieces fit together. The full source for the CLI starter lives at [github.com/viaduct-dev/cli-starter](https://github.com/viaduct-dev/cli-starter); we'll highlight the key files below.

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


{{ codetag("demoapps/cli-starter/src/main/viaduct/schema/schema.graphqls", "schema-config", lang="kotlin") }}


Viaduct itself has built-in definitions for the root GraphQL types `Query` and `Mutation`. Subscriptions are not yet supported; see the [roadmap](../../roadmap/index.md) for the current plan. Since `Query` is built-in, application code should extend it as illustrated above.

You'll notice that both fields have `@resolver` applied to them, meaning that a developer-provided function is needed to compute their respective value. **All fields of `Query` must have `@resolver` applied to them.**

## Application Entry Point

**`ViaductApplication.kt`** contains the `main` function for this command-line tool. This file has three main responsibilities:

### 1. Creating the Viaduct Engine


{{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/viadapp/ViaductApplication.kt", "building-viaduct", lang="kotlin") }}


This creates an instance of the Viaduct engine using `BasicViaductFactory.create()`, which discovers tenant modules and schemas automatically from the classpath. For more advanced setups—such as registering multiple scoped schemas or wiring in a dependency injection framework—use `ViaductBuilder` directly.

### 2. Preparing the Query



{{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/viadapp/ViaductApplication.kt", "create-execution-input", lang="kotlin") }}


This creates an `ExecutionInput` that wraps the GraphQL query to be executed. It takes the query from command-line arguments, or uses a default query if none is provided.

### 3. Executing the Query



{{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/viadapp/ViaductApplication.kt", "viaduct-execute-operation", lang="kotlin") }}


This sends the query to the Viaduct engine and waits for the result. Viaduct uses Kotlin coroutines for asynchronous execution.

## Resolver Implementation

**`HelloWorldResolvers.kt`** holds the resolver classes for this schema — each one provides the value-producing function for a field marked with `@resolver`.

Each resolver is a class that extends a generated base class and overrides the `resolve` function:


{{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/resolvers/HelloWorldResolvers.kt", "greeting-resolver", lang="kotlin") }}


The `@Resolver` annotation specifies which other fields this resolver depends on (in this case, none, so it's an empty string).

## Understanding the Build Configuration

At the top of `build.gradle.kts` you'll see:



{{ codetag("demoapps/cli-starter/build.gradle.kts", "plugins-config", lang="kotlin") }}


You can see the two Viaduct plugins appearing here:

- **`viaduct.application`**: This plugin must be applied to the root project. It coordinates build processes across the entire application, including code generation.

- **`viaduct.module`**: This plugin indicates that the project contains application code (resolvers). One or more projects in your build can apply this plugin.

Viaduct applications are structured as multi-project Gradle builds. The CLI starter is the simplest case — a single project where both plugins are applied to the same module. Real-world applications usually split things up: one project applies `viaduct.application` and wires the runtime, and one or more sibling projects apply `viaduct.module` to contribute schema and resolvers (one Gradle module per **tenant**). The Star Wars demo's [Architecture section](../starwars/architecture/index.md) walks through that layout end-to-end.

## What's Next

Continue to [Extending the Application](../extending/index.md) to learn how to add new functionality to your Viaduct application.
