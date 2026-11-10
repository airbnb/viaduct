package viaduct.tenant.runtime.execution.requiredselections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

/**
 * Contract test for required selection set (RSS) features:
 * deep alias access, combined objectValueFragment + queryValueFragment, and
 * the regression guard for field-level RSS leaking across interface implementors.
 *
 * Resolver doc-comments specify required implementations.
 */
@TestSchema(
    """
    extend type Query {
      "Return \"B\""
      globalConfig: String @resolver
      "Return a Bar with value=\"B\""
      bar: Bar @resolver
      "Return a Baz with id=<globalIDFor(\"Baz\",\"baz1\")>, x=100"
      baz: Baz @resolver
      "Use objectValueFragment(aliasedBar: bar { aliasedValue: value }) via getObjectValue; return \"A:<aliasedBar.aliasedValue>\""
      string1: String @resolver
      "Return \"InitialValue\""
      initialString: String @resolver
      inputObject: Baz @resolver
      inputQuery: Baz @resolver
      inputCopy: Baz @resolver
      nestedInput: BazBox @resolver
      subqueryOutput: Baz @resolver
      subqueryCopy: Baz @resolver
      scalarCopy: Baz @resolver
      sourceConnection: BazConnection @resolver
      inputConnectionCopy: BazConnection @resolver
    }

    type BazBox {
      baz: Baz
    }

    type BazEdge @edge {
      node: Baz
      cursor: String
    }

    type BazConnection @connection {
      edges: [BazEdge]
      pageInfo: PageInfo
    }

    extend type Mutation {
      "Use ctx.query(initialString); return \"Mutated from: <initialString>\""
      string1: String @resolver
    }

    type Bar {
      "Return \"B\""
      value: String @resolver
    }

    type Baz implements Node {
      id: ID!
      x: Int
      "Use objectValueFragment(x) and queryValueFragment(globalConfig); return \"<globalConfig> item with value <x>\""
      y: String @resolver
    }
"""
)
abstract class RequiredSelectionsContractTest : KotlinFeatureAppTestContractBase() {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `required selection GRTs cannot be returned even when output selections fit`(engine2: Boolean) {
        configureEngine(engine2)
        for (field in listOf("inputObject", "inputQuery", "inputCopy")) {
            for (selection in listOf("x", "x y")) {
                val result = execute("{ $field { $selection } }")
                assertEquals(1, result.errors.size, "$field { $selection }")
                assertEquals("viaduct.errors.TenantUsageException", result.errors.single().extensions["fullyQualifiedErrorClass"])
                assertTrue(result.errors.single().message.contains("required-selection GRT"))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `required selection GRTs cannot be embedded in fresh outputs`(engine2: Boolean) {
        configureEngine(engine2)
        val result = execute("{ nestedInput { baz { x } } }")
        assertEquals(1, result.errors.size)
        assertEquals("viaduct.errors.TenantUsageException", result.errors.single().extensions["fullyQualifiedErrorClass"])
        assertTrue(
            result.errors.single().message.contains(
                "Cannot use required-selection GRT 'Baz' as output. Build a new GRT or use ctx.query() with the output selections."
            )
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `subquery GRTs and explicitly copied scalars can be returned`(engine2: Boolean) {
        configureEngine(engine2)
        for (field in listOf("subqueryOutput", "subqueryCopy", "scalarCopy")) {
            execute("{ $field { x y } }").assertEquals {
                "data" to {
                    field to {
                        "x" to 100
                        "y" to "B item with value 100"
                    }
                }
            }
        }
    }

    private fun configureEngine(engine2: Boolean) {
        withViaductBuilder {
            withFlagManager(if (engine2) MockFlagManager.create(FlagManager.Flags.ENGINE2_ENABLED) else MockFlagManager())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `generated connection copies retain required selection origin`(engine2: Boolean) {
        configureEngine(engine2)
        val result = execute("{ inputConnectionCopy { __typename } }")

        assertEquals(1, result.errors.size)
        assertEquals("viaduct.errors.TenantUsageException", result.errors.single().extensions["fullyQualifiedErrorClass"])
        assertTrue(result.errors.single().message.contains("required-selection GRT 'BazConnection'"))
    }

    @Test
    fun `required selections use deep aliases via objectValueFragment`() {
        execute("{ string1 }").assertEquals {
            "data" to { "string1" to "A:B" }
        }
    }

    @Test
    fun `objectValueFragment and queryValueFragment work together`() {
        execute("{ baz { y } }").assertEquals {
            "data" to { "baz" to { "y" to "B item with value 100" } }
        }
    }

    @Test
    fun `mutation resolver explicitly queries Query fields`() {
        execute("mutation { string1 }").assertEquals {
            "data" to { "string1" to "Mutated from: InitialValue" }
        }
    }
}
