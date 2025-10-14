---
title: Viaduct and ViaductBuilder
description: How the Viaduct runtime is constructed in the Star Wars demo using ViaductBuilder.
layout: docs/single
weight: 3
linkTitle: Viaduct & Builder
---

This page explains **how the Viaduct runtime is built** in the Star Wars demo, referencing the configuration code
(for example, `ViaductConfiguration.kt`) and the controller that executes requests (`ViaductGraphQLController.kt`).

> Goal: make it clear **what the builder registers**, **how schemas are defined**, and **what the runtime looks like**
> when it receives an `ExecutionInput` to resolve queries and mutations.

## High-level flow

1. **Schema registration** (IDs, SDL discovery, and scope sets).
2. **Module registration** (generated types, resolvers, and package conventions).
3. **Runtime construction** via `ViaductBuilder`.
4. **Execution**: the controller creates an `ExecutionInput` (with `schemaId`, `query`, `variables`, etc.) and calls
   `viaduct.executeAsync(...)`.

## Builder configuration

This excerpt mirrors what happens in configuration (names and constants from the demo):

{{< codetag path="demoapps/starwars/src/main/kotlin/viaduct/demoapp/starwars/config/ViaductConfiguration.kt" tag="viaduct_configuration" >}}


- `PUBLIC_SCHEMA` and `PUBLIC_SCHEMA_WITH_EXTRAS` are **schema IDs** used by the demo.
- `packagePrefix` and `resourcesIncluded` tells Viaduct **where** to discover SDL and generated types.
- The builder creates an **immutable runtime** that the controller will use to execute requests.

## Example: executing requests through the controller

The controller **resolves scopes → chooses a schema → builds `ExecutionInput` → executes**:

{{< codetag path="demoapps/starwars/src/main/kotlin/viaduct/demoapp/starwars/rest/ViaductGraphQLController.kt" tag="viaduct_graphql_controller" >}}

> For details on `determineSchemaId(scopes)` and `createExecutionInput(...)`, see the **Scope** and **Schemas**
> documentation in this set.

## Builder best practices

- **Declare schema IDs** and their scope sets explicitly.
- Keep `packagePrefix` aligned with generated code (`viaduct.demoapp...`).
- Configure **directives** and **modules** in the builder when applicable.
- Avoid conditional logic in the builder; route by scope in the controller instead.

{{< prevnext >}}
