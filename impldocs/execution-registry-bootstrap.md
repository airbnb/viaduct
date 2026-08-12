# Execution Registry: Bootstrap Identity

This document is the canonical description of *how a registry configuration is identified* during
execution-registry bootstrapping: what uniquely names a configuration, what that means for the
inputs to a dispatcher-registry build, and how a host application replaces configurations without
dropping the ones it did not regenerate.

It spans Kotlin (KSP) generation, Java generation, engine bootstrapping, and the source-overlay
protocol a host application uses to replace configs, which is why it lives here rather than in
[`execution-registry-ksp-pipeline.md`](execution-registry-ksp-pipeline.md) — that document is
deliberately scoped to KSP production and incrementality.

## The configuration key

A configuration is identified by the pair of the tenant module that owns it and the tenant API
implementation that produced it:

```text
ConfigKey = <tenantName, apiName>
```

Examples:

```text
<data/demo-todo, kotlin>
<data/demo-todo, java>
<data/payments, acme-dsl>
```

At any point when we build a dispatcher registry, the input configurations form a finite *map*,
not a list with accidental duplicates:

```text
ExecutionRegistryConfigs: Map<ConfigKey, ExecutionRegistryConfigFile>
```

There is at most one `ExecutionRegistryConfigFile` per `<tenantName, apiName>` pair in a single
registry build. A tenant may contribute several configurations when its tenant API implementations
are compatible, but those configurations have different keys — for example a tenant with both
resolvers from two compatible tenant APIs:

```text
<data/demo-todo, kotlin>   -> kotlin config
<data/demo-todo, acme-dsl> -> acme-dsl config
```

Not every pair of tenant APIs is compatible. That compatibility constraint is separate from key
uniqueness: the key model permits different API names for one tenant, while build-time validation
determines which combinations are legal.

The full dispatcher registry is the validated union of the executors produced from every
configuration in the map:

```text
DispatcherRegistry = validatedUnion(
  materialize(config) for config in ExecutionRegistryConfigs.values
)
```

`materialize(config)` uses `config.executorFactory` to interpret the config's entries and construct
executors. The factory therefore belongs to the **value** side of the map. It does not identify the
map entry.

## Why `apiName` and not the executor factory

There is today a de facto one-to-one mapping between API names and factory classes:

| `apiName` | Current executor factory       |
| --------- | ------------------------------ |
| `kotlin`  | `ViaductModernExecutorFactory` |
| `java`    | `ViaductJavaExecutorFactory`   |

No engine contract requires that mapping to stay one-to-one. `ExecutorFactory` does not expose API
identity, and an API implementation may rename, replace, or split its factory without becoming a
different API. Conversely, the execution-registry model also supports factories for synthetic
built-ins, so not every factory class is itself a tenant API identity.

This matters precisely when an API implementation changes its factory class without changing its API
identity: the new configuration must *replace* the old configuration for the same key. Keying by the
factory would instead preserve both and incorrectly attempt to materialize both.

Using the JVM `Class` rather than the FQN string would add classloading identity to the problem while
retaining the same conceptual coupling. The serialized metadata already carries an FQN because the
engine needs to instantiate the implementation; it separately carries the stable semantic name used
to identify its configuration slot.

`apiName` is an **open string** in the wire format rather than a closed engine enum. The
engine declares exactly one name in Kotlin — `KOTLIN_API_NAME`, its default — because engine code has
to recognize its own default API at runtime. Every other API declares its own name in its own module,
including the Java API (in its Bazel macro) and any built outside the engine. Synthetic built-ins carry
explicit names appropriate to their producer (e.g. `builtin_query_node`) rather than deriving identity
from a factory FQN.

Open does not mean arbitrary: a name must be a **valid Java identifier**. It travels through build-tool
arguments, diagnostics, and generated artifacts, so identifier syntax keeps it usable as a bare token
everywhere and keeps names comparable without normalization rules. `TenantModuleConfigAssembler`
rejects anything else at assembly time; at runtime `ModuleConfigSource.from` requires only a nonblank
name, so configs produced outside this engine's build tooling are not held to the stricter syntax when
they are read.

## `ModuleConfigSource` semantics

A `ModuleConfigSource` is a source of exactly one `ExecutionRegistryConfigFile` for exactly one
`<tenantName, apiName>` pair:

```text
ModuleConfigSources: Map<ConfigKey, ModuleConfigSource>

read(ModuleConfigSources[key]) = ExecutionRegistryConfigs[key]
```

It owns three pieces of information:

- `tenantName` — the tenant-module half of the key.
- `apiName` — the tenant-API half of the key.
- `source` — the physical mechanism for opening the current serialized configuration.

Both key fields come from the underlying `ExecutionRegistryConfigFile`. `ModuleConfigSource.from`
reads enough metadata to require and extract them, and the primary constructor is private so a
source can never be paired with a key that disagrees with the JSON it contains. Both fields are
nullable on the Jackson data class for wire compatibility, but a source that omits either — or leaves
`apiName` blank — is rejected with a clear error.

The source does **not** own `executorFactory`. The engine reads that field from the full
configuration when it materializes executors.

The word "source" is load-bearing. A classpath URL and a hotswap filesystem file may be different
source objects for the same logical `<tenantName, apiName>` entry. Their object identity, path,
content, and executor-factory FQN are not the configuration key.

## Build-time uniqueness

