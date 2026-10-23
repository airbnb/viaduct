---
title: Viaduct and ViaductBuilder
description: How the Viaduct runtime is constructed in the Star Wars demo using ViaductBuilder.
---


This page explains **how the Viaduct runtime is built** in the Star Wars demo, referencing the configuration code (for example, `ViaductConfiguration.kt`) and the controller that executes requests (`ViaductRestController.kt` or `ViaductRestController.java`).

> Goal: make it clear **what the builder registers**, **how schemas are defined**, and **what the runtime looks like** when it receives an `ExecutionInput` to resolve queries and mutations.

## High-level flow

1. **Schema registration** (IDs, SDL discovery, and scope sets).
2. **Module registration** (generated types, resolvers, and package conventions).
3. **Runtime construction** via `ViaductBuilder`.
4. **Execution**: the controller creates an `ExecutionInput` with operation text and variables, selects a schema ID, and calls `viaduct.execute(...)` (Kotlin) or `viaduct.executeAsync(...)` (Java).

## Builder configuration

Both demos build Viaduct with `ViaductBuilder`, the Micronaut injector factory, and every declared scoped schema. Module discovery uses the generated `META-INF/viaduct/modules/` configuration on the classpath.


This excerpt is the demo's configuration:


=== "Kotlin"

    {{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductConfiguration.kt", "viaduct_configuration", lang="kotlin", strip_comments=True) | indent(4) }}

=== "Java"

    {{ codetag("demoapps/starwars-java/src/main/java/com/example/starwars/service/viaduct/ViaductConfiguration.java", "viaduct_configuration", lang="java", strip_comments=True) | indent(4) }}


- `providesStarWarsSchemas` selects the two scoped schemas the controller routes between, `publicSchema` and `publicSchemaWithExtras`, from what `scopes.yaml` declared.
- `withTenantModuleInjectorFactory` lets Micronaut construct resolver classes.
- `withScopedSchemas(SchemaScopeInfo.fromResources())` registers every declared scoped schema.
- The builder creates an **immutable runtime** that the controller will use to execute requests.

## Example: executing requests through the controller

Each route **passes its schema ID → the controller builds `ExecutionInput` → executes**:


=== "Kotlin"

    {{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductRestController.kt", "viaduct_graphql_controller", lang="kotlin", strip_comments=True) | indent(4) }}

=== "Java"

    {{ codetag("demoapps/starwars-java/src/main/java/com/example/starwars/service/viaduct/ViaductRestController.java", "viaduct_graphql_controller", lang="java", strip_comments=True) | indent(4) }}


> For details on the schema routes and `createExecutionInput(...)`, see the **Scope** and **Schemas** documentation in this set.

## Builder best practices

- **Declare schema IDs** and their scope sets in `scopes.yaml`, and read them with `SchemaScopeInfo.fromResources` instead of restating them in code.
- Keep the settings plugin’s module package configuration aligned with resolver packages (`com.example.starwars...`).
- Avoid conditional logic in the builder; serve each scoped schema on its own route in the controller instead.

> For the complete `ViaductBuilder` and `ExecutionInput` API reference, see the [Viaduct API developer reference](../../../docs/developers/viaduct_api/index.md).
