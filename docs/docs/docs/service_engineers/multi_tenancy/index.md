---
title: Multi-tenancy
description: Structuring a Viaduct application so multiple tenant projects contribute schema partitions and resolvers to one composed schema.
---

Viaduct multi-tenancy is a source and build structure for letting multiple teams contribute independently owned GraphQL schema partitions and resolver code to one Viaduct application.

A tenant is one Gradle project that applies a Viaduct module language plugin. It contributes a schema partition and the resolver implementations for that partition. The settings topology declares the application project, tenant projects, and package layout. The application project builds the `Viaduct` runtime that serves the composed schema.

This page is for service engineers setting up that source and build structure. For resolver authoring patterns, see the [Developers](../../developers/index.md) section. For a worked example, see the [Star Wars tutorial](../../../getting_started/starwars/index.md).

## Viaduct Gradle Plugins

A multi-tenant Viaduct build uses two kinds of Gradle plugins:

- **Settings plugins** declare the application project and its tenant-module projects in `settings.gradle.kts`. The Kotlin and Java settings plugins make their language bindings available to the application through `usePlugin(...)`.
- **Module language plugins** select a language for a tenant module. Apply `com.airbnb.viaduct.module-kotlin-gradle-plugin` for a Kotlin module or `com.airbnb.viaduct.module-java-gradle-plugin` for a Java module. Each module contains SDL and the resolvers that implement it.

