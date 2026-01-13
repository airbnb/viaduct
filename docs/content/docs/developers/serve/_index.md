---
title: Development Server (serve)
description: Run your Viaduct application with GraphiQL IDE and auto-reloading
weight: 100
---

## Overview

The `serve` task provides a development server for Viaduct applications with:

- **GraphiQL IDE**: Interactive GraphQL explorer in your browser
- **Auto-reloading**: Automatic rebuild and restart when code changes
- **Zero configuration**: Works out-of-the-box for simple applications

## Prerequisites

The `serve` task is automatically available in any project that applies the Viaduct application plugin:

```kotlin
plugins {
    id("com.airbnb.viaduct.application-gradle-plugin")
}
```

## Quick Start (Recommended)

Start the development server with automatic reloading:

```shell
./gradlew --continuous serve
```

The serve task automatically uses configuration from your `viaductApplication` extension in `build.gradle.kts`:

- **Package prefix**: Uses `viaductApplication.modulePackagePrefix` to discover your resolvers
- **Port**: Defaults to 8080 (configurable via `viaductApplication.servePort`)
- **Host**: Defaults to 0.0.0.0 (configurable via `viaductApplication.serveHost`)

This is the recommended way to run the development server. When you change source files:

1. Gradle detects the change
2. Rebuilds the affected code
3. Restarts the server automatically

The server provides:

- GraphQL endpoint: `http://localhost:8080/graphql`
- GraphiQL IDE: `http://localhost:8080/graphiql`
- Health check: `http://localhost:8080/health`

Press `Ctrl+C` to stop.

**Note**: If you have a custom `@ViaductServerConfiguration` provider, the package prefix from the extension is ignored as your provider controls Viaduct instantiation.

## Configuring Dependency Injection

### Using @ViaductServerConfiguration

To enable dependency injection in your resolvers, create a class annotated with `@ViaductServerConfiguration` that implements `ViaductProvider`:

```kotlin
import viaduct.serve.ViaductServerConfiguration
import viaduct.serve.ViaductProvider
import viaduct.service.api.Viaduct

@ViaductServerConfiguration
class MyViaductProvider : ViaductProvider {
    override fun getViaduct(): Viaduct {
        // Return your Viaduct instance configured with DI
        return myDiFramework.getBean(Viaduct::class.java)
    }
}
```

The serve server automatically discovers your implementation via classpath scanning for the annotation.

### Example: Micronaut Integration

```kotlin
@ViaductServerConfiguration
class MicronautViaductProvider : ViaductProvider {
    override fun getViaduct(): Viaduct {
        val context = ApplicationContext.builder()
            .packages(
                "com.example.app.injector",
                "com.example.app.resolvers"
            )
            .start()
        return context.getBean(Viaduct::class.java)
    }
}
```

### Example: Manual Configuration

```kotlin
@ViaductServerConfiguration
class MyViaductProvider : ViaductProvider {
    override fun getViaduct(): Viaduct {
        return ViaductBuilder()
            .withTenantModule(MyTenantModule())
            .build()
    }
}
```

### Without @ViaductServerConfiguration (Default Mode)

If no `@ViaductServerConfiguration` annotated class is found, the serve server falls back to default mode using `viaductApplication.modulePackagePrefix` from your build configuration. In this mode:

- **Dependency injection is NOT available**
- Only `@Resolver` classes with zero-argument constructors work
- Resolvers requiring injected dependencies will fail
- **Package prefix is read from `viaductApplication.modulePackagePrefix`**

You will see this warning when running in default mode:

```
╔════════════════════════════════════════════════════════════════════════════╗
║  NO @ViaductServerConfiguration FOUND - USING DEFAULT FACTORY             ║
╠════════════════════════════════════════════════════════════════════════════╣
║  DEPENDENCY INJECTION IS NOT AVAILABLE IN THIS MODE                        ║
║                                                                            ║
║  Only @Resolver classes with zero-argument constructors will work.        ║
║  If your resolvers require injected dependencies, they will fail.         ║
║                                                                            ║
║  To enable DI, create a class annotated with @ViaductServerConfiguration  ║
║  that implements ViaductProvider and returns your Viaduct instance.       ║
╚════════════════════════════════════════════════════════════════════════════╝
```

