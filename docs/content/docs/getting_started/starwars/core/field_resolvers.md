---
title: Field Resolvers
description: Implementing field resolvers in Viaduct.
layout: docs/single
weight: 4
linkTitle: Field Resolvers
---

Field resolvers compute values for individual fields when a simple property read is not enough. They complement
node resolvers by adding business logic, formatting, and light lookups at the **field** level, while keeping
entity fetching in the **node** layer.

> This page focuses on **single-field resolvers**. Batching strategies are covered in `batch_resolvers.md`.

## Where field resolvers fit in the execution flow

1. A client query selects fields on an object (for example, `Character.name`, `Character.homeworld`).
2. Viaduct plans execution and invokes resolvers for fields that require logic beyond plain data access.
3. Each resolver receives a typed `Context` with the **parent object** in `ctx.objectValue` and any **arguments**
   in `ctx.arguments`.
4. The resolver returns a value for the field (or `null`), and execution continues for the rest of the selection set.

## When to use field resolvers

- **Computed fields:** the value is derived from other data (for example, formatting, aggregation, mapping).
- **Cross-entity relationships (lightweight):** dereference an ID already present on the parent and fetch once.
- **Business rules and presentation:** apply domain rules or output formatting.
- **Argument-driven behavior:** vary the result based on resolver arguments.

> Avoid heavy cross-entity fan-out here. If multiple objects need the same relationship, prefer a **batch resolver**
> so the work is grouped per request.

## Anatomy of a field resolver

A typical resolver extends the generated base class for the field and overrides `resolve`:

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterDisplayNameResolver.kt" tag="resolver_example"  >}}

### Access to arguments

Arguments declared in the schema are available via `ctx.arguments` with the appropriate getters:

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/FilmSummaryResolver.kt" tag="resolver_example"  >}}

## Examples

### 1) Simple computed value

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterIsAdultResolver.kt" tag="resolver_example"  >}}

### 2) Single related lookup (non-batched)

Use for **one-off** relationships where only a few objects are in play. If many parent objects will request
the same relationship in a single operation, move this to a batch resolver.

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterHomeworldResolver.kt" tag="resolver_example"  >}}

### 3) Argument-driven formatting

The `limit` argument controls the length of the returned summary.

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/queries/AllCharactersQueryResolver.kt" tag="resolver_example"  >}}

## Error handling and nullability

- Prefer returning **`null`** for missing/unknown values.
- Throw exceptions only for **unexpected** conditions (I/O failure, decoding errors).
- Match the field nullability in the schema: if the field is non-null, ensure you always produce a value.

## Performance and design guidelines

- **Keep it light:** perform inexpensive logic and at most a single lookup.
- **Defer relationships:** if many parents need the same relationship, implement a **batch field resolver** instead.
- **Avoid hidden N+1:** do not loop lookups inside `resolve` when the query can select many parents.
- **Respect fragments:** if you need parent fields, request them via the base resolver’s fragment, or rely on getters
  that are already available on `ctx.objectValue`.

{{< prevnext >}}
