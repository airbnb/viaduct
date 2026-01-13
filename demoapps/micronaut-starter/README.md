# Micronaut Viaduct Starter

A minimal Viaduct GraphQL application using Micronaut for dependency injection.

## Key Features

- **Fast Development Mode**: Uses only Micronaut's DI container (not the full HTTP server) when running with ViaductServer
- **Full DI Support**: Resolvers can have dependencies injected via Micronaut's `@Singleton`, `@Factory` annotations
- **Production Ready**: Same DI configuration works for both development and production
- **Clean Separation**: Development-only code is in a separate source set and excluded from production builds

## Project Structure

```
micronaut-starter/
├── src/
│   ├── main/kotlin/com/example/viadapp/
│   │   └── production/                        # Production code (included in all builds)
│   │       ├── ViaductConfiguration.kt        # Viaduct bean factory
│   │       └── MicronautTenantCodeInjector.kt # DI bridge
│   └── dev/kotlin/com/example/viadapp/
│       └── dev/                               # Development-only code (excluded from production)
│           └── MicronautViaductProvider.kt    # ViaductServer integration
└── resolvers/
    └── src/main/
        ├── kotlin/.../resolvers/              # Resolver implementations
        └── viaduct/schema/schema.graphqls     # GraphQL schema
```

## Running in Development Mode

The `MicronautViaductProvider` (in the `dev` source set) starts only the Micronaut `ApplicationContext` (DI container), not the full HTTP server. This provides:

- Faster startup times
- Full dependency injection for resolvers
- GraphiQL IDE at http://localhost:8080/graphiql

```bash
# From demoapps/micronaut-starter directory:
./gradlew serve

# Or from projects/viaduct/oss directory:
./gradlew :micronaut-starter:serve
```

## Production vs Development

### Production Build
- Only includes code from `src/main/kotlin` (the `production` package)
- Does NOT include the `viaduct-serve` dependency
- Does NOT include `MicronautViaductProvider`
- Suitable for deployment with a full Micronaut HTTP server

### Development Build (serve task)
- Includes both `src/main/kotlin` and `src/dev/kotlin`
- Includes the `viaduct-serve` dependency
- Uses `MicronautViaductProvider` for fast iteration with GraphiQL

## How Development Mode Works

1. `MicronautViaductProvider` is annotated with `@ViaductServerConfiguration`
2. When ViaductServer starts, it discovers this provider via classpath scanning
3. The provider starts a minimal `ApplicationContext` (DI only, no HTTP server)
4. The `Viaduct` bean is retrieved from the DI container
5. ViaductServer uses this Viaduct instance to serve GraphQL requests

## Build Configuration

The `build.gradle.kts` uses Gradle source sets to separate development and production code:

```kotlin
// Create a separate source set for development-only code
sourceSets {
    create("dev") {
        kotlin.srcDir("src/dev/kotlin")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

// Dev source set configurations extend from main
val devImplementation by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

dependencies {
    // Production dependencies
    implementation(libs.micronaut.inject)
    implementation(libs.micronaut.context)

    // Development-only: serve dependency for ViaductServer integration
    devImplementation(libs.viaduct.serve)
}

// The serve task includes dev classes
tasks.named<JavaExec>("serve") {
    classpath += sourceSets["dev"].output
    classpath += sourceSets["dev"].runtimeClasspath
}
```

### Key Points

- **`devImplementation`**: Dependencies added here are only available to the `dev` source set and won't be included in production JARs
- **Source set inheritance**: The `dev` source set can access all classes from `main`, but not vice versa
- **Serve task configuration**: The `serve` task explicitly adds the dev classes and runtime dependencies to its classpath

### Building for Production

```bash
# Standard build - excludes dev code and viaduct-serve dependency
./gradlew build

# The resulting JAR only contains production code
./gradlew jar
```

### Running in Development

```bash
# Includes dev code and viaduct-serve dependency
./gradlew serve

# With auto-reload on code changes
./gradlew --continuous serve
```
