# Viaduct CLI Starter app

## Requirements

- Java JDK 21 is installed
- `JAVA_HOME` environment variable is set correctly or `java` is in the classpath

## Quick Start

Check out the [Getting Started](https://airbnb.io/viaduct/docs/getting_started/) docs.

### Run the demo

```bash
./gradlew -q run
```

This will execute a default GraphQL query called `greeting`. You should see the following output:

```json
{
  "data" : {
    "greeting" : "Hello, World!"
  }
}
```

Try a different GraphQL query by adding it as an argument to the run command:

```bash
./gradlew -q run --args="'{ author }'"
```

You should see the following output:

```json
{
  "data" : {
    "author" : "Brian Kernighan"
  }
}
```
