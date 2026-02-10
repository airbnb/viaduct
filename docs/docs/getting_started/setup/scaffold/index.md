---
title: Scaffold Approach
description: Generate a complete Viaduct project with a single command
---


This guide walks you through creating a Viaduct application using the scaffold task, which generates a complete Ktor + Viaduct GraphQL project from a single command.

## Getting Started

The scaffold task generates a ready-to-run Ktor application with:

- GraphQL endpoint (`/graphql`)
- GraphiQL UI (`/graphiql`)
- Health check endpoint (`/health`)
- A sample "hello world" resolver

### Prerequisites

- Java 21 must be on the path or available via JAVA_HOME
- Gradle must be installed (the scaffold will generate wrapper files)

### Running the Scaffold

First, clone a repository that has the Viaduct application plugin applied. The simplest is the [CLI starter](https://github.com/viaduct-graphql/cli-starter):

```shell
git clone https://github.com/viaduct-graphql/cli-starter.git
cd cli-starter
```

Then run the scaffold task with your desired package prefix:

```shell
./gradlew scaffold -PpackagePrefix=com.example.myapp -PoutputDir=../my-app
```

This creates a complete project in `../my-app/`.

### Running the Generated Application

Navigate to your new project and run it:

```shell
cd ../my-app
./gradlew run
```

The server starts with:

- GraphQL endpoint at http://localhost:8080/graphql
- GraphiQL UI at http://localhost:8080/graphiql
- Health check at http://localhost:8080/health

Try the greeting query in GraphiQL:

```graphql
{
  greeting
}
```

### Generated Project Structure

```
my-app/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
└── src/
    └── main/
        ├── kotlin/
        │   └── com/example/myapp/
        │       ├── Main.kt
        │       ├── ktorplugins/
        │       │   ├── ContentNegotiation.kt
        │       │   └── Routing.kt
        │       └── resolvers/
        │           └── GreetingResolver.kt
        ├── resources/
        │   └── application.conf
        └── viaduct/
            └── schema/
                └── schema.graphqls
```

### Key Generated Files

#### Main.kt

The entry point uses Ktor's `EngineMain`. Here's a similar pattern from the ktor-starter:

{{ codetag("demoapps/ktor-starter/src/main/kotlin/com/example/viadapp/ViaductApplication.kt", "ktor-main") }}

#### schema.graphqls

The GraphQL schema extends the base Query type with a `greeting` field. The `@resolver` directive indicates that a resolver class implements this field:

{{ codetag("demoapps/cli-starter/src/main/viaduct/schema/schema.graphqls", "schema-config") }}

#### resolvers/GreetingResolver.kt

A resolver implements the `greeting` query. Here's the pattern from the cli-starter:

{{ codetag("demoapps/cli-starter/src/main/kotlin/com/example/resolvers/HelloWorldResolvers.kt", "greeting-resolver") }}

#### ktorplugins/ContentNegotiation.kt

Configures Jackson for JSON serialization:

{{ codetag("demoapps/ktor-starter/src/main/kotlin/com/example/viadapp/Plugins.kt", "ktor-content-negotiation") }}

#### ktorplugins/Routing.kt

Configures the GraphQL endpoint using `BasicViaductFactory`. The scaffold generates routes for `/health`, `/graphql` and `/graphiql`:

{{ codetag("demoapps/ktor-starter/src/main/kotlin/com/example/viadapp/Routing.kt", "ktor-graphql-routing") }}

### Customizing Your Application

After scaffolding, the generated code is entirely yours to modify:

1. **Add types**: Extend `schema.graphqls` with your types and queries
2. **Add resolvers**: Create new resolver classes in the `resolvers/` package
3. **Add routes**: Extend `ktorplugins/Routing.kt` with custom endpoints
4. **Configure the server**: Modify `application.conf` for port, host, etc.

### Development Mode

For development with auto-restart on code changes:

```shell
./gradlew --continuous run
```

## What's Next

Continue to [Touring the Application](../../tour/index.md) to understand the structure of a Viaduct application.