For an ordinary registry build the source collection must also be a finite map: there can be only one
`ModuleConfigSource`, and consequently only one `ExecutionRegistryConfigFile`, per key. Duplicate
keys are malformed build inputs. They are rejected at the earliest practical boundary
(`ModuleConfigSource.requireUniqueKeys`, called from both `ExecutionRegistryConfigSourceCollector`
and the hotswap overlay) rather than resolved through list order or `associateBy` last-write-wins
behavior.

Note that this replaced a previous last-write-wins collapse, so duplicated or shadowed tenant jars on
a classpath now fail fast at startup instead of silently registering whichever config the scan
happened to visit last.

This invariant is independent of resolver-coordinate validation. Two *different* API configurations
may still attempt to register conflicting fields; the existing dispatcher-registry validation remains
responsible for those conflicts.

### Three distinct concerns

These are easy to conflate and are enforced in different places:

| Concern | Question it answers | Where enforced |
|---|---|---|
| API compatibility | May this tenant use both of these tenant APIs at once? | build-time validation |
| Key uniqueness | Is there exactly one config per `<tenantName, apiName>`? | `ModuleConfigSource.requireUniqueKeys` |
| Coordinate conflicts | Do two configs try to register the same field/node? | dispatcher-registry validation |

## Injector scope is deliberately coarser than the key

`ModuleConfigBootstrapper` groups parsed configs by `tenantName` **alone** — not by
`<tenantName, apiName>` — to create one `CodeInjector` per tenant module and to detect conflicting
`bootstrapClass` declarations across a tenant's configs.

This is intentional and must not be "corrected" to match the configuration key. The
`TenantModuleInjectorFactory` SPI contract is to bootstrap each tenant exactly once; re-keying it by
`<tenantName, apiName>` would hand a tenant's per-API configs one separate injector each and change
behavior. Injector scope and bootstrap-class agreement are tenant-module concerns; the
configuration key is a per-config concern.

## Source replacement is a right-biased overlay

A host application that regenerates configs at runtime (Airbnb's code hotswap is one; see
`common/viaduct/tenant/runtime/impldocs/execution-registry-airbnb.md`) temporarily has two source
maps:

```text
baseSources:    Map<ConfigKey, ModuleConfigSource>   // startup classpath baseline
hotswapSources: Map<ConfigKey, ModuleConfigSource>   // regenerated on the filesystem
```

The inputs for the updated registry are a right-biased overlay:

```text
overlay(base, updates)[key] =
  updates[key]  if updates contains key
  base[key]     otherwise
```

For example:

```text
base:
  <data/demo-todo, kotlin>   -> M0
  <data/demo-todo, acme-dsl> -> C0

hotswap:
  <data/demo-todo, kotlin>   -> M1

result:
  <data/demo-todo, kotlin>   -> M1
  <data/demo-todo, acme-dsl> -> C0
```

The result is then used to rebuild the **complete** dispatcher registry. We do not mutate an existing
registry entry-by-entry, and we do not merge the old and new JSON contents of a single config. We
replace one complete source for a key and rebuild from the resulting complete source map.

This is why `executorFactory` is the wrong merge discriminator. If `M0` names factory class `F0` and
`M1` names a replacement class `F1`, the key is still `<data/demo-todo, kotlin>`. Keying by the
factory would preserve both configurations and incorrectly attempt to materialize both.

### The overlay cannot express deletion

An absent hotswap key means "retain the base source", not "remove it". Supporting deletion would
require a tombstone or a different snapshot protocol; neither exists today. Do not read absence as
removal.

### Deployment sequencing

Enforcement of a nonblank `apiName` lives in `ModuleConfigSource.from`, which runs against the running
process's classpath baseline as well as any regenerated files. A process started from a build that
predates `apiName` has baseline configs without the field, so overlaying newer sources onto it fails
when the baseline is read.

Overlaying onto an already-running older process is therefore not supported: restart it on a build that
emits `apiName`, then overlay as usual.

## Where API names are produced

Every generated config carries an explicit API identity:

- **Kotlin** — `TenantModuleConfigAssembler` via `--api-name`; `kotlin`. Production tenants
  get this from the `macros_viaduct_tenant_internal.bzl` macro, which sets `api_name = "kotlin"`
  centrally; the Gradle module plugin sets `AssembleTenantModuleConfigFileTask.API_NAME`.
- **Java** — the same assembler with `api_name = "java"` (Bazel) or `JAVA_API_NAME` (Gradle
  test-support wiring).
- **Tenant APIs outside this engine** — declare their own name and emit their own sibling
  `<pkg>.<api>.json` resource, without going through the assembler.
- **Synthetic built-ins** — `buildBuiltinModuleConfigSource` takes an explicit `apiName`; the
  `Query.node` and `@namespaceType` producers pass their own stable names.

### The Bazel `api_name` default

The `api_name` attr on `assemble_tenant_module_config` defaults to `kotlin`, mirroring how
`executor_factory` defaults to the Kotlin factory.

`api_name` and `executor_factory` are never chosen independently — a Java tenant needs both the Java
factory and the `java` slot — so callers pick an API rather than setting two knobs:

- **Kotlin** — the tenant macro (`macros_viaduct_tenant_internal.bzl`) supplies both, so
  production tenants never write either attr. Direct callers (test and tutorial BUILD files) ride the
  `kotlin` default.
- **Java** — the `assemble_java_tenant_module_config` macro sets both from one decision.

This makes the hazard structural rather than documentary: a caller cannot express "Java factory,
kotlin slot" through either macro. Setting the two attrs by hand on the underlying rule still can, so
prefer the macros. Note that this does *not* introduce a factory-FQN-to-API-name mapping — both values
derive from a single caller-declared API, never one from the other.
