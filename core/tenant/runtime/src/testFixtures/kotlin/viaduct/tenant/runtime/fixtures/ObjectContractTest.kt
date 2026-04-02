package viaduct.tenant.runtime.fixtures

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals

/**
 * Contract test for object resolution patterns.
 *
 * Defines the SDL and assertions for:
 * - Shorthand and fragment @Resolver patterns
 * - Object builders
 * - Field resolvers returning lists of objects
 * - Field resolvers with arguments
 * - Object types with non-resolver data fields and nested objects
 * - Computed field resolvers using objectValueFragment
 * - Nullable field handling
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
abstract class ObjectContractTest : FeatureAppTestBase() {
    init {
        sdl = """
            | #START_SCHEMA
            | type Foo {
            |   "Shorthand @Resolver pattern: use objectValueFragment = \"baz\" to request the baz field from the parent Foo; return its value (e.g. \"world\")"
            |   shorthandBar: String @resolver
            |   "Fragment @Resolver pattern: use objectValueFragment with a fragment on Foo requesting baz and nested { value }; return \"{baz}-{nested.value}\" (e.g. \"world-nested_value\")"
            |   fragmentBar: String @resolver
            |   "Return a plain string value (e.g. \"world\")"
            |   baz: String @resolver
            |   "Return a NestedFoo object"
            |   nested: NestedFoo @resolver
            |   "Return a string message (e.g. \"message from resolver\")"
            |   message: String @resolver
            | }
            | type NestedFoo {
            |   "Return a plain string value (e.g. \"nested_value\")"
            |   value: String @resolver
            | }
            | type Address {
            |   street: String!
            |   city: String!
            |   country: String
            | }
            | type Person {
            |   name: String!
            |   age: Int
            |   address: Address
            |   "Compute the full address string; use objectValueFragment with fragment on Person { address { street city country } }; return '<street>, <city>, <country>'"
            |   fullAddress: String @resolver
            |   "Return a simple greeting string without accessing any parent object fields"
            |   greeting: String @resolver
            | }
            | extend type Query {
            |   "Return a single Foo object"
            |   greeting: Foo @resolver
            |   "Return a list of 3 Foo objects"
            |   fooList: [Foo] @resolver
            |   "Return a list of 2 NestedFoo objects"
            |   nestedFooList: [NestedFoo] @resolver
            |   "Receive message (String) and count (Int) arguments via ctx.getArguments(); return a single Foo object"
            |   fooWithArgs(message: String, count: Int): Foo @resolver
            |   "Receive name argument; return a Person with name=name, age=30, address={street='123 Main St', city='San Francisco', country='USA'}"
            |   personByName(name: String!): Person @resolver
            | }
            | #END_SCHEMA
        """.trimMargin()
    }

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

    @Test
    fun `personResolverReturnsObjectType`() {
        execute(
            query = "query(\$name: String!) { personByName(name: \$name) { name age } }",
            variables = mapOf("name" to "Alice")
        ).assertEquals {
            "data" to {
                "personByName" to {
                    "name" to "Alice"
                    "age" to 30
                }
            }
        }
    }

    @Test
    fun `nestedObjectTypesAreAccessible`() {
        execute(
            query = "query(\$name: String!) { personByName(name: \$name) { name address { street city country } } }",
            variables = mapOf("name" to "Bob")
        ).assertEquals {
            "data" to {
                "personByName" to {
                    "name" to "Bob"
                    "address" to {
                        "street" to "123 Main St"
                        "city" to "San Francisco"
                        "country" to "USA"
                    }
                }
            }
        }
    }

    @Test
    fun `objectFieldResolverComputesValue`() {
        execute(
            query = "query(\$name: String!) { personByName(name: \$name) { name fullAddress } }",
            variables = mapOf("name" to "Charlie")
        ).assertEquals {
            "data" to {
                "personByName" to {
                    "name" to "Charlie"
                    "fullAddress" to "123 Main St, San Francisco, USA"
                }
            }
        }
    }

    @Test
    fun `nullableFieldsHandledCorrectly`() {
        execute(
            query = "{ personByName(name: \"Dave\") { name age address { country } } }"
        ).assertEquals {
            "data" to {
                "personByName" to {
                    "name" to "Dave"
                    "age" to 30
                    "address" to {
                        "country" to "USA"
                    }
                }
            }
        }
    }
}
