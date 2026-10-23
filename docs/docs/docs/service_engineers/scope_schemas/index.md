---
title: Scope Schemas
description: Declaring the scope IDs and scoped schemas an application can build in scopes.yaml, and choosing which to serve at runtime.
---

A scoped schema is an executable view of the central schema, projected onto a set of scope IDs. It contains the fields visible to at least one of those scopes, excludes tenant-local fields, and has a schema ID that requests select through `SchemaId`. One Viaduct application can serve several scoped schemas from the same central schema, for example a smaller public view beside a larger internal one.

Tenant developers mark visibility with the `@scope` directive; see [Developers: Scopes](../../developers/scopes/index.md). This page is for the service engineer who decides which scope IDs exist and which scoped schemas can be built from the schema. Both are recorded as scope definitions in `scopes.yaml`. Which of those a deployment serves is chosen at runtime with `withScopedSchemas`.

## Where `scopes.yaml` lives

Put the file at `src/main/viaduct/scopes.yaml` in the application project, the project that applies the Viaduct application plugin. A `scopes.yaml` in a tenant project is not read, and an application has at most one.

Without the file, or with a file that declares neither `schemaScopes` nor `scopedSchemas`, the build packages no scope definitions and does not check `@scope` names against a declared universe. The runtime then infers the valid scope IDs from the scope directives the schema contains, and `SchemaScopeInfo.fromResources` throws.

## Syntax

The Star Wars demo declares two scope IDs and two scoped schemas:

{{ codefile("demoapps/starwars/src/main/viaduct/scopes.yaml") }}

- `version` is required in a non-empty file and must be `1`.
- `schemaScopes` is the scope universe: every scope ID that `@scope(to: [...])` may name. It must not be empty or repeat an ID. A scope ID starts with a letter, continues with letters, digits, underscores, or hyphens, and may carry one `:`-separated namespace segment, such as `viaduct:public`. `*` is the SDL wildcard for "all scopes" and cannot be declared.
- `scopedSchemas` is optional and requires `schemaScopes`. Each entry has an `id` and a non-empty list of `scopes`, all of which must appear in `schemaScopes`. An `id` starts with a letter and continues with letters, digits, or underscores. `BASE` and `NONE` are reserved.
- Unknown keys are rejected at any level.

A violation fails the build with a stable code in brackets, for example `[SCOPED_SCHEMA_UNKNOWN_SCOPE] Scoped schema 'publicSchema' references scope id(s) not declared under schemaScopes: [internal]. Declared scopes: [default, extras].`

## What the build checks

Declaring `schemaScopes` turns on the scope consistency rules, which are off without it; see [Developers: Scopes](../../developers/scopes/index.md#validation). For example, a type extension that adds scoped fields must declare its own `@scope` and cannot widen the scopes of the type it extends, and a type must have at least one field in every scope it declares.

The build then projects the schema onto the whole universe and onto each declared scoped schema. Building a view is what checks every `@scope(to: [...])` name against `schemaScopes`, and the build fails if any view is not a valid, introspectable GraphQL schema.

## Reading the declared scoped schemas at runtime

The build packages the checked scope definitions with the application's generated code. {{ kdoc("viaduct.service.SchemaScopeInfo") }} reads them back from the classpath, so the running service selects from what the build validated instead of restating scope IDs in code:

- `SchemaScopeInfo.fromResources()` returns every declared scoped schema, ordered by id.
- `SchemaScopeInfo.fromResources(ids)` returns the ones named by `ids`, and fails with `IllegalArgumentException` naming any id that is not declared.

Both overloads throw `IllegalStateException` when no scope definitions are on the classpath, which is the state of an application without `scopes.yaml`, and when two applications contribute them to one classpath. The unscoped view, `SchemaScopeInfo.Base`, is never declared; add it yourself to serve it. A deployment that serves one declared scoped schema beside the unscoped view registers:

```kotlin
SchemaScopeInfo.fromResources(setOf("publicSchema")) + SchemaScopeInfo.Base
```

Read the definitions where your service builds its objects, not in a static initializer. A failed read in a static initializer is reported as `ExceptionInInitializerError` and, on every later access, as `NoClassDefFoundError`; read in a factory, it reports its own message. The Star Wars demo selects the two scoped schemas its controller routes between in a Micronaut factory:

{{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductConfiguration.kt", "declared_schemas") }}

## Registering scoped schemas with `withScopedSchemas`

`ViaductBuilder.withScopedSchemas` registers exactly the views it is given, and the list must not be empty. The Star Wars demo serves every declared scoped schema and no unscoped view:

{{ codetag("demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/ViaductConfiguration.kt", "schema_registration") }}

Requests then select a view by passing its `schemaId` to `Viaduct.execute`. A hand-built `SchemaScopeInfo.Scoped` is also accepted. When scope definitions are on the classpath, it is projected against the declared universe like a declared one. See [Viaduct API: Registering executable schema views](../../developers/viaduct_api/index.md#registering-executable-schema-views) for what each view contains.

Serve each scoped schema on its own route, so introspection on a route describes the schema it executes. The Star Wars demo serves `publicSchema` on `/graphql` and `publicSchemaWithExtras` on `/extras/graphql`.

## Adopting `scopes.yaml`

Adding `scopes.yaml` can fail a schema that builds today. The consistency rules above start to apply, so a type extension that adds scoped fields without its own `@scope` now fails the build. A type used by a directive, such as a directive's input type, must be scoped to the whole universe. Without `scopes.yaml` the universe is inferred from the scopes the schema already uses. Declaring a scope that no type uses yet enlarges it.

For example, a directive whose input type is `@scope(to: ["public"])` builds while the inferred universe is `{public}`. Declaring `schemaScopes: [public, future]` makes the build fail with ``Type ... is used by a GraphQL directive and should not have restricted scopes. Please use scope `*` for this type``. Scope that type with `@scope(to: ["*"])` so it follows the universe as scopes are added.

## See also

- [Developers: Scopes](../../developers/scopes/index.md) — authoring `@scope` and the scope validation rules
- [Viaduct API](../../developers/viaduct_api/index.md) — building a `Viaduct` and selecting a schema per request
- [Multi-tenancy](../multi_tenancy/index.md) — the application and tenant projects
- [Star Wars: Schemas](../../../getting_started/starwars/architecture/schemas.md) — the demo's two scoped schemas end to end
