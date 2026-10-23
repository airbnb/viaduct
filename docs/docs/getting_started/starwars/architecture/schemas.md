---
title: Schemas
description: How schemas are defined, registered, and selected at runtime in the Star Wars demo using Viaduct.
---


In Viaduct, a **schema** describes the GraphQL surface that a request can access. In the Star Wars demo, we register two schema IDs — one public and one with extra fields — and select between them at runtime based on **scopes**.

## What a schema is

- A **set of SDL files** (`*.graphqls`) under src/main/viaduct/schema.
- A **schema ID** that names a concrete slice of the overall SDL (for example, public vs. public-with-extras).
- A set of **scope IDs**, declared in `src/main/viaduct/scopes.yaml`, that controls which fields each slice contains.

The Star Wars demo declares two schema IDs:

{{ codefile("demoapps/starwars/src/main/viaduct/scopes.yaml") }}

- `publicSchema` — the default public surface.
- `publicSchemaWithExtras` — includes everything from `publicSchema` plus extra fields marked with `@scope(to: ["extras"])`.

The build checks every `@scope` name against `schemaScopes` and builds both scoped schemas. See [Scope Schemas](../../../docs/service_engineers/scope_schemas/index.md) for the file's syntax.

## Where schemas are registered

Schemas are registered when creating a Viaduct instance. The demo reads the scoped schemas the build declared with `SchemaScopeInfo.fromResources()` and registers all of them (excerpt from `ViaductConfiguration.kt`):


=== "Kotlin"

    {{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductConfiguration.kt", "schema_registration", lang="kotlin") | indent(4) }}

=== "Java"

    {{ codetag("demoapps/starwars-java/src/main/java/com/example/starwars/service/viaduct/ViaductConfiguration.java", "schema_registration", lang="java") | indent(4) }}


- `withScopedSchemas` registers exactly the listed views. Each declared scoped schema becomes a `SchemaScopeInfo.Scoped`; add `SchemaScopeInfo.Base` to also serve the unfiltered base view. Schemas without `@scope` need neither `scopes.yaml` nor `withScopedSchemas`.

## Organizing SDL files

Place each tenant's GraphQL SDL files under its `src/main/viaduct/schema` directory. Keep entities modular (for example, `character.graphqls`, `film.graphqls`, `species.graphqls`) and use **directives** like `@scope`, `@idOf`, and `@oneOf` where appropriate.

### Example (fragment)


{{ codetag("demoapps/starwars/modules/universe/src/main/viaduct/schema/Planet.graphqls", "schemas_example", lang="graphql") }}


> Fields like `film` are typically resolved by **field/batch resolvers**, not embedded in SDL logic.

## Evolving the schema

To add a field that should exist **only** in the extras slice:

1. Declare it in SDL with a scope:


{{ codetag("demoapps/starwars/modules/universe/src/main/viaduct/schema/Species.graphqls", "schemas_extras_example", lang="graphql") }}


2. Ensure a scoped schema that includes `extras` is declared in `scopes.yaml` (`publicSchemaWithExtras`).
3. Serve that schema ID on a route in the controller, as the demo does with `/extras/graphql`.

To add a new entity:

- Define the type and relationships in SDL.
- Implement node/field (and batch if needed) resolvers.
- Emit typed IDs with `ctx.globalIDFor(Type.Reflection, internalId)` in Kotlin or `context.globalIDFor(Type.ofClass(Character.class), internalId)` in Java.
- Write integration tests that hit both schema IDs if visibility differs.

## Testing and troubleshooting

- Validate that the expected fields appear in **introspection** under each schema ID.
- Run end-to-end queries for both public and extras slices.
- Confirm that resolvers are wired and **batched** correctly when many parents are selected.
- Log the route and `schemaId` each request runs against.

## Do and don’t

- **Do** register schema IDs with clear scope sets (public vs. public + extras).
- **Do** keep SDL modular and use directives for visibility and type-safety.
- **Don’t** rely on a single “mega schema” and conditional logic inside resolvers to hide fields.

> For multi-tenant schema management at the service layer, see the [Multi-tenancy service engineer docs](../../../docs/service_engineers/multi_tenancy/index.md). For the full `@scope` directive reference, see the [Scopes developer reference](../../../docs/developers/scopes/index.md).
