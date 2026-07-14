---
title: Multi-tenancy
description: Structuring a Viaduct application so multiple tenant projects contribute schema partitions and resolvers to one composed schema.
---

Viaduct multi-tenancy is a source and build structure for letting multiple teams contribute independently owned GraphQL schema partitions and resolver code to one Viaduct application.

A tenant is one Gradle project that applies the Viaduct module plugin. It contributes a schema partition and the resolver implementations for that partition. The settings topology declares the application project, tenant projects, and package layout. The application project applies the Viaduct application plugin and builds the `Viaduct` runtime that serves the composed schema.

This page is for service engineers setting up that source and build structure. For resolver authoring patterns, see the [Developers](../../developers/index.md) section. For a worked example, see the [Star Wars tutorial](../../../getting_started/starwars/index.md).

## The Two Viaduct Gradle Plugins

A multi-tenant Viaduct application uses two Gradle plugins:

- `com.airbnb.viaduct.application-gradle-plugin` marks the project that builds the `Viaduct` runtime. This is usually the host service project: the project with the HTTP server, dependency-injection setup, runtime configuration, observability, and deployment packaging.
- `com.airbnb.viaduct.module-gradle-plugin` marks a project that contributes tenant code. A tenant project contains GraphQL SDL files and the Kotlin resolvers that implement that SDL.

The application plugin coordinates the application-level build, aggregating the results of module-level builds into the larger application. The module plugin handles the module-local build process.

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

The exact directory names are your choice; `modules`, `tenants`, or domain-specific names are all fine as long as Gradle includes the projects and the Viaduct topology declares them. The application project applies `viaduct.application`. Tenant projects apply `viaduct.module`. Shared library projects do not apply either Viaduct plugin.

## Settings Topology

Declare the Viaduct application and tenant modules in `settings.gradle.kts`:

```kotlin title="settings.gradle.kts"
plugins {
    id("com.airbnb.viaduct.settings-gradle-plugin")
}

includeViaductApplication {
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

The application plugin uses this topology to wire module schema partitions and runtime dependencies. Module plugins use the same topology to find the owning application and generate code under the declared package layout.

## Application Project

The application project owns the runtime and should apply the application plugin:

```kotlin title="build.gradle.kts"
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.viaduct.application)
}

dependencies {
    implementation(libs.viaduct.api)
    implementation(libs.viaduct.runtime)
}
```

The application project is also where service-engineering concerns usually live:

- constructing the `Viaduct` instance with `BasicViaductFactory` or `ViaductBuilder`
- integrating with the web framework or message consumer
- configuring dependency injection for resolver classes
- configuring schema variants, observability, feature flags, and deployment packaging

Tenant projects should normally be listed in the Viaduct settings topology rather than manually added as application project dependencies. The application plugin adds topology modules to the application runtime classpath so Viaduct can discover their generated metadata and resolver classes, while keeping tenant implementation code out of the application project's compilation classpath.

## Tenant Projects

Each tenant project applies the module plugin:

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

A tenant project usually contains:

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

The `src/main/viaduct/schema` directory is the convention the module plugin uses for tenant SDL. The Kotlin package should align with the settings topology `modulePackagePrefix` plus the tenant project's `modulePackageSuffix`.

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

This is schema composition, not Gradle dependency resolution. If tenant code needs to call Kotlin classes from another project, model that as a normal Gradle dependency. But GraphQL type visibility comes from central schema assembly, not from tenant-to-tenant Gradle dependencies.

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
- schema scoping configuration
- observability and error handling configuration

Tenant project:

- GraphQL SDL for one owned slice of the central schema
- resolvers for fields declared by that tenant
- tenant-local tests
- tenant-specific data loaders or adapters when they are not shared elsewhere

Shared library project:

- domain models shared by multiple tenants
- backend clients used by multiple tenants
- framework-neutral utilities
- no Viaduct plugin unless the project also contributes SDL and resolvers

## Common Setup Mistakes

### Tenant Project Not on the Runtime Classpath

If a tenant project applies the module plugin but the application does not package its tenant metadata and resolver classes at runtime, first check that the tenant is declared in the settings topology:

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

The application plugin wires topology-declared tenant modules onto the runtime classpath. If runtime metadata is still missing, verify that the application and tenant plugins are applied to the projects named in settings, and inspect the application's runtime classpath rather than adding a manual `runtimeOnly(project(...))` edge.

### Package Prefix and Suffix Do Not Line Up

If resolvers are not found at runtime, check that the settings topology `modulePackagePrefix` and each tenant project's `modulePackageSuffix` match the Kotlin packages where resolver classes are compiled.

### Treating Tenant Schema Composition as a Gradle Dependency Graph

Tenant projects build independently as Gradle projects. Viaduct assembles their schema partitions into the central schema at the application level. Do not document tenant-to-tenant GraphQL relationships as Gradle dependency resolution.

Use normal Gradle dependencies only for ordinary Kotlin/JVM code dependencies.

## See Also

- [Getting Started: Gradle wiring](../../../getting_started/index.md#plugins-intro)
- [Star Wars Architecture: Tenants](../../../getting_started/starwars/architecture/tenants.md)
- [Server Integration](../server_integration/index.md)
- [Developers: Scopes](../../developers/scopes/index.md)
- [Dependency Injection](../dependency_injection/index.md)
