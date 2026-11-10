# Viaduct plugins and the build process: a shared conceptual model

## Plugin overview

Viaduct's Gradle plugins organize an application's schema, tenant modules, and generated code into a build:

- The **settings plugin** records which project owns each application and which projects are its modules. The service engineer supplies the application's package prefix and each module's suffix; together these determine the module's full tenant package name.
- **Application and module conventions** provide language-neutral schema and manifest transport, plugin SDL contributions, and integrated schema validation. The settings plugin installs them on declared projects before project build scripts run.
- **Tenant API settings plugins** enable capabilities for a particular application. `com.airbnb.viaduct.kotlinapi` installs Kotlin GRT generation on the application; `com.airbnb.viaduct.javaapi` installs Java GRT generation. Enabling generation makes variants available without adding unused GRTs to the classpath.
- The **Kotlin and Java module plugins**, applied by tenant developers, generate resolver bases, connect shared types to compilation, and produce module manifests describing resolvers and other bootstrap information.

The existing application and metamodule plugin IDs remain supported. The application plugin is a compatibility wrapper that enables both language generators. The metamodule plugin delegates to the module conventions helper. Applications may contain separate Kotlin and Java modules, or contain their tenant module in the application project itself.

## Enabling tenant APIs

Declare settings plugins on the settings script's classpath, then enable each capability inside its application declaration:

```kotlin
pluginManagement {
    val viaductVersion: String by settings
    plugins {
        id("com.airbnb.viaduct.settings-gradle-plugin") version viaductVersion
        id("com.airbnb.viaduct.kotlinapi") version viaductVersion
        id("com.airbnb.viaduct.javaapi") version viaductVersion
    }
}
plugins {
    id("com.airbnb.viaduct.settings-gradle-plugin")
    id("com.airbnb.viaduct.kotlinapi") apply false
    id("com.airbnb.viaduct.javaapi") apply false
}
includeViaductApplication {
    usePlugin("com.airbnb.viaduct.kotlinapi")
    usePlugin("com.airbnb.viaduct.javaapi")
    project(":service")
    modulePackagePrefix("example.service")
    includeModule {
        project(":service:tenant")
        modulePackageSuffix("tenant")
    }
}
```

A module applies its chosen tenant API plugin, along with the language and processor plugins it needs. Project plugins are resolved with the consumer's chosen versions:

```kotlin
plugins {
    kotlin("jvm") version "<kotlin-version>"
    id("com.google.devtools.ksp") version "<ksp-version>"
    id("com.airbnb.viaduct.module-gradle-plugin") version "<viaduct-version>"
}
```

The `conventions` artifact contains the settings capabilities, neutral helpers, and application-side generators. It does not carry the legacy application plugin descriptor or tenant module plugins. Keeping those project plugins off the settings classpath preserves versioned legacy plugin requests and lets the Kotlin module plugin see the consumer's Kotlin Gradle plugin.

An application still applies its hosting and JVM plugins and declares its runtime dependencies. The neutral conventions helper does not apply a language or hosting plugin.

## Public contracts and installation

The [public conventions](../common/src/main/kotlin/viaduct/gradle/ViaductConventions.kt) are available as typed extensions:

| Extension type | Providers and values |
| --- | --- |
| `ViaductApplicationConventions` | `modules`, `allSchemaPartitionsIn`, `centralSchema`, `allModuleManifestsIn`, `modulePackagePrefix`, `centralSchemaDirectory`, `pluginSchema`, `pluginFinalSchema`, `schemaValidationPlugins`, `finalSchemaDirectory` |
| `ViaductModuleConventions` | `application`, `centralSchemaIn`, `schemaPartition`, `moduleManifests`, `layout` |

Configuration properties, including `schemaValidationPlugins`, use `NamedDomainObjectProvider<Configuration>`. Plugins configure these providers and contribute task-output providers to their outgoing artifacts. They do not inspect another project's mutable model or query topology internals.

`pluginSchema` and `pluginFinalSchema` are `CopySpec` inputs for plugin SDL. The former contributes to the central schema used for code generation; the latter contributes additions to the final schema used at runtime. Both `centralSchemaDirectory` and `finalSchemaDirectory` are `Provider<Directory>` outputs owned by Viaduct. Plugins consume these providers and register their own input files or task outputs instead of modifying assembled directories.

A third-party settings plugin calls `Settings.registerViaductPlugin(id, applicationPlugin, moduleStubPlugin)` synchronously from its `apply` method. The application plugin class is required; the module stub class is optional. `usePlugin(id)` applies the settings plugin and verifies that registration completed before `apply` returned. Repeated identical registrations and repeated use within an application are harmless; conflicting registrations fail with the plugin ID.

