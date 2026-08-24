---
title: Field Resolvers
description: Implementing field resolvers in Viaduct.
---


Field resolvers compute values for individual fields when a simple property read is not enough. They complement node resolvers by adding business logic, formatting, and light lookups at the **field** level, while keeping entity fetching in the **node** layer.

> This page focuses on **single-field resolvers** (using the default `@resolver`). Batching strategies are covered in [Batch Resolvers](batch_resolvers.md).

## Where field resolvers fit in the execution flow

1. A client query selects fields on an object (for example, `Character.name`, `Character.homeworld`).
2. Viaduct plans execution and invokes resolvers for fields that require logic beyond plain data access.
3. Each resolver receives a typed `Context` with the **parent object** in `ctx.getObjectValue()` and any **arguments** in `ctx.arguments`.
4. The resolver returns a value for the field (or `null`), and execution continues for the rest of the selection set.

## When to use field resolvers

- **Computed fields:** the value is derived from other data (for example, formatting, aggregation, mapping).
- **Cross-entity relationships (lightweight):** dereference an ID already present on the parent and fetch once.
- **Business rules and presentation:** apply domain rules or output formatting.
- **Argument-driven behavior:** vary the result based on resolver arguments.

> Avoid heavy cross-entity fan-out here. If multiple objects need the same relationship, prefer a **batch resolver** so the work is grouped per request.

## Anatomy of a field resolver

A typical resolver extends the generated base class for the field and overrides `resolve`:


{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterDisplayNameResolver.kt", "resolver_example", lang="kotlin") }}


### Access to arguments

Arguments declared in the schema are available via `ctx.arguments` with the appropriate getters:


{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/FilmSummaryResolver.kt", "resolver_example", lang="kotlin") }}


## Examples

### 1) Simple computed value


{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterIsAdultResolver.kt", "resolver_example", lang="kotlin") }}


### 2) Single related lookup (non-batched)

Use for **one-off** relationships where only a few objects are in play. If many parent objects will request the same relationship in a single operation, move this to a batch resolver.


{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/films/resolvers/FilmPlanetsResolver.kt", "resolver_example", lang="kotlin") }}


### 3) Argument-driven behavior

The `format` argument controls the presentation of the returned string.


{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterFormattedDescriptionResolver.kt", "resolver_example", lang="kotlin") }}


## What about heavy lookups?

Field resolvers are intended to be cheap. When a single field genuinely needs an expensive load, push the work out of the resolver:

- **Move it into the data layer** so the cost is bounded by your repository or service.
- **Convert to a batch field resolver** when more than a handful of parents will request the field in one operation — this is almost always the right answer for relationship loads.
- **Delegate to a service** behind a small façade and keep the resolver thin. The resolver becomes a translator between Viaduct's `Context` and your service contract.

If you find yourself doing significant work inside `resolve`, that's a signal to revisit which layer should own the load.

## Error handling and nullability

GraphQL itself dictates most of the rules here, so the resolver-side guidance is short:

- Prefer returning **`null`** for missing or unknown values when the schema field is nullable. See the [GraphQL error spec](https://spec.graphql.org/draft/#sec-Errors) for how clients see partial results.
- Throw exceptions only for **unexpected** conditions (I/O failure, decoding errors).
- Match the field nullability in the schema: if the field is non-null, ensure you always produce a value.

## Do and don’t

- **Do** keep it light: perform inexpensive logic and at most a single lookup.
- **Do** defer relationships: if many parents need the same relationship, implement a **batch field resolver** instead.
- **Do** request only the parent fields you need via `objectValueFragment`, and rely on getters already available on `ctx.getObjectValue()`.
- **Don’t** loop lookups inside `resolve` when the query can select many parents — that's a hidden N+1.
- **Don’t** put heavy business logic or multi-step orchestration inside a field resolver; push it to a service or batch resolver.

> See [Best Practices](../../../docs/developers/best_practices/index.md) for the consolidated reference.

> For the full field-resolver API, generated base-class reference, and advanced patterns, see the [Field Resolvers developer reference](../../../docs/developers/resolvers/field_resolvers.md).