If `viaductApplication.modulePackagePrefix` is not set in your build configuration, you'll see an error:

```
No @ViaductServerConfiguration found and no packagePrefix configured.
Either:
  1. Create a @ViaductServerConfiguration provider class, OR
  2. Set viaductApplication.modulePackagePrefix in your build.gradle.kts
```

**Recommendation**: If your resolvers have any dependencies, create a `@ViaductServerConfiguration` class.

## Configuration Options

### In build.gradle.kts (Recommended)

Configure serve settings in your `build.gradle.kts` using the `viaductApplication` extension:

```kotlin
viaductApplication {
    modulePackagePrefix.set("com.example.app")  // Used for resolver discovery in default mode
    servePort.set(3000)                         // Default: 8080
    serveHost.set("127.0.0.1")                  // Default: 0.0.0.0
}
```

### Using Gradle Properties (Override)

You can override these settings at runtime using Gradle properties:

```shell
./gradlew --continuous serve -Pserve.port=3000 -Pserve.host=127.0.0.1
```

Or set them in your `gradle.properties` file:

```properties
serve.port=3000
serve.host=127.0.0.1
```

**Note**: Property overrides take precedence over extension settings, allowing temporary configuration changes without modifying build files.

## Development Workflow

### Recommended Workflow

1. Start the server in continuous mode:
   ```shell
   ./gradlew --continuous serve
   ```

2. Open GraphiQL in your browser: `http://localhost:8080/graphiql`

3. Make changes to your schema or resolvers:
   ```graphql
   # Add a new field to Character.graphqls
   type Character {
     # ... existing fields ...
     nickname: String
   }
   ```

4. Gradle automatically detects the change, rebuilds, and restarts the server

5. Refresh GraphiQL to see the new field in the schema

### What Gets Watched

Continuous mode watches:

- **GraphQL schema files** (`.graphqls`) in all modules
- **Kotlin source files** in `src/main/kotlin`
- **Resource files** referenced by the application

## Using GraphiQL

GraphiQL provides an interactive environment for exploring and testing your GraphQL API.

### Features

- **Query Editor**: Write and execute GraphQL queries
- **Schema Documentation**: Browse your schema's types and fields
- **Auto-completion**: Get suggestions as you type
- **Query History**: Access previously executed queries
- **Variables Panel**: Test queries with different variable values

### Example Query

Try this query in GraphiQL:

```graphql
{
  allCharacters(limit: 5) {
    name
    birthYear
    homeworld {
      name
    }
  }
}
```

## Troubleshooting

### Port Already in Use

If port 8080 is already in use, either:

- Stop the process using the port
- Use a different port with `-Pserve.port=<port>`

### Running Without Continuous Mode

If you need to run the server without auto-reloading:

```shell
./gradlew serve
```

Note: In this mode, you must manually stop and restart the server after making changes.

### Server Not Restarting in Continuous Mode

If the server doesn't restart after changes:

1. Check that you're using `--continuous` flag
2. Verify your changes are in watched files (schema or source code)
3. Check Gradle output for any compilation errors
4. Try stopping and restarting the continuous build

### Changes Not Reflected

If code changes don't appear in GraphiQL:

1. Hard refresh your browser (`Cmd+Shift+R` or `Ctrl+Shift+F5`)
2. Check the Gradle output for any errors during recompilation
3. Verify the server actually restarted (look for "Starting Viaduct Development Server..." in logs)

### Resolver Instantiation Errors

If you see errors about resolvers failing to instantiate:

1. Check if your resolvers require dependencies (constructor parameters)
2. If yes, create a `@ViaductServerConfiguration` class to enable DI
3. Verify your DI framework is configured correctly

## Comparison with Production

`serve` is for development only. For production deployments:

- Configure your actual HTTP server (Ktor, Jetty, etc.)
- Set up proper authentication and authorization
- Configure production logging and monitoring
- Review the [Service Engineers](../../service_engineers/) documentation

## Next Steps

- Learn about [Testing](../testing/) your Viaduct application
- Explore [Resolvers](../resolvers/) to add business logic
- Understand [Schema Management](../schema_change_management/) for evolving your API
