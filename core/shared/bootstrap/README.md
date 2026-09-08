# Viaduct Bootstrap Data Model

This module holds the data model for Viaduct's **bootstrap data**: the module-index JSON that build
tooling emits per tenant module (`META-INF/viaduct/modules/<tenantpkg>.json`) and that the engine
reads when it bootstraps a dispatcher registry.

Contents of `viaduct.bootstrap`:

- `ExecutionRegistryConfigFile` and its entry types (`NodeEntryConfig`, `FieldEntryConfig`,
  `SelectionsBlockConfig`, `VariableProviderEntryConfig`, `ProviderVariablesAPIData`) — the wire
  format, plus `parse`/`toJson`.
- `ConfigKey` — the `<tenantName, apiName>` identity of one configuration.
- `KOTLIN_API_NAME` — the `apiName` wire value for the tenant API this engine ships.

The identity model and the bootstrap protocol are documented in
[`impldocs/execution-registry-bootstrap.md`](../../../impldocs/execution-registry-bootstrap.md).

## Why this is its own module

The point of this module is to be a **very small dependency**: one that we are comfortable importing
into any context, including directly into the Gradle plugins where that is desirable. It depends on
Jackson and on `shared/apiannotations` (marker annotations, itself dependency-free) — no engine, no
graphql-java, no Guice, nothing else.

Bootstrap data has two very different kinds of handler. The engine *consumes* it at runtime and
legitimately sits on top of the whole stack. Build tooling *produces* it, and a producer needs
nothing from the engine beyond the shape of the file it has to write. When the model lived in
`engine/api`, every producer had to choose between depending on the engine — and everything the
engine drags in — or hand-copying the format. The `apiName` values are the clearest symptom: the
same literals are duplicated in `gradle-plugins/common`, in `build-logic`'s test support, and as an
attr default in the Bazel rule, because none of those places should be putting the engine on a build
classpath to read one constant. Anything in this module is importable from all of them; pointing
those copies here is a follow-up, not something this module's introduction did on its own.

## Where this is going

Longer term this library is intended to be the basis for letting **other build systems** create the
bootstrap data Viaduct needs, more easily and more reliably than by reproducing the JSON by hand.

The bootstrap file — not KSP, not Gradle, not Bazel — is the real contract between "whatever built
this tenant module" and the engine that runs it. A new producer (a Maven plugin, a different Bazel
rule, an in-house generator, a tenant API implemented in another language's toolchain) should be
able to depend on this one small artifact, build an `ExecutionRegistryConfigFile`, serialize it with
`toJson`, and package the result — with the compiler checking the shape and the field names, and
with `apiName`/`ConfigKey` semantics coming from the same source the engine uses. Format changes
then land in one place instead of in each producer's string-building code.

## What belongs here

Keep this module to the *description* of bootstrap data and its identity. Rule of thumb: if it needs
the engine, a GraphQL schema, or a live registry to be meaningful, it does not belong here.

Deliberately left in `engine/api` under `viaduct.engine.api.bootstrap.executionregistry`:

- `ModuleConfigSource` — pairs a `ConfigKey` with an engine-side `InputStreamSource`.
- `ModuleConfigFactory` — runtime SPI for configs synthesized in-process rather than read from a
  resource.
- `RequiredSelectionSetSupport` — decodes the variable model into engine `SelectionSetVariable`
  types.

Please do not add dependencies to this module. Adding a Viaduct module or graphql-java here would
take away the property that makes it useful, since a producer would inherit them too.
