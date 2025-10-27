---
title: Build From Scratch
description: Create a Viaduct application from the ground up
weight: 3
---

This guide walks you through creating a Viaduct application from the ground up, giving you a deeper understanding of how Viaduct projects are structured.

## Requirements

- Java 21 must be on the path or available via JAVA_HOME
- Gradle version 8 or higher

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
// When part of composite build, use local gradle-plugins
// When standalone, use Maven Central (only after version is published)
pluginManagement {
   if (gradle.parent != null) {
       includeBuild("../../gradle-plugins")
   } else {
       repositories {
           mavenCentral()
           gradlePluginPortal()
       }
   }
}

dependencyResolutionManagement {
   repositories {
       mavenCentral()
       gradlePluginPortal()
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

{{< codetag path="demoapps/cli-starter/build.gradle.kts" tag="gradle-config">}}

### Creating the Schema

Create the directory structure for your schema:

```shell
mkdir -p src/main/viaduct/schema
```

Create `src/main/viaduct/schema/schema.graphqls` with the following content:


{{< codetag path="demoapps/cli-starter/src/main/viaduct/schema/schema.graphqls" tag="schema-config">}}

### Creating the Application Code

Create the directory structure for your Kotlin code:

```shell
mkdir -p src/main/kotlin/com/example/viadapp
mkdir -p src/main/kotlin/com/example/viadapp/resolvers
```

Create `src/main/kotlin/com/example/viadapp/ViaductApplication.kt`:

{{< codetag path="demoapps/cli-starter/src/main/kotlin/com/example/viadapp/ViaductApplication.kt" tag="application-kt">}}

Create `src/main/kotlin/com/example/viadapp/resolvers/HelloWorldResolvers.kt`:

{{< codetag path="demoapps/cli-starter/src/main/kotlin/com/example/resolvers/HelloWorldResolvers.kt" tag="resolvers-setup">}}

### Building adn running the Application

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

Continue to [Touring the Application](../../tour) to understand the structure of a Viaduct application.
