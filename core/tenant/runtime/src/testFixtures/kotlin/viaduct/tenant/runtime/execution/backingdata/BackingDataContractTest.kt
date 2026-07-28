package viaduct.tenant.runtime.execution.backingdata

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

/**
 * Contract test for resolvers that use the @backingData annotation.
 *
 * Defines the SDL and assertions for:
 * - Backing data resolved and available to dependent resolvers (expected: i=10, s="Hello, World!")
 * - Error when trying to subselect on a backing data scalar field (FieldUndefined)
 * - Error when querying a backing data field directly (FieldUndefined)
 *
 * Note: This contract test is Kotlin-only because @backingData is not available
 * in the Java Tenant API.
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
@TestSchema(
    """
    #directive @visibility(level: String!) on FIELD_DEFINITION

    extend type Query {
     "Return a Foo object"
     foo: Foo @resolver
    }

    type Foo {
      "Read i from backing data; return 10"
      iValue: Int @resolver
      "Read s from backing data; return \"Hello, World!\""
      sValue: String @resolver
      "Return a BackingDataValue(i=10, s=\"Hello, World!\") instance of class featureapps.example.BackingDataValue"
      backingDataValue: BackingData
        #@visibility(level:"private")
        @resolver
        @backingData(class: "featureapps.example.BackingDataValue")
    }
"""
)
abstract class BackingDataContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `Backing data is resolved and available to other resolvers`() {
        execute(
            query = """
                query backing_data_resolved_available_to_other_resolvers {
                    foo {
                      sValue
                      iValue
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "foo" to {
                    "iValue" to 10
                    "sValue" to "Hello, World!"
                }
            }
        }
    }

    @Test
    fun `Direct backing data field subselection is hidden from base schema`() {
        execute(
            query = """
                    query TestQuery {
                        foo {
                            backingDataValue {
                                iValue,
                                sValue
                            }
                        }
                    }
            """.trimIndent()
        ).assertEquals {
            "errors" to arrayOf(
                {
                    "message" to "Validation error (FieldUndefined@[foo/backingDataValue]) : Field 'backingDataValue' in type 'Foo' is undefined"
                    "locations" to arrayOf(
                        {
                            "line" to 3
                            "column" to 9
                        }
                    )
                    "extensions" to {
                        "classification" to "ValidationError"
                    }
                }
            )
            "data" to null
        }
    }

    @Test
    fun `Direct backing data field query is hidden from base schema`() {
        execute(
            query = """
                    query TestQuery {
                        foo {
                            backingDataValue
                        }
                    }
            """.trimIndent()
        ).assertEquals {
            "errors" to arrayOf(
                {
                    "message" to "Validation error (FieldUndefined@[foo/backingDataValue]) : Field 'backingDataValue' in type 'Foo' is undefined"
                    "locations" to arrayOf(
                        {
                            "line" to 3
                            "column" to 9
                        }
                    )
                    "extensions" to {
                        "classification" to "ValidationError"
                    }
                }
            )
            "data" to null
        }
    }
}
