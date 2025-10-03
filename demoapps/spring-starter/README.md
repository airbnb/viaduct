# Viaduct Spring Starter App

## Requirements

- Java JDK 21 is installed
- `JAVA_HOME` environment variable is set correctly or `java` is in the classpath

## Quick Start

Check out the [Getting Started](https://airbnb.io/viaduct/docs/getting_started/) docs.

### Start the Viaduct Spring Starter App

```bash
./gradlew bootRun
```

The server will start on `http://localhost:8080`.

> **Note**: For IntelliJ users, add this line to VM Params in Run Configuration:
> `--add-opens java.base/java.lang=ALL-UNNAMED`


### Test the GraphQL endpoint

#### curl

With the server running, you can use the following `curl` command to send GraphQL queries:

```bash
curl 'http://localhost:8080/graphql' -H 'content-type: application/json' --data-raw '{"query":"{ greeting }"}'
```

You should see the following output:
```json
{"data":{"greeting":"Hello, World!"}}
```

#### GraphiQL

With the server running, navigate to the following URL in your browser to bring up the [GraphiQL](https://github.com/graphql/graphiql) interface:

[http://localhost:8080/graphiql?path=/graphql](http://localhost:8080/graphiql?path=/graphql)

Then, run the following query:

```graphql
query HelloWorld {
  greeting
}
```

You should see this response:

```json
{
  "data": {
    "greeting": "Hello, World!"
  }
}
```