After validating all declarations, the settings plugin installs conventions, selected application plugins, and optional module stubs using Gradle's isolated project lifecycle callback. Capabilities are scoped to the applications that enable them. A stub can publish an empty contribution variant so applications can resolve that contribution from every declared module before any module opts into its real plugin.

## Three build stages

A Viaduct application build consists of three logical stages based on artifact dependencies:

```text
module schema partitions + application schema + built-in schema + plugin SDL
                              |
                              v
                    CENTRAL-SCHEMA STAGE
             validated central schema + GRT variants
                       |                  |
                       v                  |
                  MODULE STAGE            |
            compiled code + manifests     |
                       |                  |
                       +---------+--------+
                                 |
                                 v
                 APPLICATION ASSEMBLY STAGE <-- plugin final SDL
      manifest index + final-schema resources + runtime libraries
```

Gradle represents these dependencies through configurations and task-output providers. A producer exposes an artifact through a consumable configuration. A consumer declares a dependency and selects variants through a resolvable configuration. Providers carry the dependencies on producing tasks.

### 1. Central-schema stage

This stage combines module schema partitions, application-owned schema, Viaduct built-in definitions, and plugin SDL, then validates the result. Application-side plugins contribute SDL through `pluginSchema`; module-extension plugins can also supply SDL through the existing schema-contribution API. The central schema is a directory of SDL files preserving their source provenance so downstream tooling can identify tenant ownership and the plugin supplying a declaration.

The application conventions populate `modules` from settings declarations and resolve their schema partitions through `allSchemaPartitionsIn`. Module conventions expose `schemaPartition` and resolve the owning application's `centralSchema` through `centralSchemaIn`. Schema preparation runs before module compilation and must not depend on generated resolver bases or compiled module code.

Schema and manifest metadata use the public `ViaductArtifacts.KIND` attribute (`viaduct.kind`) and `Usage=viaduct-contribution`. Each incoming metadata configuration is non-transitive:

| Artifact | `viaduct.kind` |
| --- | --- |
| Module schema partition | `schema-partition` |
| Central schema directory | `central-schema` |
| Module manifest directory | `module-manifest` |
| Schema resource JAR | `schema-resources` |

Language generators consume the central schema and expose GRT JARs using ordinary JVM `java-api` and `java-runtime` variants with capabilities `com.airbnb.viaduct:kotlin-grt` and `com.airbnb.viaduct:java-grt`. Module plugins copy the public owning-application dependency and request their language capability. A module's `implementation` inherits that declaration, making the same GRT library available at compile time and runtime.

GRT JARs contain classes only. Application assembly packages the final schema in a separate resource JAR.

#### Schema provenance and ownership

Schema paths identify both ownership and origin:

| Contributor | Source SDL path |
| --- | --- |
| Tenant module | `schema/module/<module-id>/source/<relative-path>` |
| Application base schema | `schema/application/source/schemabase/<relative-path>` |
| Application common schema | `schema/application/source/common/<relative-path>` |
| Plugin | `schema/plugin/<plugin-id>/source/<relative-path>` |

The module ID is the configured module package suffix, with dots retained as literal characters. An empty suffix identifies the sole module by the application package prefix; settings reject a blank prefix and reject an empty suffix alongside sibling modules. Shared package-to-module identity is used by schema provenance, resolver generation, and manifest assembly. Generated SDL replaces `source` with `generated/<generator-plugin-id>` under the same owner; for example, `schema/module/<module-id>/generated/<generator-plugin-id>/<relative-path>` or `schema/plugin/<plugin-id>/generated/<generator-plugin-id>/<relative-path>`. This distinguishes the tenant that owns a declaration from the plugin that generates it. Viaduct built-in SDL uses the plugin identity `com.airbnb.viaduct`.

The `pluginSchema` and `pluginFinalSchema` copy specifications supply destinations beneath `schema/plugin/`, starting with `<plugin-id>/source/` or `<plugin-id>/generated/<generator-plugin-id>/`. Staging preserves contributed SDL bytes and relative file paths, rejects destination collisions, and removes stale staged files. The same provenance paths survive central and final assembly and appear in validation diagnostics.

#### Restricted plugin scalars

A plugin can define a scalar for use in directive arguments and field arguments declared by that same plugin. This restriction follows the supplying plugin's provenance, including for generated SDL, and applies through list and non-null wrappers. Such a scalar cannot type a field's return value, an input-object field, or an argument declared by a tenant or another plugin. A tenant can still apply the supplying plugin's directives and provide values for those arguments.

Plugin scalars use a fixed JSON value representation: Java `Object` or Kotlin `Any`, with list and nullability wrappers preserved. Runtime coercion handles JSON primitives, objects, arrays, and nested nulls. These scalars do not introduce a general scalar-to-JVM-type mapping SPI; the plugin remains responsible for checks specific to its scalar's values.

### 2. Module stage

