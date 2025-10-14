---
title: Testing
description: Testing the Star Wars demo application in Viaduct.
weight: 7
---
## Testing

The demo includes comprehensive tests:

- **Integration tests** for all resolvers
- **Batch resolver performance tests**
- **Scope isolation tests**
- **GlobalID encoding/decoding tests**
- **@oneOf input validation tests**

Key test files:

- `ResolverIntegrationTest.kt` - Tests all standard resolvers
- `BatchResolverDemoTest.kt` - Tests batch resolver efficiency
- `ExtrasScopeTest.kt` - Tests multi-module scoping
- `GlobalIDDemoTest.kt` - Tests GlobalID functionality

The implementation demonstrates how Viaduct's custom directives and batch resolvers enable powerful GraphQL schema
capabilities while maintaining optimal performance and clean, understandable code organization.

All examples shown in this document are representations of actual working code in this demo application. More example
queries and usage patterns can be found in
the [integration tests](src/test/kotlin/viaduct/demoapp/starwars/ResolverIntegrationTest.kt).

{{< prevnext >}}
