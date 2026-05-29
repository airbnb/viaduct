# Arbitrary
This module generates streams of arbitrary GraphQL objects that are suitable for
use in fuzzing and property-based testing.

Most of the generators are Viaduct-agnostic and produce GraphQL objects that can be used to test any GraphQL system.

The arbitrary object streams are exposed as a kotest-property [Arb](https://kotest.io/docs/proptest/property-test-generators.html#arbitrary);
properties of an Arb can be tested using JUnit, Kotest, or other unit testing frameworks.


## Quick Start
```kotlin
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import io.kotest.property.Arb
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.graphQLSchema

class QuickStartTest {
    @Test
    fun `arbitrary schemas can be roundtripped through SDL`(): Unit = runBlocking {
        Arb.graphQLSchema().checkAll { schema ->
            val sdl = SchemaPrinter().print(schema)
            SchemaParser().parse(sdl)
        }
    }
}
```

# Configuration
All generator functions in this module accept an optional `Config` parameter.

This is used to customize the shape of objects that are produced, and is primarily oriented around "weights" that control how often a GraphQL feature will be explored by a generator.

Omitting a `Config` will use a default configuration that approximates a real-world probability distribution. The generators in this module are tuned to have acceptable performance for ~1000 generations when using the default `Config`.

A `Config` may be modified to steer the distribution of generated objects towards an area of interest. This library includes configuration knobs for controlling the probabilities of a field definition taking arguments, the probability that a resolver will throw an exception, the probability that an inline fragment will omit a type condition, etc.

See `viaduct/arbitrary/graphql/configs.kt` for a full list of configuration knobs.

## Example
```kotlin
import io.kotest.property.Arb
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.ObjectTypeSize
import viaduct.arbitrary.graphql.SchemaSize
import viaduct.arbitrary.graphql.graphQLSchema

// create a custom Config that generates wide object types
val extraLargeObjectConfig = Config.default +
    (SchemaSize to 10) +
    (ObjectTypeSize to 200..1000)

val arbSchema = Arb.graphQLSchema(extraLargeObjectConfig)
```

# Library
Useful generators and methods provided by this library.

## GraphQL

### Schema & Documents
| Generator                 | Description                                                       |
|---------------------------|-------------------------------------------------------------------|
| Arb.graphQLSchema         | generate arbitrary `graphql.schema.GraphQLSchema` objects         |
| Arb.graphQLDocument       | generate arbitrary `graphql.language.Document` objects            |
| Arb.graphQLExecutionInput | generate arbitrary `graphql.ExecutionInput` objects               |
| Arb.vSchema               | generate arbitrary `viaduct.graphql.schema.ViaductSchema` objects |
| Arb.viaductSchema         | generate arbitrary `viaduct.engine.api.ViaductSchema` objects     |
| Arb.graphQLName           | generate names suitable for use in GraphQL                        |

### Values
| Generator                 | Description                                                               |
|---------------------------|---------------------------------------------------------------------------|
| Arb.ir                    | generate an arbitrary IR.Value for a given GraphQL type                   |
| Arb.objectIR              | generate an arbitrary IR.Value.Object for any object type in a schema     |
| Arb.outputObjectIR        | generate an arbitrary IR.Value.Object restricted to output object types   |
| Arb.inputObjectIR         | generate an arbitrary IR.Value.Object restricted to input object types    |

### Execution
| Generator                    | Description                                                              |
|------------------------------|--------------------------------------------------------------------------|
| Arb.viaduct                  | generate a complete Viaduct instance backed by arbitrary resolvers       |
| Arb.fieldResolverExecutor    | generate an arbitrary FieldResolverExecutor for a schema                 |
| Arb.nodeResolverExecutor     | generate an arbitrary NodeResolverExecutor for a schema                  |
| Arb.checkerExecutor          | generate an arbitrary CheckerExecutor for a schema                       |
| arbRuntimeWiring             | create a deterministic RuntimeWiring that returns arbitrary data         |
