---
title: Error Handling
description: Customizing error reporting and handling in Viaduct.
weight: 20
---

{{% alert color="info" title="Preview Feature" %}}
This feature is currently in development. All documented functionality is ready to use but the API may change in future releases.
{{% /alert %}}

## Data Fetcher Error Handling

Viaduct provides two extension points for customizing error handling in
resolvers. Both are optional for service architects.

### ResolverErrorBuilder

When a resolver throws an exception, Viaduct will catch it and return it
as a GraphQL error. As a service architect, you can customize
resolver exception handling by implementing your own {{<
kdoc viaduct.service.api.spi.ResolverErrorBuilder >}}.
This interface has a single method, `exceptionToGraphQLError`, which takes the thrown exception and constructs a `GraphQLError` object.

`ResolverErrorBuilder` is an interface with a single method,
`exceptionToGraphQLError`. This method produces a list of `GraphQLError`
objects.

```kotlin
import graphql.GraphqlErrorBuilder

class MyResolverErrorBuilder : ResolverErrorBuilder {
  override fun exceptionToGraphQLError(
    exception: Throwable,
    env: DataFetchingEnvironment,
    errorMetadata: ErrorMetadata
  ): List<GraphQLError> {
    return when (exception) {
      is MyCustomException -> listOf(
        GraphqlErrorBuilder.newError(env)
          .message("A custom error occurred: ${exception.customMessage}")
          .build()
      )
      else -> listOf(
        GraphqlErrorBuilder.newError(env)
          .message("An unexpected error occurred")
          .errorType(ErrorType.DataFetchingException)
          .build()
      )
    }
  }
}
```

### ResolverErrorReporter

In addition to returning errors in `ExecutionResult`, Viaduct also
allows you to configure an error reporter called from within the engine.

{{< kdoc viaduct.service.api.spi.ResolverErrorReporter >}} is an interface with a single method, `reportError`.
This method is called whenever a resolver throws an exception and allows you
to log the error or send it to an external monitoring system. This interface
does not affect error reporting to clients or handling within the Viaduct
engine.

For instance, if you wanted to emit exceptions to <a href="https://sentry.io/welcome/" target="_blank" rel="noopener noreferrer">Sentry</a>, you could implement the interface like this:

```kotlin
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition

class MyResolverErrorReporter : ResolverErrorReporter {
  override fun reportError(
    exception: Throwable,
    fieldDefinition: GraphQLFieldDefinition,
    env: DataFetchingEnvironment,
    errorMessage: String,
    errorMetadata: ErrorMetadata
  ) {
    Sentry.captureException(exception) {
      it.setExtra("fieldName", fieldDefinition.name)
      it.setExtra("parentType", fieldDefinition.type.name)
      it.setExtra("path", env.executionStepInfo.path.toString())
      it.setExtra("errorMessage", errorMessage)
      it.setExtra("errorMetadata", errorMetadata.toString())
    }
  }
}
```

`ResolverErrorReporter` provides information about the exception, allowing
you to include additional context about the error in your monitoring system.
The example above includes the field name, parent type, execution path and
error metadata.

## Configuring Error Handlers

To use custom error handlers, provide them when building your Viaduct instance:

```kotlin
val viaduct = ViaductBuilder()
    .withMeterRegistry(meterRegistry)
    .withResolverErrorBuilder(MyResolverErrorBuilder())
    .withResolverErrorReporter(MyResolverErrorReporter())
    .withTenantAPIBootstrapperBuilder(myBootstrapper)
    .build()
```
