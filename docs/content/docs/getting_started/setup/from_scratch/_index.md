---
title: Build From Scratch
description: Create a Viaduct application from the ground up
weight: 3
---

This guide walks you through creating a Viaduct application from the ground up, giving you a deeper understanding of how Viaduct projects are structured.

## Getting Started

We'll build a simple CLI application that demonstrates the core concepts of Viaduct. This will be a single-module project where both the Viaduct application and schema are located inside the `src` directory.

### Project Setup

Create a new directory for your project and navigate into it:

```shell
mkdir viaduct-hello-world
cd viaduct-hello-world
```

### Configuring Gradle

Create a `settings.gradle.kts` file with the following content:

```kotlin
val viaductVersion: String by settings

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            version("viaduct", viaductVersion)
        }
    }
}
```

Create a `gradle.properties` file to specify the Viaduct version:

```properties
viaductVersion=0.3.0
```

You'll need to create a `gradle/libs.versions.toml` file:

```toml
[plugins]
viaduct-application = { id = "com.airbnb.viaduct.application-gradle-plugin", version.ref = "viaduct" }
viaduct-module = { id = "com.airbnb.viaduct.module-gradle-plugin", version.ref = "viaduct" }
```

Create a `build.gradle.kts` file. The key requirement is to include both Viaduct plugins:

```kotlin
plugins {
    kotlin("jvm") version "1.9.24"
    alias(libs.plugins.viaduct.application)
    alias(libs.plugins.viaduct.module)
    application
}

viaductApplication {
    modulePackagePrefix.set("com.example.viadapp")
}

viaductModule {
    modulePackageSuffix.set("resolvers")
}

// Additional dependencies and configuration as needed by your project
```

### Creating the Schema

Create the directory structure for your schema:

```shell
mkdir -p src/main/viaduct/schema
```

Create `src/main/viaduct/schema/schema.graphqls` with the following content:

```graphql
extend type Query {
  greeting: String @resolver
  author: String @resolver
}
```

### Creating the Application Code

Create the directory structure for your Kotlin code:

```shell
mkdir -p src/main/kotlin/com/example/viadapp
mkdir -p src/main/kotlin/com/example/viadapp/resolvers
```

Create `src/main/kotlin/com/example/viadapp/ViaductApplication.kt`:

```kotlin
package com.example.viadapp

import kotlinx.coroutines.runBlocking
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaRegistrationInfo
import viaduct.service.SchemaScopeInfo
import viaduct.service.TenantRegistrationInfo
import viaduct.service.api.ExecutionInput

const val SCHEMA_ID = "helloworld"

fun main(argv: Array<String>) {

  // Build viaduct Service with Schema ID and tenant package name
  val viaduct = BasicViaductFactory.create(
    schemaRegistrationInfo = SchemaRegistrationInfo(
      scopes = listOf(SchemaScopeInfo(SCHEMA_ID))
    ),
    tenantRegistrationInfo = TenantRegistrationInfo(
      tenantPackagePrefix = "com.example.viadapp"
    )
  )

  // Create an ExecutionInput to run in viaduct
  val defaultQuery = "query { greeting }"
  val executionInput = ExecutionInput(
    schemaId = SCHEMA_ID,
    query = argv.getOrNull(0) ?: defaultQuery,
    variables = emptyMap(),
    requestContext = object {}
  )

  // Execute the input and get the resolt.
  val result = runBlocking {
    viaduct.execute(executionInput)
  }

  println(result.toSpecification())
}
```

Create `src/main/kotlin/com/example/viadapp/resolvers/HelloWorldResolvers.kt`:

```kotlin

package com.example.viadapp.resolvers

import viaduct.api.Resolver
import com.example.viadapp.resolvers.resolverbases.QueryResolvers

@Resolver
class GreetingResolver : QueryResolvers.Greeting() {
  override suspend fun resolve(ctx: Context): String {
    return "Hello, World!"
  }
}

@Resolver
class AuthorResolver : QueryResolvers.Author() {
  override suspend fun resolve(ctx: Context): String {
    return "Viaduct Team"
  }
}

```

### Building adn running the Application

Build your application:

```shell
./gradlew build
```

Run the application:

```shell
./gradlew -q run --args="'{ greeting }'"
```

You should see the GraphQL response with your greeting!

## What's Next

Continue to [Touring the Application](../../tour) to understand the structure of a Viaduct application.
