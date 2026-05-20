---
title: Build From Scratch
description: Create a Viaduct application from the ground up
---


This guide walks you through creating a Viaduct application from the ground up, giving you a deeper understanding of how Viaduct projects are structured.

> **Before you begin**: Review the [Compatibility section](../index.md#compatibility) to choose appropriate Gradle and Kotlin versions for your project.

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
pluginManagement {
   repositories {
       mavenCentral()
       gradlePluginPortal()
   }
}

dependencyResolutionManagement {
   repositories {
       mavenCentral()
   }
   versionCatalogs {
       create("libs")
   }
}
```


You'll need to create a `gradle/libs.versions.toml` file:

```toml
[versions]
viaduct = "0.7.0"

[plugins]
viaduct-application = { id = "com.airbnb.viaduct.application-gradle-plugin", version.ref = "viaduct" }
viaduct-module = { id = "com.airbnb.viaduct.module-gradle-plugin", version.ref = "viaduct" }
```

Create a `build.gradle.kts` file. The key requirement is to include both Viaduct plugins:


{{ codetag("demoapps/cli-starter/build.gradle.kts", "gradle-config", lang="kotlin") }}


### Creating the Schema

Create the directory structure for your schema:

```shell
mkdir -p src/main/viaduct/schema
```

Create `src/main/viaduct/schema/schema.graphqls` with the following content:


{{ codetag("demoapps/cli-starter/src/main/viaduct/schema/schema.graphqls", "schema-config", lang="kotlin") }}


### Creating the Application Code

Create the directory structure for your Kotlin code:

```shell
mkdir -p src/main/kotlin/com/example/viadapp
mkdir -p src/main/kotlin/com/example/viadapp/resolvers
```

Create `src/main/kotlin/com/example/viadapp/ViaductApplication.kt`:


{{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/viadapp/ViaductApplication.kt", "application-kt", lang="kotlin") }}


Create `src/main/kotlin/com/example/viadapp/resolvers/HelloWorldResolvers.kt`:


{{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/resolvers/HelloWorldResolvers.kt", "resolvers-setup", lang="kotlin") }}


### Building and running the Application

Build your application:

```shell
gradle wrapper --gradle-version 8.14
./gradlew build
```

Run the application:

```shell
./gradlew -q run --args="'{ greeting }'"
```
or
```shell
./gradlew -q run --args="'{ author }'"
```

## What's Next

Continue to [Touring the Application](../../tour/index.md) to understand the structure of a Viaduct application.
