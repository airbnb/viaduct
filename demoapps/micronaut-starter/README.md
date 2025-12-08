# Micronaut Viaduct Starter

A minimal Viaduct GraphQL application using Micronaut for dependency injection.

## Key Features

- **Fast Development Mode**: Uses only Micronaut's DI container (not the full HTTP server) when running with ViaductServer
- **Full DI Support**: Resolvers can have dependencies injected via Micronaut's `@Singleton`, `@Factory` annotations
- **Production Ready**: Same DI configuration works for both development and production

## Running in Serve Mode

The `MicronautServerProvider` starts only the Micronaut `ApplicationContext` (DI container), not the full HTTP server. This provides:

- Faster startup times
- Full dependency injection for resolvers
- GraphiQL IDE at http://localhost:8080/graphiql

```bash
./gradlew :demoapps:micronaut-starter:run
```

## Project Structure

```
micronaut-starter/
├── src/main/kotlin/
│   └── com/example/viadapp/
│       ├── serve/
│       │   └── MicronautServerProvider.kt  # ViaductServer integration
│       └── injector/
│           ├── ViaductConfiguration.kt     # Viaduct bean factory
│           └── MicronautTenantCodeInjector.kt  # DI bridge
└── resolvers/
    └── src/main/
        ├── kotlin/.../resolvers/           # Resolver implementations
        └── viaduct/schema/schema.graphqls  # GraphQL schema
```

## How It Works

1. `MicronautServerProvider` is annotated with `@ViaductServerConfiguration`
2. When ViaductServer starts, it discovers this provider
3. The provider calls `ApplicationContext.run()` (DI only, no HTTP server)
4. The `Viaduct` bean is retrieved from the DI container
5. ViaductServer uses this Viaduct instance to serve GraphQL requests
