---
title: Schemas
description: How schemas are defined, registered, and selected at runtime in the Star Wars demo using Viaduct.
layout: docs/single
weight: 2
linkTitle: Schemas
---

In Viaduct, a **schema** describes the GraphQL surface that a request can access. In the Star Wars demo, we register
two schema IDs — one public and one with extra fields — and select between them at runtime based on **scopes**.

## What a schema is

- A **set of SDL files** (`*.graphqls`) discovered by Viaduct under a package prefix.
- A **schema ID** that names a concrete slice of the overall SDL (for example, public vs. public-with-extras).
- A **scope configuration** that controls which requests can see each slice.

The Star Wars demo defines two schema IDs:

- `PUBLIC_SCHEMA` — the default public surface.
- `PUBLIC_SCHEMA_WITH_EXTRAS` — includes everything from `PUBLIC_SCHEMA` plus extra fields marked with `@scope(to: ["extras"])`.

## Where schemas are registered

Schemas are registered in configuration, providing **scope bindings** and **SDL discovery** settings (package prefix
and resource regex). Example (excerpt adapted from `ViaductConfiguration.kt`):

{{< codetag path="demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductConfiguration.kt" tag="schema_registration" >}}

- `packagePrefix`: where Viaduct scans for generated types/resolvers.
- `resourcesIncluded`: which SDL files to include.
- `scopes`: which runtime scopes activate each schema ID.

## Organizing SDL files

Place your GraphQL SDL files under the configured resources path so they are included by `resourcesIncluded`. Keep
entities modular (for example, `character.graphqls`, `film.graphqls`, `species.graphqls`) and use **directives** like
`@scope`, `@idOf`, and `@oneOf` where appropriate.

### Example (fragment)

{{< codetag path="demoapps/starwars/modules/universe/src/main/viaduct/schema/Planet.graphqls" tag="schemas_example" land="graphql" >}}


> Fields like `film` are typically resolved by **field/batch resolvers**, not embedded in SDL logic.

## Evolving the schema

To add a field that should exist **only** in the extras slice:

1. Declare it in SDL with a scope:

{{< codetag path="demoapps/starwars/modules/universe/src/main/viaduct/schema/Species.graphqls" tag="schemas_extras_example" land="graphql" >}}

2. Ensure the **schema ID** associated with `extras` is registered (`PUBLIC_SCHEMA_WITH_EXTRAS`).
3. The controller will route requests that include the extras scope to that schema ID.

To add a new entity:
- Define the type and relationships in SDL.
- Implement node/field (and batch if needed) resolvers.
- Emit typed IDs with `ctx.globalIDFor(Type.Reflection, internalId)`.
- Write integration tests that hit both schema IDs if visibility differs.

## Testing and troubleshooting

- Validate that the expected fields appear in **introspection** under each schema ID.
- Run end-to-end queries for both public and extras slices.
- Confirm that resolvers are wired and **batched** correctly when many parents are selected.
- Log the chosen `schemaId` and active scopes for each request.

## Do and don’t

- **Do** register schema IDs with clear scope sets (public vs. public + extras).
- **Do** keep SDL modular and use directives for visibility and type-safety.
- **Don’t** rely on a single “mega schema” and conditional logic inside resolvers to hide fields.
- **Don’t** mix raw IDs with Global IDs; declare `@idOf` where applicable.

{{< prevnext >}}
