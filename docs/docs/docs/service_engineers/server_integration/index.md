---
title: Server Integration
description: Embedding Viaduct into a web framework, message consumer, or any other entry point.
---

Viaduct is a pure GraphQL execution engine. It does not include an HTTP server, a message
broker client, or any other transport layer. This is deliberate — it keeps the core lean and
lets you embed it into whatever infrastructure your organisation already uses.

The contract is simple: build an `ExecutionInput`, call `viaduct.executeAsync()`, and send
the result wherever it needs to go. The entry point that calls `executeAsync` can be an HTTP
controller, a Kafka consumer, a RabbitMQ listener, a gRPC handler, a CLI command — anything.

## The execution contract

```kotlin
// Build the input
val input = ExecutionInput.create(
    operationText = query,
    variables = variables,
    requestContext = yourRequestContext,
)

// Execute
val result = viaduct.executeAsync(input).await()

// Send the result wherever it needs to go
val responseBody = result.toSpecification()
```

`executeAsync` is a suspend function — it runs in a coroutine and does not block a thread
while resolvers are executing. Use `execute` only if you are in a non-coroutine context and
cannot avoid blocking.

## HTTP — StarWars demo (Micronaut)

The StarWars demo wires Viaduct to a Micronaut `@Controller`. The controller reads the
incoming request body, constructs an `ExecutionInput`, and returns the GraphQL result as JSON:

```kotlin title="demoapps/starwars/src/main/kotlin/.../ViaductRestController.kt"
@Controller
class ViaductRestController(
    private val viaduct: Viaduct,
) {
    @Post("/graphql")
    suspend fun graphql(
        @Body request: Map<String, Any>,
        @Header("X-Viaduct-Scopes") scopesHeader: String?,
    ): HttpResponse<Map<String, Any?>> {
        val input = ExecutionInput.create(
            operationText = request["query"] as String,
            variables = (request["variables"] as? Map<String, Any>) ?: emptyMap(),
        )
        val result = viaduct.executeAsync(input, determineSchemaId(scopesHeader)).await()
        return HttpResponse.ok(result.toSpecification())
    }
}
```

Any HTTP framework works the same way — Spring, Ktor, Jetty, etc. The demos directory
contains examples for each.

## Message consumer example

For a queue-based entry point the shape is identical. The only difference is where the
operation text comes from and where the result goes:

```kotlin
class GraphQLMessageConsumer(
    private val viaduct: Viaduct,
) {
    suspend fun onMessage(message: IncomingMessage) {
        val input = ExecutionInput.create(
            operationText = message.query,
            variables = message.variables,
            requestContext = message.headers,
        )
        val result = viaduct.executeAsync(input).await()
        message.reply(result.toSpecification())
    }
}
```

## ExecutionInput fields

| Field | Required | Description |
|---|---|---|
| `operationText` | yes | The GraphQL document text |
| `variables` | no | Variable values for the operation |
| `operationName` | no | Which operation to run when the document contains multiple |
| `requestContext` | no | Arbitrary object passed through to resolvers via the execution context |
| `operationId` | no | Stable identifier for the operation — auto-derived from a hash of the document if omitted |
| `executionId` | no | Unique ID for this specific execution — auto-generated UUID if omitted |

## See also

- [Dependency Injection](../dependency_injection/index.md) — Wiring a DI container so resolvers can declare constructor dependencies
- [Multi-tenancy](../multi_tenancy/index.md) — Serving multiple scoped schemas from a single instance
- [Observability](../observability/index.md) — Metrics and error reporting
