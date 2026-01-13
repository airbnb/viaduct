---
title: Error Handling
description: Customizing error reporting and handling in Viaduct.
weight: 20
---

## Data Fetcher Error Handling

Viaduct provides two extension points for customizing error handling in
resolvers. Both are optional for service architects.

### ResolverErrorBuilder

When a resolver throws an exception, Viaduct will catch it and return it
as a GraphQL error. As a service architect, you can customize
resolver exception handling by implementing your own {{<
kdoc viaduct.service.api.spi.ResolverErrorBuilder >}}.

`ResolverErrorBuilder` is a functional interface with a single method,
`exceptionToGraphQLError`. This method takes the thrown exception and
error metadata, and produces a list of {{< kdoc viaduct.service.api.GraphQLError >}}
objects. Return `null` to indicate that your builder does not handle this
exception type, allowing the framework to use default handling.

```kotlin
import viaduct.service.api.GraphQLError
import viaduct.service.api.spi.ErrorBuilder
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.ResolverErrorBuilder

class MyResolverErrorBuilder : ResolverErrorBuilder {
    override fun exceptionToGraphQLError(
        throwable: Throwable,
        errorMetadata: ErrorReporter.Metadata
    ): List<GraphQLError>? {
        return when (throwable) {
            is MyCustomException -> listOf(
                ErrorBuilder.newError(errorMetadata)
                    .message("A custom error occurred: ${throwable.customMessage}")
                    .extension("errorType", "CUSTOM_ERROR")
                    .build()
            )
            is IllegalArgumentException -> listOf(
                ErrorBuilder.newError(errorMetadata)
                    .message("Invalid argument: ${throwable.message}")
                    .extension("errorType", "INVALID_ARGUMENT")
                    .build()
            )
            else -> null // Return null for unhandled exceptions
        }
    }
}
```

You can also use the functional interface syntax for simpler implementations:

```kotlin
val myErrorBuilder = ResolverErrorBuilder { throwable, metadata ->
    listOf(
        ErrorBuilder.newError(metadata)
            .message("Error: ${throwable.message}")
            .extension("errorType", "INTERNAL_ERROR")
            .build()
    )
}
```

The {{< kdoc viaduct.service.api.spi.ErrorBuilder >}} helper class automatically
populates the error's `path` and `locations` from the metadata, so you only
need to set the message and any custom extensions.

### ErrorReporter

In addition to returning errors in `ExecutionResult`, Viaduct also
allows you to configure an error reporter called from within the engine.

{{< kdoc viaduct.service.api.spi.ErrorReporter >}} is a functional interface
with a single method, `reportResolverError`. This method is called whenever
a resolver throws an exception and allows you to log the error or send it
to an external monitoring system. This interface does not affect error
reporting to clients or handling within the Viaduct engine.

For instance, if you wanted to emit exceptions to <a href="https://sentry.io/welcome/" target="_blank" rel="noopener noreferrer">Sentry</a>, you could implement the interface like this:

```kotlin
import viaduct.service.api.spi.ErrorReporter

class MyErrorReporter : ErrorReporter {
    override fun reportResolverError(
        exception: Throwable,
        errorMessage: String,
        metadata: ErrorReporter.Metadata
    ) {
        Sentry.captureException(exception) {
            it.setExtra("fieldName", metadata.fieldName)
            it.setExtra("parentType", metadata.parentType)
            it.setExtra("operationName", metadata.operationName)
            it.setExtra("path", metadata.executionPath?.joinToString("/"))
            it.setExtra("errorMessage", errorMessage)
            it.setExtra("isFrameworkError", metadata.isFrameworkError)
            it.setExtra("componentName", metadata.componentName)
        }
    }
}
```

The {{< kdoc viaduct.service.api.spi.ErrorReporter.Metadata >}} class provides
rich context about the error:

| Field | Description |
|-------|-------------|
| `fieldName` | The name of the field where the error occurred |
| `parentType` | The type of the parent object |
| `operationName` | The name of the GraphQL operation |
| `executionPath` | The execution path to the field (e.g., `["user", "profile", 0]`) |
| `sourceLocation` | Source location in the GraphQL query document |
| `isFrameworkError` | Whether the error is a framework error or tenant error |
| `resolvers` | List of resolver class names involved in the error |
| `source` | The source object being resolved (parent object) |
| `context` | The GraphQL context containing request-level data |
| `localContext` | The local context for field-specific data |
| `componentName` | The component name associated with the field definition |

You can also use the functional interface syntax:

```kotlin
val myErrorReporter = ErrorReporter { exception, errorMessage, metadata ->
    logger.error(
        "Error in field ${metadata.fieldName}: $errorMessage",
        exception
    )
}
```

## Configuring Error Handlers

To use custom error handlers, provide them when building your Viaduct instance:

```kotlin
val viaduct = ViaductBuilder()
    .withMeterRegistry(meterRegistry)
    .withDataFetcherErrorBuilder(MyResolverErrorBuilder())
    .withResolverErrorReporter(MyErrorReporter())
    .withTenantAPIBootstrapperBuilder(myBootstrapper)
    .build()
```

Both error handlers are optional. If not provided, Viaduct uses sensible defaults:
- `ResolverErrorBuilder.NOOP` - Returns `null`, allowing framework default handling
- `ErrorReporter.NOOP` - Does nothing (no external reporting)
