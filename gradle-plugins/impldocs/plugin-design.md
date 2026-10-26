# Viaduct plugins and the build process: a shared conceptual model

## Plugin overview

Viaduct's Gradle plugins organize an application's schema, tenant modules, and generated code into a build:

- The **settings plugin** records which project owns each application and which projects are its modules. The service engineer supplies the application's package prefix and each module's suffix; together these determine the module's full tenant package name.
- **Application and module conventions** provide language-neutral schema and manifest transport. The settings plugin installs them on declared projects before project build scripts run.
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
| `ViaductApplicationConventions` | `modules`, `allSchemaPartitionsIn`, `centralSchema`, `allModuleManifestsIn`, `modulePackagePrefix`, `centralSchemaDirectory` |
| `ViaductModuleConventions` | `application`, `centralSchemaIn`, `schemaPartition`, `moduleManifests`, `layout` |

Configuration properties use `NamedDomainObjectProvider<Configuration>`. Plugins configure these providers and contribute task-output providers to their outgoing artifacts. They do not inspect another project's mutable model or query topology internals.

A third-party settings plugin calls `Settings.registerViaductPlugin(id, applicationPlugin, moduleStubPlugin)` synchronously from its `apply` method. The application plugin class is required; the module stub class is optional. `usePlugin(id)` applies the settings plugin and verifies that registration completed before `apply` returned. Repeated identical registrations and repeated use within an application are harmless; conflicting registrations fail with the plugin ID.

After validating all declarations, the settings plugin installs conventions, selected application plugins, and optional module stubs using Gradle's isolated project lifecycle callback. Capabilities are scoped to the applications that enable them. A stub can publish an empty contribution variant so applications can resolve that contribution from every declared module before any module opts into its real plugin.

## Three build stages

A Viaduct application build consists of three logical stages based on artifact dependencies:

```text
module schema partitions + application schema + built-in schema
                              |
                              v
                    CENTRAL-SCHEMA STAGE
              validated schema + available GRT variants
                       |                |
                       v                |
                    MODULE STAGE        |
          compiled code + module manifests
                       |                |
                       v                v
                 APPLICATION ASSEMBLY STAGE
         manifest index + schema resources + runtime libraries
```

Gradle represents these dependencies through configurations and task-output providers. A producer exposes an artifact through a consumable configuration. A consumer declares a dependency and selects variants through a resolvable configuration. Providers carry the dependencies on producing tasks.

### 1. Central-schema stage

This stage combines module schema partitions, application-owned schema, and Viaduct built-in definitions, then validates the result. Third-party plugins can also supply SDL through the existing schema-contribution API. The central schema is a directory preserving partition structure and source provenance so downstream tooling can identify tenant ownership.

The application conventions populate `modules` from settings declarations and resolve their schema partitions through `allSchemaPartitionsIn`. Module conventions expose `schemaPartition` and resolve the owning application's `centralSchema` through `centralSchemaIn`. Schema preparation runs before module compilation and must not depend on generated resolver bases or compiled module code.

Schema and manifest metadata use the public `ViaductArtifacts.KIND` attribute (`viaduct.kind`) and `Usage=viaduct-contribution`. Each incoming metadata configuration is non-transitive:

| Artifact | `viaduct.kind` |
| --- | --- |
| Module schema partition | `schema-partition` |
| Central schema directory | `central-schema` |
| Module manifest directory | `module-manifest` |

Language generators consume the central schema and expose GRT JARs using ordinary JVM `java-api` and `java-runtime` variants with capabilities `com.airbnb.viaduct:kotlin-grt` and `com.airbnb.viaduct:java-grt`. Module plugins copy the public owning-application dependency and request their language capability. A module's `implementation` inherits that declaration, making the same GRT library available at compile time and runtime.

GRT JARs contain classes only. The neutral `generateViaductSchemaResources` task packages SDL, its resource index, and the schema scoping manifest together in a separate JAR. The application's `runtimeOnly` includes that JAR, so Java-only applications do not require Kotlin GRTs to load schema resources.

### 2. Module stage

The Kotlin and Java module plugins generate resolver bases and extract resolver descriptors through Kotlin Symbol Processing (KSP) or Java annotation processing. Manifest assembly uses the central schema to validate resolver selections, including required selection sets, and determine tenant-local field ownership.

Each assembled module manifest is a JSON resource under `META-INF/viaduct/modules/`. The language plugin contributes its generated directory through `moduleManifests` and adds it to the module's resources source set. The module JAR carries the manifest alongside compiled tenant code.

Generated resolver-base directories are added to the appropriate source sets. Task-output providers connect schema assembly, generation, compilation, descriptor extraction, and manifest assembly in dependency order. See [the KSP pipeline](../../impldocs/execution-registry-ksp-pipeline.md) for descriptor extraction and stale-output handling.

### 3. Application assembly stage

The application's `allModuleManifestsIn` selects explicit manifest contributions. `generateViaductExecutionRegistryResourceIndex` lists their resource paths and rejects duplicate paths and duplicate `(tenantName, apiName)` identities. The resulting index becomes an application resource; the manifests remain in module JARs. Index generation does not scan the application's runtime dependency JARs to discover contributions.

The application's `runtimeOnly` inherits its declared module dependencies. A separate runtime-variant resolution finds application-owned shared GRT libraries requested by those modules; an artifact view filters these libraries onto the application's compile classpath without adding compiled tenant code to it. Thus enabling an unused tenant API does not add its GRTs to either classpath.

The service engineer chooses the hosting framework, entry point, and distribution plugins that assemble the runnable service. See [bootstrap identity](../../impldocs/execution-registry-bootstrap.md) for runtime manifest loading.

## Stage boundaries and the development loop

For an application that also contains tenant code in the same project, the conventions connect local schema, GRT, and manifest outputs through task-output providers instead of self-referential project dependencies.

The central-schema assembly task declares its inputs and outputs and is cacheable. Gradle can skip it when inputs and outputs are unchanged, or restore a matching result from the build cache. When it executes, it assembles and validates the entire schema, even if only one partition changed.

Editing only a resolver implementation does not rerun schema assembly unless a plugin derives changed SDL from that source. Changes to the central schema or GRTs can invalidate downstream generation and compilation in several modules.

The convention and registration contracts provide a foundation for additional plugins. Federation behavior, custom scalar generation, and new validation or runtime extension interfaces are outside this change.