The Kotlin and Java module plugins generate resolver bases and extract resolver descriptors through Kotlin Symbol Processing (KSP) or Java annotation processing. Manifest assembly uses the central schema to validate resolver selections, including required selection sets, and determine tenant-local field ownership.

Each assembled module manifest is a JSON resource under `META-INF/viaduct/modules/`. The language plugin contributes its generated directory through `moduleManifests` and adds it to the module's resources source set. The module JAR carries the manifest alongside compiled tenant code.

Generated resolver-base directories are added to the appropriate source sets. Task-output providers connect schema assembly, generation, compilation, descriptor extraction, and manifest assembly in dependency order. See [the KSP pipeline](../../impldocs/execution-registry-ksp-pipeline.md) for descriptor extraction and stale-output handling.

### 3. Application assembly stage

This stage assembles the final schema as well as the runtime artifacts. A plugin can derive additional SDL from `centralSchemaDirectory` and contribute its output through `pluginFinalSchema`. `assembleViaductFinalSchema` combines those additions with the unchanged central SDL, validates the complete result, and exposes it through `finalSchemaDirectory`. Final additions do not feed back into central-schema assembly, GRT generation, resolver-base generation, or module manifest assembly. They extend the schema packaged for runtime without introducing a schema-generation cycle.

The neutral `generateViaductSchemaResources` task packages the final contributed SDL, its resource index, and the schema scoping manifest together in a separate JAR; Viaduct supplies built-in definitions at runtime. The application's `runtimeOnly` includes that JAR, so Java-only applications do not require Kotlin GRTs to load schema resources. Module conventions select the same JAR for test and test-fixture runtimes without depending on application classes.

The application's `allModuleManifestsIn` selects explicit manifest contributions. `generateViaductExecutionRegistryResourceIndex` lists their resource paths and rejects duplicate paths and duplicate `(tenantName, apiName)` identities. The resulting index becomes an application resource; the manifests remain in module JARs. Index generation does not scan the application's runtime dependency JARs to discover contributions.

The application's `runtimeOnly` inherits its declared module dependencies. Each module exposes artifactless metadata identifying its required GRT languages. The application resolves that metadata non-transitively and combines it with any same-project module requirements to connect only the required local GRT task outputs to `api`. Application compilation therefore does not resolve module runtime dependencies or require compiled tenant code. Enabling an unused tenant API does not add its GRTs to either classpath.

The service engineer chooses the hosting framework, entry point, and distribution plugins that assemble the runnable service. See [bootstrap identity](../../impldocs/execution-registry-bootstrap.md) for runtime manifest loading.

## Schema validation

Central and final assembly both run structural schema checks, built-in Viaduct rules, plugin rules, and validation of declared scoped schemas. Validation is part of each assembly task, so consumers of that task's schema output depend on its successful validation. Final assembly revalidates the combined schema and attributes failures introduced by final additions to the contributing plugins, retaining central source locations as context.

Plugins add validation rule libraries to `schemaValidationPlugins`. Viaduct resolves those libraries and their transitive runtime dependencies for both assembly tasks and discovers providers through `META-INF/services/viaduct.gradle.spi.ValidationRuleProvider`. This is an experimental SPI: `ValidationRuleProvider.createRuleSet(schema)` returns the top-level `viaduct.gradle.spi.ViaductRuleSet`. A rule set supplies rules and can use the shared validation context or supply its own context for a separate traversal. Rule IDs must be nonblank and unique across built-in and plugin rules; invalid provider contracts fail the build with the provider's identity.

`validateViaductSchema` aggregates central and final assembly, and `check` depends on it. Plugins can attach standalone checks to this aggregate, but that does not make those checks dependencies of every schema consumer. Checks that gate schema consumption belong in the integrated validation SPI or require explicit task dependencies.

## Stage boundaries and the development loop

For an application that also contains tenant code in the same project, the conventions connect local schema, GRT, and manifest outputs through task-output providers instead of self-referential project dependencies.

The central- and final-schema assembly tasks declare their inputs and outputs and are cacheable. Gradle can skip them when inputs and outputs are unchanged, or restore a matching result from the build cache. When either executes, it assembles and validates its entire schema, even if only one input changed.

Editing only a resolver implementation does not rerun schema assembly unless a plugin derives changed SDL from that source. Changes to the central schema or GRTs can invalidate downstream generation and compilation in several modules.

Changes confined to final-schema inputs invalidate final assembly and runtime schema packaging without changing the central schema used by module generators. Plugins deriving final SDL from the central schema depend on the central output and contribute their own task-output providers, keeping this flow one-way.

The convention and registration contracts support plugin SDL, restricted plugin scalars, integrated validation, and final-schema assembly. Federation semantics and executable federation runtime behavior remain separate work built on these mechanisms.
