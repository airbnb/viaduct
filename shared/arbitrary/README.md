# Arbitrary
This module generates streams of arbitrary viaduct objects that can be used in
[property-based testing](https://en.wikipedia.org/wiki/Software_testing#Property_testing):

> Property testing is a testing technique where, instead of asserting that specific inputs produce specific expected
> outputs, the practitioner randomly generates many inputs, runs the program on all of them, and asserts the truth of
> some "property" that should be true for every pair of input and output.

This is a useful approach for testing systems for which it is difficult to enumerate all
test cases.

The arbitrary object streams are exposed as a kotest-property [Arb](https://kotest.io/docs/proptest/property-test-generators.html#arbitrary).
Properties of an Arb can be tested using JUnit, kotest, or other unit testing frameworks.


## QuickStart
```kotlin
import graphql.schema.idl.SchemaPrinter
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.graphQLSchema

class QuickStartTest {
    @Test
    fun `arbitrary graphql-java schemas can be serialized`() {
        Arb.graphQLSchema().forAll { schema ->
            runCatching { SchemaPrinter().print(schema) }.isSuccess()
        }
    }
}
```

# Configuration
Generator functions in this module can be configured using a `Config`. This can be used to
shape the distribution of generated objects and include options that control the length of names,
the maximum list depth, the probability that a field has arguments, and more.

See configs.kt for a list of configurable options.

## Example
```kotlin
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.ObjectSize
import viaduct.arbitrary.graphql.SchemaSize
import viaduct.arbitrary.graphql.graphQLSchema

val extraLargeObjectConfig = Config.default +
    (SchemaSize to 10) +
    (ObjectSize to 200..1000)

val arbSchema = Arb.graphQLSchema(extraLargeObjectConfig)
```

# Library
Useful Arbs included in this library.

## GraphQL
| Generator                 | Description                                                      |
|---------------------------|------------------------------------------------------------------|
| Arb.graphQLSchema         | generate arbitrary graphql-java schemas                          |
| Arb.viaductExtendedSchema | generate arbitrary ViaductExtendedSchemas                        |
| Arb.graphQLDocument       | generate arbitrary graphql-java query documents                  |
| Arb.graphQLExecutionInput | generate arbitrary graphql-java ExecutionInputs                  |
| Arb.graphQLValueFor       | generate arbitrary graphql-java values for a provided type       |
| Arb.graphQLName           | generate names suitable for use in GraphQL                       |
| arbRuntimeWiring          | create a deterministic RuntimeWiring that returns arbitrary data |
