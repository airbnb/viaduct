package viaduct.tenant.runtime.execution.trivial

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

/**
 * Contract test for basic object resolution patterns.
 *
 * Defines the SDL and assertions for:
 * - Shorthand and fragment @Resolver patterns
 * - Object builders
 * - Field resolvers returning lists of objects
 * - Field resolvers with arguments
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
@TestSchema(
    """
    type Foo {
      shorthandBar: String @resolver
      fragmentBar: String @resolver
      baz: String @resolver
      nested: NestedFoo @resolver
      message: String @resolver
    }
    type NestedFoo {
      value: String @resolver
    }
    extend type Query {
      greeting: Foo @resolver
      fooList: [Foo] @resolver
      nestedFooList: [NestedFoo] @resolver
      fooWithArgs(message: String, count: Int): Foo @resolver
    }
"""
)
abstract class ObjectContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `shorthand resolver pattern`() {
        execute(
            query = """
                query {
                    greeting {
                        shorthandBar
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "greeting" to {
                    "shorthandBar" to "world"
                }
            }
        }
    }

    @Test
    fun `fragment resolver pattern`() {
        execute(
            query = """
                query {
                    greeting {
                        fragmentBar
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "greeting" to {
                    "fragmentBar" to "world-nested_value"
                }
            }
        }
    }

    @Test
    fun `field resolver returns a list of Foo objects`() {
        execute(
            query = """
                query {
                    fooList {
                        baz
                        nested {
                            value
                        }
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "fooList" to arrayOf(
                    {
                        "baz" to "world"
                        "nested" to {
                            "value" to "nested_value"
                        }
                    },
                    {
                        "baz" to "world"
                        "nested" to {
                            "value" to "nested_value"
                        }
                    },
                    {
                        "baz" to "world"
                        "nested" to {
                            "value" to "nested_value"
                        }
                    }
                )
            }
        }
    }

    @Test
    fun `field resolver returns a list of NestedFoo objects`() {
        execute(
            query = """
                query {
                    nestedFooList {
                        value
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "nestedFooList" to arrayOf(
                    {
                        "value" to "nested_value"
                    },
                    {
                        "value" to "nested_value"
                    }
                )
            }
        }
    }

    @Test
    fun `field resolver with arguments returns an object type`() {
        execute(
            query = """
                query {
                    fooWithArgs(message: "test message", count: 5) {
                        message
                        baz
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "fooWithArgs" to {
                    "message" to "message from resolver"
                    "baz" to "world"
                }
            }
        }
    }

    @Test
    fun `field resolver with null arguments returns an object type`() {
        execute(
            query = """
                query {
                    fooWithArgs(message: null, count: null) {
                        message
                        baz
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "fooWithArgs" to {
                    "message" to "message from resolver"
                    "baz" to "world"
                }
            }
        }
    }
}
