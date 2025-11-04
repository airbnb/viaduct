---
title: Schema Extensions
description: Defining application-wide custom directives and common types using the schemabase directory
weight: 50
---

Viaduct applications can define **custom directives** and **common types** that are shared across all modules by placing GraphQL schema files in a special directory. This provides a centralized location for schema definitions that extend Viaduct's built-in schema components.

## The schemabase directory

The Viaduct Gradle plugin automatically discovers and includes schema files from:

```
src/main/viaduct/schemabase/
```

Any `.graphqls` files in this directory (including subdirectories) are automatically added to your application's schema during the build process. You do **not** need to manually configure or register these files.

**Note:** Viaduct does not yet support custom scalars.

## Build integration

The `assembleViaductCentralSchema` Gradle task automatically:

1. Scans `src/main/viaduct/schemabase/` for `*.graphqls` files
2. Copies them to the build output under `schemabase/`
3. Includes them when assembling the complete application schema
4. Validates that the combined schema is valid GraphQL

You can verify the assembled schema in `build/viaduct/schema/` after running:

```bash
./gradlew assembleViaductCentralSchema
```

## Relation to Viaduct's built-in schema

Viaduct automatically provides several built-in schema components that you don't need to define:

- **Directives:** `@resolver`, `@scope`, `@idOf`, `@backingData`
- **Interfaces:** `Node` (when used)
- **Scalars:** `DateTime`, `Date`, `Long`, `BigDecimal`, `BigInteger`, `Object`, `Upload`
- **Root types:** `Query` (always), `Mutation` (when extended)

For details about these built-in components, see the [Developers: Schema Reference](../../developers/schema_reference/) section.

Your `schemabase/` files extend and complement these built-in components with application-specific definitions.

## See also

- [Developers: Schema Reference](../../developers/schema_reference/) — Viaduct's built-in schema components
- [Developers: Resolvers](../../developers/resolvers/) — Implementing resolvers for your schema
- [Getting Started: Custom Directives](../../getting_started/starwars/directives/) — Examples of using Viaduct's built-in directives
- [Service Engineers: Multiple Modules](../multiple_modules/) — Organizing large multi-module applications
