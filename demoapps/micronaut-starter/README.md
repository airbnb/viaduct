# Micronaut Viaduct Starter

A minimal Viaduct GraphQL application using Micronaut for dependency injection.

## Key Features

- **Full DI Support**: Resolvers can have dependencies injected via Micronaut's `@Singleton`, `@Factory` annotations
- **Production Ready**: Runs as a full Micronaut HTTP server

## Project Structure

```
micronaut-starter/
├── src/
│   └── main/kotlin/com/example/viadapp/
│       └── production/                        # Production code
│           ├── ViaductConfiguration.kt        # Viaduct bean factory
│           └── MicronautCodeInjector.kt       # DI bridge
└── resolvers/
    └── src/main/
        ├── kotlin/.../resolvers/              # Resolver implementations
        └── viaduct/schema/schema.graphqls     # GraphQL schema
```

## Building and Testing

```bash
# From demoapps/micronaut-starter directory:
./gradlew build
```
