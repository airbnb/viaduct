# Viaduct Jetty Starter App

A minimal Viaduct GraphQL application using Eclipse Jetty servlet container. This example demonstrates how to integrate Viaduct with a lightweight HTTP server using servlets.

## Requirements

- Java JDK 21 is installed
- `JAVA_HOME` environment variable is set correctly or `java` is in the classpath

## Quick Start

Check out the [Getting Started](https://airbnb.io/viaduct/docs/getting_started/) docs.

### Start the Viaduct Jetty Starter App

```bash
./gradlew run
```

The server will start on `http://localhost:8080`.

### Test the GraphQL endpoint

#### curl

With the server running, you can use the following `curl` command to send GraphQL queries:

```bash
curl 'http://localhost:8080/graphql' -H 'content-type: application/json' --data-raw '{"query":"{ greeting }"}'
```

You should see the following output:
```json
{"data":{"greeting":"Hello from Jetty + Viaduct!"}}
```

#### Query multiple fields

```bash
curl 'http://localhost:8080/graphql' -H 'content-type: application/json' --data-raw '{"query":"query HelloWorld { greeting author }"}'
```

Response:
```json
{
  "data": {
    "greeting": "Hello from Jetty + Viaduct!",
    "author": "Viaduct GraphQL with Jetty"
  }
}
```

#### GET requests

The servlet also supports GET requests with query parameters:

```bash
curl 'http://localhost:8080/graphql?query=%7B%20greeting%20%7D'
```

#### GraphiQL

With the server running, navigate to the following URL in your browser to bring up the [GraphiQL](https://github.com/graphql/graphiql) interface:

[http://localhost:8080/graphiql](http://localhost:8080/graphiql)

Then, run the following query:

```graphql
query HelloWorld {
  greeting
  author
}
```

You should see this response:

```json
{
  "data": {
    "greeting": "Hello from Jetty + Viaduct!",
    "author": "Viaduct GraphQL with Jetty"
  }
}
```

## Running Tests

Run the integration tests with:

```bash
./gradlew test
```

The tests start an embedded Jetty server and verify the GraphQL endpoint behavior.

## Project Structure

```
jetty-starter/
├── src/main/kotlin/com/example/viadapp/
│   ├── JettyViaductApplication.kt  # Main application entry point
│   └── ViaductServlet.kt           # GraphQL servlet implementation
├── resolvers/                       # GraphQL resolver module
│   └── src/main/
│       ├── kotlin/com/example/viadapp/resolvers/
│       │   └── HelloWorldResolvers.kt
│       └── viaduct/schema/
│           └── schema.graphqls      # GraphQL schema definition
└── src/test/kotlin/                 # Integration tests
```

## How It Works

This example demonstrates:

1. **BasicViaductFactory**: Creates a Viaduct engine with automatic module discovery
2. **Custom Servlet**: Implements a GraphQL-over-HTTP servlet that handles POST and GET requests
3. **Jetty Server**: Lightweight embedded HTTP server
4. **Module-based Architecture**: Resolvers are organized as a Viaduct module with automatic schema generation

The servlet supports:
- POST requests with JSON body (`application/json`)
- POST requests with GraphQL query body (`application/graphql`)
- GET requests with query parameters
- Proper error handling and status codes
