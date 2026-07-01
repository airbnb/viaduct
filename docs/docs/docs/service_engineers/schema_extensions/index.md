---
title: Schema Extensions
description: Defining application-wide custom directives and common types using the schemabase directory
---

Viaduct provides a set of directives and built-in types that go beyond what's defined in the GraphQL specification (see the [Developers: Schema Reference](../../developers/schema_reference/index.md)). You can define **custom directives** and **common types** that are shared across all modules by placing GraphQL schema files in a centralized location. (Viaduct does not yet support custom scalars.)


## The schemabase directory

The Viaduct [application plugin](../../../getting_started/index.md) automatically discovers and includes schema files from:

```
src/main/viaduct/schemabase/
```

Any `.graphqls` files in this directory (including subdirectories) are automatically added to your application's schema during the build process.

## Validation

Run the `validateViaductSchemaExtensions` task to validate your `schemabase/` and `src/viaduct/schema/` files in isolation:

```bash
./gradlew validateViaductSchemaExtensions
```

This task applies all standard Viaduct schema rules to your extension files alone, without requiring module partitions to be present. Use it as a fast feedback loop when authoring directives, types, or PageInfo in schemabase.

To validate your extensions as part of a full application build, use the `assembleViaductCentralSchema` Gradle task:

```bash
./gradlew assembleViaductCentralSchema
```

## PageInfo

Viaduct automatically defines a `PageInfo` type for [Relay Connection](https://relay.dev/graphql/connections.htm){:target="_blank"} pagination:

```graphqls
type PageInfo {
  hasNextPage: Boolean!
  hasPreviousPage: Boolean!
  startCursor: String
  endCursor: String
}
```

For how `PageInfo` is used in connection types, see [Pagination](../../developers/pagination/index.md).

You can replace this definition with your own, where you can add directives that make sense for your application. However, your definition of `PageInfo` must be in `schemabase/` and must conform to the following restrictions:

- **Required fields**: The four standard fields (`hasNextPage`, `hasPreviousPage`, `startCursor`, `endCursor`) must be defined with the standard types.
- **No custom fields**: Additional fields such as `totalCount` are not permitted.
- **No interfaces (or unions)**: `PageInfo` can't implement an interface. (Separately we validate that it's never a member of a union.)
- **No arguments**: Custom `PageInfo` definitions can't add arguments to its fields.

These restrictions are based on the spec itself and on best practices established by the Relay community.

What you can do with a custom definition is add directives to the type itself and to its fields. (In the future, when Viaduct supports custom scalars, it will support custom cursor types as well.)

<!-- References: All of the above restrictions came from having a couple of agents "deep search" the web. People don't like restrictions, but the goal of Viaduct is to be opinionated, and in picking opinions, where there's doubt we pick opinions that can be undone without breaking compatibility. The no-custom-fields restriction might be controversial, so some more details. First, the spec is deliberately asymmetric: it explicitly allows for custom fields on `Connection` but, while it doesn't explicitly disallow them on `PageInfo`, it doesn't allow for them either. Second, the reference impl and all major additional implementations have no custom fields. Third, every time a PR is posted to the reference impl to add `totalCount` specifically, it is rejected with instruction to put it on `Connection` instead. This comment is the only natural place to put our rationale, so do not delete it. -->

## Input and enum types in schemabase

Directives and output types defined in `schemabase/` can be referenced by all module partitions. However, **input types and enum types defined in `schemabase/` are closed**: module partitions cannot extend them with `extend input` or `extend enum`.

This restriction exists because input extensions are merged at schema-assembly time and the order is undefined — allowing modules to extend schemabase inputs would produce non-deterministic schemas. If you need a schemabase input type to grow, add the new fields directly in `schemabase/`.

## See also

- [Developers: Schema Reference](../../developers/schema_reference/index.md) — Viaduct's built-in schema components
- [Developers: Resolvers](../../developers/resolvers/index.md) — Implementing resolvers for your schema
- [Star Wars: Custom Directives](../../../getting_started/starwars/directives/index.md) — Examples of using Viaduct's built-in directives