An application can also enable an **application extension plugin** in its [settings declaration](#settings-topology). For Foundation Federation, add `id("com.airbnb.viaduct.foundation-federation") apply false` to the settings `plugins` block and `usePlugin("com.airbnb.viaduct.foundation-federation")` to `includeViaductApplication`.

## Migrating an Existing Build

1. Remove `com.airbnb.viaduct.application-gradle-plugin` from the application build script and `com.airbnb.viaduct.metamodule-gradle-plugin` from tenant build scripts. Declare the application and its modules in [settings](#settings-topology) so Viaduct can install the conventions.
2. Add `id("com.airbnb.viaduct.kotlinapi") apply false` or `id("com.airbnb.viaduct.javaapi") apply false` to the settings `plugins` block for each resolver language the application uses. Enable each with `usePlugin("com.airbnb.viaduct.kotlinapi")` or `usePlugin("com.airbnb.viaduct.javaapi")` in `includeViaductApplication`. The retired application plugin enabled both languages automatically. An application with both Java and Kotlin modules needs both settings declarations and both `usePlugin` calls. Keep a JVM plugin such as `java-library` or `org.jetbrains.kotlin.jvm` in the application build script.
3. Replace `com.airbnb.viaduct.module-gradle-plugin` with `com.airbnb.viaduct.module-kotlin-gradle-plugin` in Kotlin modules. Keep `com.airbnb.viaduct.module-java-gradle-plugin` in Java modules. Update any version catalog aliases to use these IDs and use the same Viaduct version throughout the build.
4. If a build script or plugin used the forwarding extensions, look up `ViaductApplicationConventions` in the application project or `ViaductModuleConventions` in a module with `extensions.getByType<...>()`.

A schema-only application can omit language bindings. Once a project applies a module language plugin, its owning application must enable the corresponding binding in settings.

## Recommended Project Shape

Use a Gradle multi-project build. One common shape is:

```text
settings.gradle.kts
build.gradle.kts                         # application project
common/build.gradle.kts                  # optional shared JVM code, not a Viaduct tenant
tenants/catalog/build.gradle.kts         # tenant project
tenants/orders/build.gradle.kts          # tenant project
tenants/users/build.gradle.kts           # tenant project
```

The exact directory names are your choice; `modules`, `tenants`, or domain-specific names are all fine as long as Gradle includes the projects and the Viaduct topology declares them. The settings declaration selects the languages available to the application. Kotlin tenant projects apply `viaduct.module`; Java tenant projects apply `viaduct.module.java`. The aliases in these examples refer to the version catalog supplied by the corresponding demo app; Gradle scripts use Kotlin DSL for both languages. Shared library projects do not apply Viaduct plugins.

## Settings Topology

Declare the Viaduct application and tenant modules in `settings.gradle.kts`. The `apply false` entries put the language plugins on the settings classpath. Each application selects the languages it needs with `usePlugin`.

```kotlin title="settings.gradle.kts"
plugins {
    id("com.airbnb.viaduct.settings-gradle-plugin")
    id("com.airbnb.viaduct.kotlinapi") apply false
    id("com.airbnb.viaduct.javaapi") apply false
}

includeViaductApplication {
    usePlugin("com.airbnb.viaduct.kotlinapi")
    usePlugin("com.airbnb.viaduct.javaapi")
    project(":")
    modulePackagePrefix("com.example.myservice")

    includeModule {
        project(":tenants:catalog")
        modulePackageSuffix("catalog")
    }
    includeModule {
        project(":tenants:orders")
        modulePackageSuffix("orders")
    }
    includeModule {
        project(":tenants:users")
        modulePackageSuffix("users")
    }
}
```

Viaduct uses this topology to wire module schema partitions and runtime dependencies. Module language plugins use it to find the owning application and generate code under the declared package layout.

## Application Project

The application project owns the runtime. Viaduct installs its application conventions from the settings declaration:

===! "Kotlin"

    ```kotlin title="build.gradle.kts"
    plugins {
        alias(libs.plugins.kotlinJvm)
    }

    dependencies {
        implementation(libs.viaduct.api)
        implementation(libs.viaduct.runtime)
    }
    ```

=== "Java"

    ```kotlin title="build.gradle.kts"
    plugins {
        `java-library`
        alias(libs.plugins.micronautApplication)
    }

    dependencies {
        implementation(libs.viaduct.api)
        implementation(libs.viaduct.javaapi.runtime)
        implementation(libs.viaduct.runtime)
        implementation(libs.micronaut.http.server.netty)
        implementation(libs.micronaut.inject)
        annotationProcessor(libs.micronaut.inject.java)
    }
    ```

    This reduced example uses the Java demo's version catalog. Its [full application build](https://github.com/airbnb/viaduct/blob/main/demoapps/starwars-java/build.gradle.kts) also configures Micronaut, the main class and tests.

The application project is also where service-engineering concerns usually live:

- constructing the `Viaduct` instance with `BasicViaductFactory` or `ViaductBuilder`
- integrating with the web framework or message consumer
- configuring dependency injection for resolver classes
- declaring [scope schemas](../scope_schemas/index.md), and configuring observability, feature flags, and deployment packaging

Tenant projects should normally be listed in the Viaduct settings topology rather than manually added as application project dependencies. Viaduct adds topology modules to the application runtime classpath so Viaduct can discover their generated metadata and resolver classes, while keeping tenant implementation code out of the application project's compilation classpath.

## Tenant Projects

Each tenant project applies the module language plugin for its resolver language.

===! "Kotlin"

    ```kotlin title="tenants/catalog/build.gradle.kts"
    plugins {
        `java-library`
        alias(libs.plugins.kotlinJvm)
        alias(libs.plugins.ksp)
        alias(libs.plugins.viaduct.module)
    }

    dependencies {
        api(libs.viaduct.api)
        implementation(libs.viaduct.runtime)
    }
    ```

=== "Java"

    {{ codefile("demoapps/starwars-java/modules/filmography/build.gradle.kts") | indent(4) }}

A tenant project usually contains:

===! "Kotlin"

    ```text
    tenants/catalog/
      build.gradle.kts
      src/main/viaduct/schema/
        Product.graphqls
        Query.graphqls
      src/main/kotlin/com/example/myservice/catalog/
        ProductNodeResolver.kt
        ProductSearchResolver.kt
    ```

=== "Java"

    ```text
    tenants/catalog/
      build.gradle.kts
      src/main/viaduct/schema/
        Product.graphqls
        Query.graphqls
      src/main/java/com/example/myservice/catalog/
        ProductNodeResolver.java
        ProductSearchResolver.java
    ```

The module language plugin reads tenant SDL from `src/main/viaduct/schema`. The Java or Kotlin package should align with the settings topology `modulePackagePrefix` plus the tenant project's `modulePackageSuffix`.

!!! warning
    Viaduct validates topology package prefixes and suffixes as dotted identifier segments, but it does not reject every reserved word from every resolver implementation language. Service engineers should consider adding application-specific Gradle linting to ensure `modulePackagePrefix` and `modulePackageSuffix` values are valid package names for the languages their resolver teams use.

For example, with `modulePackagePrefix("com.example.myservice")` on the application topology and `modulePackageSuffix("catalog")` on a tenant module, resolver implementations for that tenant should live under a package such as:

```text
com.example.myservice.catalog
```

## Central Schema Assembly

Each tenant contributes SDL files from its own `src/main/viaduct/schema` directory. During the application build, Viaduct assembles those schema partitions into one central schema.

This central schema is the schema served by the application runtime. A type defined in one tenant can be referenced or extended by another tenant because all tenant schema partitions contribute to the same central schema.

For example, one tenant can define a type:

```graphql title="tenants/catalog/src/main/viaduct/schema/Product.graphqls"
type Product @scope(to: ["default"]) {
  id: ID!
  title: String!
}
```

Another tenant can extend it:

```graphql title="tenants/orders/src/main/viaduct/schema/ProductOrderFields.graphqls"
extend type Product @scope(to: ["default"]) {
  recentOrders: [Order!]! @resolver
}
```

This is schema composition, not Gradle dependency resolution. If tenant code needs to call Java or Kotlin classes from another project, model that as a normal Gradle dependency. But GraphQL type visibility comes from central schema assembly, not from tenant-to-tenant Gradle dependencies.

## Source Layout Choices

The service engineer should choose a layout that makes ownership and build behavior obvious. Good layouts usually have these properties:

- Tenant projects are small enough that one team can own their schema and resolver code.
- Shared JVM code lives in ordinary library projects, separate from tenant schema partitions.
- Tenant project names and package suffixes are stable, because they shape generated code and resolver discovery.
- The Viaduct settings topology lists every tenant served by the application.
- The application project is the only place that owns runtime integration decisions.

### Organizing Large Schemas by Domain

For large schemas, use ordinary source layout and package naming to group tenants by domain or subdomain. The intermediate directories do not need to be Gradle projects; they can simply create a readable namespace.

```text
tenants/
  finance/
    accountspayable/
    accountsreceivable/
```

In that layout, the tenant modules might use settings-topology package suffixes such as:

```kotlin
modulePackageSuffix("finance.accountspayable")
```

and:

```kotlin
modulePackageSuffix("finance.accountsreceivable")
```

This keeps generated code and resolver discovery aligned with the ownership structure without implying that every directory is a Gradle project.

## What Belongs in Each Project

Application project:

- web server or message consumer entry point
- `BasicViaductFactory` or `ViaductBuilder` configuration
- dependency injection integration
- scope definitions in `src/main/viaduct/scopes.yaml` (see [Scope Schemas](../scope_schemas/index.md))
- observability and error handling configuration

Tenant project:

- GraphQL SDL for one owned slice of the central schema
- resolvers for fields declared by that tenant
- tenant-local tests
- tenant-specific data loaders or adapters when they are not shared elsewhere

A Java host must include `viaduct-javaapi-runtime` alongside the shared service API and main runtime. The Java root build example above shows these dependencies. Java modules use `viaduct-javaapi-api`. The module language plugin configures Viaduct's annotation processor, and the demo separately configures Micronaut's processor for DI.

Shared library project:

- domain models shared by multiple tenants
- backend clients used by multiple tenants
- framework-neutral utilities
- no Viaduct plugin unless the project also contributes SDL and resolvers

## Common Setup Mistakes

### Tenant Project Not on the Runtime Classpath

If an application omits a tenant project's metadata and resolver classes at runtime, check that the project applies a module language plugin and is declared in the settings topology:

```kotlin
includeViaductApplication {
    project(":app")
    modulePackagePrefix("com.example.app")

    includeModule {
        project(":tenants:catalog")
        modulePackageSuffix("catalog")
    }
}
```

Viaduct puts the declared tenant modules on the application runtime classpath. If metadata is missing, check the application and module paths in settings, the module language plugins, and the application's runtime classpath.

### Package Prefix and Suffix Do Not Line Up

If resolvers are not found at runtime, check that the settings topology `modulePackagePrefix` and each tenant project's `modulePackageSuffix` match the Java or Kotlin packages where resolver classes are compiled.

### Treating Tenant Schema Composition as a Gradle Dependency Graph

Tenant projects build independently as Gradle projects. Viaduct assembles their schema partitions into the central schema at the application level. Do not document tenant-to-tenant GraphQL relationships as Gradle dependency resolution.

Use normal Gradle dependencies only for ordinary JVM code dependencies.

## See Also

- [Getting Started: Gradle wiring](../../../getting_started/index.md#plugins-intro)
- [Star Wars Architecture: Tenants](../../../getting_started/starwars/architecture/tenants.md)
- [Server Integration](../server_integration/index.md)
- [Developers: Scopes](../../developers/scopes/index.md)
- [Scope Schemas](../scope_schemas/index.md)
- [Dependency Injection](../dependency_injection/index.md)
