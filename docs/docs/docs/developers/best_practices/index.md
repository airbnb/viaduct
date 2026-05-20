---
title: Best Practices
description: A consolidated reference of resolver, identity, batching, fragment, and scope guidance.
---

This page collects the recurring "do" and "don't" guidance from across the tutorial sections into one reference. The deep explanations stay in the tutorials; this page is for skimming when you want a quick sanity check before writing or reviewing a resolver.

## Resolver responsibility

- **Do** keep node resolvers tiny: _lookup → build → return_. Load a single entity by its internal ID and attach a Global ID via `ctx.globalIDFor(<Type>.Reflection, internalId)`. See [Node Resolvers](../../../getting_started/starwars/core/node_resolvers.md).
- **Do** put computation, formatting, and lightweight relationship dereferencing in field resolvers. See [Field Resolvers](../../../getting_started/starwars/core/field_resolvers.md).
- **Do** isolate concerns: nodes fetch, fields compute, batch resolvers aggregate. Don't mix loading logic inside field resolvers.
- **Don't** perform per-request joins or heavy business logic inside a node resolver — you'll lose batching opportunities elsewhere.
- **Don't** assume execution order between independent resolvers. Express dependencies through `objectValueFragment`, not by sequencing.

## Nullability and errors

- **Do** return `null` for missing or unknown values when the schema field is nullable. GraphQL treats `null` as an expected outcome and surfaces partial results to clients.
- **Do** match the field's nullability: if the schema field is non-null, ensure your resolver always produces a value.
- **Don't** throw exceptions for "not found" cases. Reserve exceptions for **unexpected** conditions (I/O failures, decoding errors, programming bugs).

## Batching and N+1

- **Do** opt into batching with `@resolver(isBatching: true)` whenever the same field is selected across many parent objects in a single operation. See [Batch Resolvers](../../../getting_started/starwars/core/batch_resolvers.md).
- **Do** deduplicate keys before hitting the data layer, and return results in the **same order** as the input contexts.
- **Do** migrate a single-field resolver to a batch resolver as soon as you notice it executes the same repository call per parent.
- **Don't** call the data layer once per context inside `batchResolve`. The point of batching is one fetch per unique key set.
- **Don't** loop lookups inside a non-batched `resolve` when the query can select many parents.

## Fragments and parent data

- **Do** request only the parent fields your resolver actually uses in `objectValueFragment`. Overly broad fragments increase planning and execution cost.
- **Do** prefer the parent fragment over additional lookups: if the data is already in `ctx.objectValue`, use it.
- **Don't** rely on getters that aren't covered by the fragment — accessing a field that wasn't requested raises `UnsetFieldException`.

## Identity and Global IDs

- **Do** treat Global IDs as opaque at the network boundary. Clients should pass them around without parsing.
- **Do** generate IDs in resolvers via `ctx.globalIDFor(<Type>.Reflection, internalId)` — this keeps them opaque and stable across module or storage backends.
- **Do** annotate `ID` fields and arguments with `@idOf(...)` to bind them to a concrete GraphQL type so resolvers and tooling can handle them type-safely. See [Global IDs](../../../getting_started/starwars/core/global_id.md).
- **Don't** ask clients to base64-decode Global IDs or rely on their internal layout.
- **Don't** embed business logic or access-control information into IDs.

## Scopes

- **Do** define a `default` scope for general-availability fields so requests without an explicit scope still see something useful. See [Scope Directive](../../../getting_started/starwars/directives/scope.md).
- **Do** keep scopes orthogonal — non-overlapping responsibilities reduce confusion and accidental exposure.
- **Do** apply `@scope` explicitly to sensitive fields and pick a single source of truth for which scopes apply to a request (JWT claims, session, request context).
- **Don't** rely on `@scope` alone for authorization. Scopes govern **schema visibility**; complement them with application-level checks for data-level access control.

## Testing

- **Do** test integration flows end-to-end by issuing real GraphQL queries against a configured Viaduct instance. This catches misaligned IDs, missing fragments, and scope-visibility bugs that unit tests miss.
- **Do** add per-scope integration tests when a tenant exposes scoped fields.
- **Don't** lean exclusively on unit tests for resolvers — most resolver bugs only surface during planning or execution.

## See also

- [Node Resolvers](../../../getting_started/starwars/core/node_resolvers.md)
- [Field Resolvers](../../../getting_started/starwars/core/field_resolvers.md)
- [Batch Resolvers](../../../getting_started/starwars/core/batch_resolvers.md)
- [Resolver Integration Patterns](../../../getting_started/starwars/core/resolver_integrations.md)
- [Global IDs](../../../getting_started/starwars/core/global_id.md)
- [Scope Directive](../../../getting_started/starwars/directives/scope.md)
