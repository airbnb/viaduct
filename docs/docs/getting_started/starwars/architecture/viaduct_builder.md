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

The Java demo uses `BasicViaductFactory.create(injectorFactory, scopedSchemas)`. This creates a `ViaductBuilder` with the standard runtime defaults, the Micronaut injector factory, and the two scoped schemas. Module discovery uses the generated `META-INF/viaduct/modules/` configuration on the classpath.


This excerpt mirrors what happens in configuration (names and constants from the demo):


=== "Kotlin"

    {{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductConfiguration.kt", "viaduct_configuration", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/src/main/java/com/example/starwars/service/viaduct/ViaductConfiguration.java", start=11, end=30, lang="java") | indent(4) }}


- Kotlin names its descriptors `PUBLIC_SCHEMA` and `PUBLIC_SCHEMA_WITH_EXTRAS`; Java uses `DEFAULT_SCHEMA` and `EXTRAS_SCHEMA`. Both expose `publicSchema` and `publicSchemaWithExtras`.
- `grtPackagePrefix` and `grtResourcesIncluded` are optional test-only overrides for GRT schema file discovery (production uses defaults).
- The settings plugin records module package names during the build; generated module configuration enables runtime discovery.
- The builder creates an **immutable runtime** that the controller will use to execute requests.

## Example: executing requests through the controller

The controller **resolves scopes → chooses a schema → builds `ExecutionInput` → executes**:


=== "Kotlin"

    {{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductRestController.kt", "viaduct_graphql_controller", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codefile("demoapps/starwars-java/src/main/java/com/example/starwars/service/viaduct/ViaductRestController.java", start=31, end=58, lang="java") | indent(4) }}


> For details on `determineSchemaId(scopes)` and `createExecutionInput(...)`, see the **Scope** and **Schemas** documentation in this set.

## Builder best practices

- **Declare schema IDs** and their scope sets explicitly.
- Keep the settings plugin’s module package configuration aligned with resolver packages (`com.example.starwars...`).
- Use default GRT schema discovery in production (don't set `grtPackagePrefix` or `grtResourcesIncluded`).
- Configure **directives** and **modules** in the builder when applicable.
- Avoid conditional logic in the builder; route by scope in the controller instead.

> For the complete `ViaductBuilder` and `ExecutionInput` API reference, see the [Viaduct API developer reference](../../../docs/developers/viaduct_api/index.md).
