package viaduct.tenant.runtime.fixtures

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals

/**
 * Contract test for resolvers that use the @backingData annotation.
 *
 * Defines the SDL and assertions for:
 * - Backing data resolved and available to dependent resolvers (expected: i=10, s="Hello, World!")
 * - Error when trying to subselect on a backing data scalar field (SubselectionNotAllowed)
 * - Error when querying a backing data field directly (serialize error)
 *
 * Note: This contract test is Kotlin-only because @backingData is not available
 * in the Java Tenant API.
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
abstract class BackingDataContractTest : FeatureAppTestBase() {
    init {
        sdl = """
            | #START_SCHEMA
            | #directive @visibility(level: String!) on FIELD_DEFINITION
            |
            | extend type Query {
            |  "Return a Foo object"
            |  foo: Foo @resolver
            | }
            |
            | type Foo {
            |   "Read i from backing data; return 10"
            |   iValue: Int @resolver
            |   "Read s from backing data; return \"Hello, World!\""
            |   sValue: String @resolver
            |   "Return a BackingDataValue(i=10, s=\"Hello, World!\") instance of class featureapps.example.BackingDataValue"
            |   backingDataValue: BackingData
            |     #@visibility(level:"private")
            |     @resolver
            |     @backingData(class: "featureapps.example.BackingDataValue")
            | }
            | #END_SCHEMA
        """.trimMargin()
    }

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
    fun `Resolver includes a backing data fields in its required selections validation error`() {
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
                    "message" to "Validation error (SubselectionNotAllowed@[foo/backingDataValue]) : Subselection not allowed on leaf type " +
                        "'BackingData' of field 'backingDataValue'"
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
    fun `Resolver includes a backing data type in its required selections serialize error`() {
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
                    "message" to "serialize should not be called for BackingData scalar type. This is a no-op."
                    "locations" to arrayOf(
                        {
                            "line" to 3
                            "column" to 9
                        }
                    )
                    "path" to listOf("foo", "backingDataValue")
                    "extensions" to {
                        "classification" to "VIADUCT_INTERNAL_ENGINE_EXCEPTION"
                    }
                }
            )
            "data" to {
                "foo" to {
                    "backingDataValue" to null
                }
            }
        }
    }
}
