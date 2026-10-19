---
title: Build From Scratch
description: Create a Viaduct application from the ground up
---


This guide walks you through creating a Viaduct application from the ground up, giving you a deeper understanding of how Viaduct projects are structured.

> **Before you begin**: Review the [Compatibility section](../index.md#compatibility) to choose an appropriate Gradle version and language toolchain for your project.

## Getting Started

We'll build a simple CLI application that demonstrates the core concepts of Viaduct. This will be a single-module project where both the Viaduct application and schema are located inside the `src` directory.

### Project Setup

Create a new directory for your project and navigate into it:

```shell
mkdir viaduct-hello-world
cd viaduct-hello-world
```

### Configuring Gradle

=== "Kotlin"

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


    {{ codetag("demoapps/cli-starter/build.gradle.kts", "gradle-config", lang="kotlin", count=38) | indent(4) }}

=== "Java"

    Create `settings.gradle.kts`. This file uses Gradle’s Kotlin DSL even though the application is Java:

    ```kotlin
    pluginManagement {
        repositories {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
            mavenCentral()
            gradlePluginPortal()
        }
    }

    plugins {
        id("com.airbnb.viaduct.settings-gradle-plugin") version "2.1.0-SNAPSHOT"
    }

    dependencyResolutionManagement {
        repositories {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
            mavenCentral()
        }
    }

    rootProject.name = "viaduct-hello-world"

    includeViaductApplication {
        project(":")
        modulePackagePrefix("com.example.viadapp")
        includeModule {
            project(":")
            modulePackageSuffix("resolvers")
        }
    }
    ```

    The settings plugin registers the application and tenant module. Here both are in the same Gradle project. The package prefix and suffix put generated resolver bases in `com.example.viadapp.resolvers.resolverbases`.

    Create `build.gradle.kts`:

    ```kotlin
    plugins {
        application
        `java-library`
        id("com.airbnb.viaduct.application-gradle-plugin") version "2.1.0-SNAPSHOT"
        id("com.airbnb.viaduct.module-java-gradle-plugin") version "2.1.0-SNAPSHOT"
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        implementation("com.airbnb.viaduct:api:2.1.0-SNAPSHOT")
        implementation("com.airbnb.viaduct:runtime:2.1.0-SNAPSHOT")
        implementation("com.airbnb.viaduct:javaapi-api:2.1.0-SNAPSHOT")
        implementation("com.airbnb.viaduct:javaapi-runtime:2.1.0-SNAPSHOT")
        runtimeOnly("org.jetbrains.kotlin:kotlin-reflect:2.2.21")
    }

    application {
        mainClass.set("com.example.viadapp.ViaductApplication")
    }
    ```

    Use the same Viaduct version for the settings, application, and Java module plugins and all Viaduct dependencies. This example enables the snapshot repository directly, so it does not need the demo’s environment flag. `kotlin-reflect` is a runtime dependency of Viaduct; you do not need Kotlin source files or the Kotlin JVM plugin.

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

=== "Kotlin"

    Create the directory structure for your Kotlin code:

    ```shell
    mkdir -p src/main/kotlin/com/example/viadapp
    mkdir -p src/main/kotlin/com/example/viadapp/resolvers
    ```

    Create `src/main/kotlin/com/example/viadapp/ViaductApplication.kt`:


    {{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/viadapp/ViaductApplication.kt", "application-kt", lang="kotlin") | indent(4) }}


    Create `src/main/kotlin/com/example/viadapp/resolvers/HelloWorldResolvers.kt`:


    ```kotlin
    package com.example.viadapp.resolvers

    import com.example.viadapp.resolvers.resolverbases.QueryResolvers
    import viaduct.api.resolver.Resolver

    @Resolver
    class GreetingResolver : QueryResolvers.Greeting() {
        override suspend fun resolve(ctx: Context) = "Hello, World!"
    }

    @Resolver
    class AuthorResolver : QueryResolvers.Author() {
        override suspend fun resolve(ctx: Context) = "Brian Kernighan"
    }
    ```

=== "Java"

    Create the Java source directories:

    ```shell
    mkdir -p src/main/java/com/example/viadapp/resolvers
    ```

    Create `src/main/java/com/example/viadapp/ViaductApplication.java`:

    ```java
    package com.example.viadapp;

    public final class ViaductApplication {
        public static void main(String[] args) {
            Viaduct viaduct = BasicViaductFactory.create();
            String query = args.length > 0 ? args[0] : "{ greeting }";
            ExecutionInput input = ExecutionInput.builder()
                .operationText(query)
                .build();
            var result = viaduct.executeAsync(input, SchemaId.Base.INSTANCE).join();
            System.out.println(result.toSpecification());
        }
    }
    ```

    Create `src/main/java/com/example/viadapp/resolvers/GreetingResolver.java`:

    ```java
    package com.example.viadapp.resolvers;

    @Resolver
    public final class GreetingResolver extends QueryResolvers.Greeting {
        @Override
        public CompletableFuture<String> resolve(Context context) {
            return CompletableFuture.completedFuture("Hello, world!");
        }
    }
    ```

    Create `src/main/java/com/example/viadapp/resolvers/AuthorResolver.java`:

    ```java
    package com.example.viadapp.resolvers;

    @Resolver
    public final class AuthorResolver extends QueryResolvers.Author {
        @Override
        public CompletableFuture<String> resolve(Context context) {
            return CompletableFuture.completedFuture("Viaduct");
        }
    }
    ```

    The Java module plugin generates `QueryResolvers` from the schema before Java compilation. The default factory constructs these public no-argument resolvers; this small CLI does not need a dependency injection framework. `join()` is used only at the CLI boundary; compose futures inside resolvers instead of blocking.

### Building and running the Application

=== "Kotlin"

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

=== "Java"

    Use an installed Gradle to create the wrapper, then build and run with JDK 21+:

    ```shell
    gradle wrapper --gradle-version 9.1.0
    ./gradlew build
    ./gradlew -q run --args="'{ greeting }'"
    ./gradlew -q run --args="'{ author }'"
    ```

    The CLI prints the GraphQL response as a Java map, for example `{data={greeting=Hello, world!}}`. The Gradle build runs Java code generation and annotation processing automatically.

## What's Next

Continue to [Touring the Application](../../tour/index.md) to understand the structure of a Viaduct application.
