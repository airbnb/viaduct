# Viaduct Schema Scoping Model

This module holds the model for an application's **scope definitions**: the scope universe and the
scoped schemas declared in `src/main/viaduct/scopes.yaml`, the rules that validate them, and the
manifest (`META-INF/viaduct/schema-scoping.json`) that carries them from the build to the runtime.

Contents of `viaduct.scoping`:

- `SchemaScoping` — the declared universe and scoped schemas.
- `SchemaScopeDefinitions` — decodes a parsed `scopes.yaml` or manifest into `SchemaScoping`.
- `SchemaScopingValidator` and `ScopingErrorCodes` — the declaration rules and their stable error codes.
- `SchemaScopingManifest` — the manifest wire format, with `encode`, `parse`, and `load`.

## Why this is its own module

The model has two kinds of handler. The Gradle plugins *produce* it: they read `scopes.yaml`, validate
it, and write the manifest. The service runtime *consumes* it: `SchemaScopeInfo.fromResources` and
`SchemaConfiguration` read the manifest back. Neither belongs in the application API, and a producer
needs nothing from the runtime beyond the shape of the data.

So, like `shared/bootstrap`, this is a **very small dependency** that the Gradle plugins can bundle
directly. It depends on Jackson and on `shared/apiannotations`, and nothing else.
