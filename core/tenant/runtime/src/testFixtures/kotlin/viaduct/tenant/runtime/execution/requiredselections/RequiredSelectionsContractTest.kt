package viaduct.tenant.runtime.execution.requiredselections

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

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
    }

    extend type Mutation {
      "Use queryValueFragment(initialString) via getQueryValue; return \"Mutated from: <initialString>\""
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
    fun `mutation resolver uses queryValueFragment`() {
        execute("mutation { string1 }").assertEquals {
            "data" to { "string1" to "Mutated from: InitialValue" }
        }
    }
}
