package inludedirective.featurapps

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

@TestSchema(
    """
    extend type Query {
     foo: Foo @resolver
     thrower: Thrower @resolver
     booleanValue: Boolean @resolver
    }

    type Thrower {
     willThrow: Int @resolver
    }

    type Foo {
      intValue: Int @resolver
      sValue: String @resolver
    }
"""
)
abstract class IncludeDirectiveContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `using include directive as false`() {
        execute(
            query = """
                query {
                    foo @include(if:false) {
                      intValue
                      sValue
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {}
        }
    }

    @Test
    fun `using include directive as true`() {
        execute(
            query = """
                query {
                    foo @include(if:true) {
                      intValue
                      sValue
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "foo" to {
                    "intValue" to 10
                    "sValue" to "result value"
                }
            }
        }
    }

    @Test
    fun `using include directive will not call @resolver even if it throws`() {
        execute(
            query = """
                query {
                    foo @include(if:true) {
                      intValue
                      sValue
                    }
                    thrower @include(if:false) {
                        willThrow
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "foo" to {
                    "intValue" to 10
                    "sValue" to "result value"
                }
            }
        }
    }

    @Test
    fun `using include as a given parameter from another @resolver`() {
        execute(
            query = """
                query MyQuery(${'$'}includeFoo: Boolean!){
                    booleanValue
                    foo @include(if: ${'$'}includeFoo) {
                      intValue
                      sValue
                    }
                    thrower @include(if:false) {
                        willThrow
                    }
                 }
            """.trimIndent(),
            variables = mapOf(
                "includeFoo" to false
            )
        ).assertEquals {
            "data" to {
                "booleanValue" to false
            }
        }
    }
}
