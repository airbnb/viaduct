@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.SerializationError
import graphql.execution.DataFetcherResult
import graphql.execution.NonNullableFieldWasNullError
import graphql.schema.DataFetcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Conformance tests for ViaductExecutionStrategy against GraphQL-Java's vanilla execution.
 *
 * This test class validates that ViaductExecutionStrategy produces identical results
 * to GraphQL-Java's standard AsyncExecutionStrategy across a comprehensive range of
 * GraphQL query scenarios.
 *
 * Test categories include:
 * - Basic field execution and null handling
 * - Error propagation and non-nullable field violations
 * - List execution with nulls and errors
 * - Nested object resolution
 * - DataFetcherResult handling with errors and extensions
 * - Async data fetching with suspending functions
 * - Complex error scenarios in nested structures
 *
 * Each test runs the same query through both ViaductExecutionStrategy and
 * GraphQL-Java's vanilla execution strategy, asserting that results match exactly.
 * This ensures our custom execution strategy maintains full compatibility with
 * the GraphQL specification as implemented by GraphQL-Java.
 *
 * For core execution functionality tests, see ViaductExecutionStrategyTest.
 * For child plan specific tests, see ViaductExecutionStrategyChildPlanTest.
 */
@ExperimentalCoroutinesApi
class ViaductExecutionStrategyConformanceTest {
    @Test
    fun `basic execution`() {
        Conformer(
            "type Query { complexField: String }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "complexField" to DataFetcher { env ->
                        "Complex result: ${env.field.name}"
                    }
                )
            )
        ) {
            check("{ complexField }") { _, (act) ->
                assertEquals(mapOf("complexField" to "Complex result: complexField"), act.getData())
            }
        }
    }

    @Test
    fun `basic serial execution`() {
        Conformer(
            """
                type Query { x:Int }
                type Mutation { x:Int }
                type Subscription { x:Int }
            """.trimIndent(),
            resolvers = mapOf(
                "Mutation" to mapOf("x" to DataFetcher { 0 }),
                "Subscription" to mapOf("x" to DataFetcher { 0 }),
            )
        ) {
            check("mutation { x }")
            check("subscription { x }")
        }
    }

    @Test
    fun `simple list execution`() {
        Conformer(
            "type Query { x:[Int] }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { listOf(1, 2, 3) }))
        ) {
            check("{ x }")
        }
    }

    @Test
    fun `simple nested list execution`() {
        Conformer(
            "type Query { x:[[Int]] }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { listOf(listOf(1, 1), listOf(2)) }))
        ) {
            check("{ x }")
        }
    }

    @Test
    fun `basic union execution`() {
        Conformer(
            """
                type Query { u: Union }
                union Union = Query
            """.trimIndent(),
            resolvers = mapOf("Query" to mapOf("u" to DataFetchers.emptyMap)),
            typeResolvers = mapOf("Union" to TypeResolvers.const("Query"))
        ) {
            check("{ u { __typename } }") { _, (act) ->
                assertEquals(mapOf("u" to mapOf("__typename" to "Query")), act.getData())
            }
        }
    }

    @Test
    fun `list-of-union execution`() {
        Conformer(
            """
                type Query { u: [Union] }
                union Union = Query
            """.trimIndent(),
            resolvers = mapOf("Query" to mapOf("u" to DataFetcher { listOf(emptyMap<String, Any?>()) })),
            typeResolvers = mapOf("Union" to TypeResolvers.const("Query"))
        ) {
            check("{ u { __typename } }") { _, (act) ->
                assertEquals(mapOf("u" to listOf(mapOf("__typename" to "Query"))), act.getData())
            }
        }
    }

    @Test
    fun `nested object execution`() {
        Conformer(
            """
                type Query { complexField: ComplexType }
                type ComplexType { simpleField: String }
            """.trimIndent(),
            resolvers = mapOf(
                "Query" to mapOf(
                    "complexField" to DataFetcher { env ->
                        mapOf("simpleField" to "Simple result: ${env.field.name}")
                    }
                )
            )
        ) {
            check("{complexField { simpleField }}") { _, (act) ->
                assertEquals(
                    mapOf(
                        "complexField" to
                            mapOf("simpleField" to "Simple result: complexField")
                    ),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `simple query with error inside datafetcher`() {
        Conformer(
            "type Query { simpleField: String }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "simpleField" to DataFetcher { throw RuntimeException("Error!") }
                )
            )
        ) {
            check("query { simpleField }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(mapOf("simpleField" to null), act.getData())
                assertEquals("Exception while fetching data (/simpleField) : Error!", act.errors[0].message)
            }
        }
    }

    @Test
    fun `simple query with error inside datafetcher -- aliased`() {
        Conformer(
            "type Query { simpleField: String }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "simpleField" to DataFetcher { throw RuntimeException("Error!") }
                )
            )
        ) {
            check("query { alias: simpleField }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(mapOf("alias" to null), act.getData())
                assertEquals("Exception while fetching data (/alias) : Error!", act.errors[0].message)
            }
        }
    }

    @Test
    fun `simple mutation with error inside datafetcher`() {
        Conformer(
            """
                type Query { empty: Int }
                type Mutation { simpleField: Int }
            """.trimIndent(),
            resolvers = mapOf(
                "Mutation" to mapOf(
                    "simpleField" to DataFetcher { throw RuntimeException("Error!") }
                )
            ),
        ) {
            check("mutation { simpleField }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(mapOf("simpleField" to null), act.getData())
                assertEquals("Exception while fetching data (/simpleField) : Error!", act.errors[0].message)
            }
        }
    }

    @Test
    fun `simple subscription with error inside datafetcher`() {
        Conformer(
            """
                type Query { empty: Int }
                type Subscription { simpleField: Int }
            """.trimIndent(),
            resolvers = mapOf(
                "Subscription" to mapOf(
                    "simpleField" to DataFetcher { throw RuntimeException("Error!") }
                )
            )
        ) {
            check("subscription { simpleField }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(mapOf("simpleField" to null), act.getData())
                assertEquals("Exception while fetching data (/simpleField) : Error!", act.errors[0].message)
            }
        }
    }

    @Test
    fun `partial result with one failing field`() {
        Conformer(
            """
            type Query {
                successField: String
                failingField: String
            }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "successField" to DataFetcher { "All good" },
                    "failingField" to DataFetcher { throw RuntimeException("Field failed!") }
                )
            )
        ) {
            check("{ successField, failingField }", checkNoModernErrors = false) { _, (act) ->
                val data = act.getData<Map<String, Any?>>()
                assertEquals("All good", data?.get("successField"))
                assertEquals(null, data?.get("failingField"))
                assertTrue(act.errors.any { it.message.contains("Field failed!") })
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `partial result in nested object with one field error`() {
        Conformer(
            """
                type Query { parent: Parent }
                type Parent { child: String sibling: String }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "parent" to DataFetcher {
                        mapOf("child" to "ignored", "sibling" to "siblingValue")
                    }
                ),
                "Parent" to mapOf(
                    "child" to DataFetcher { throw RuntimeException("Child resolution error!") },
                    "sibling" to DataFetcher { "siblingValue" }
                )
            )
        ) {
            check("{ parent { child sibling } }", checkNoModernErrors = false) { _, (act) ->
                val data = act.getData<Map<String, Any?>>()
                val parentData = data?.get("parent") as? Map<String, Any?>
                assertEquals("siblingValue", parentData?.get("sibling"))
                assertEquals(null, parentData?.get("child"))
                assertTrue(act.errors.any { it.message.contains("Child resolution error!") })
            }
        }
    }

    @Test
    fun `return null for a non-null field`() {
        Conformer(
            "type Query { simpleField: String! }",
            resolvers = mapOf(
                "Query" to mapOf("simpleField" to DataFetcher { null })
            )
        ) {
            check("{ simpleField }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(null, act.getData<Map<String, Any?>?>())
                assertTrue(act.errors[0] is NonNullableFieldWasNullError)
                assertTrue(
                    act.errors[0].message.contains(
                        "The field at path '/simpleField' was declared as a non null type"
                    )
                )
            }
        }
    }

    @Test
    fun `bubbles up non-null errors to the nearest nullable field a single level`() {
        Conformer(
            """
                type Query { complexField: ComplexType }
                type ComplexType { simpleField: String! }
            """,
            resolvers = mapOf(
                "Query" to mapOf("complexField" to DataFetcher { emptyMap<String, Any?>() }),
                "ComplexType" to mapOf("simpleField" to DataFetcher { null })
            )
        ) {
            check("{ complexField { simpleField } }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(mapOf("complexField" to null), act.getData())
                assertTrue(act.errors[0] is NonNullableFieldWasNullError)
                assertTrue(
                    act.errors[0].message.contains(
                        "The field at path '/complexField/simpleField' was declared as a non null type"
                    )
                )
            }
        }
    }

    @Test
    fun `bubbles up non-null errors to the nearest nullable field multiple levels`() {
        Conformer(
            """
                type Query { complexField: ComplexType }
                type ComplexType { anotherComplexType: AnotherComplexType! }
                type AnotherComplexType { simpleField: String! }
            """,
            resolvers = mapOf(
                "Query" to mapOf("complexField" to DataFetcher { mapOf<String, Any?>() }),
                "ComplexType" to mapOf("anotherComplexType" to DataFetcher { mapOf<String, Any?>() }),
                "AnotherComplexType" to mapOf("simpleField" to DataFetcher { null })
            )
        ) {
            check("{complexField { anotherComplexType { simpleField } } }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(mapOf("complexField" to null), act.getData())
                assertTrue(act.errors[0] is NonNullableFieldWasNullError)
                assertTrue(
                    act.errors[0].message.contains(
                        "The field at path '/complexField/anotherComplexType/simpleField' was declared as a non null type"
                    )
                )
            }
        }
    }

    @Test
    fun `bubbles non-null errors up to a nullable list item`() {
        Conformer(
            """
                type Query { list: [ComplexType] }
                type ComplexType { x: Int! }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "list" to DataFetcher {
                        listOf(mapOf("x" to 0), mapOf("x" to null), mapOf("x" to 2))
                    }
                ),
            )
        ) {
            check("{ list { x } }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(
                    mapOf("list" to listOf(mapOf("x" to 0), null, mapOf("x" to 2))),
                    act.getData()
                )
                assertTrue(act.errors[0] is NonNullableFieldWasNullError)
                assertTrue(
                    act.errors[0].message.contains(
                        "The field at path '/list[1]/x' was declared as a non null type"
                    )
                )
            }
        }
    }

    @Test
    fun `bubbles non-null errors through non-nullable lists`() {
        Conformer(
            """
                type Query { list: [ComplexType!] }
                type ComplexType { x: Int! }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "list" to DataFetcher {
                        listOf(mapOf("x" to 0), mapOf("x" to null), mapOf("x" to 2))
                    }
                ),
            )
        ) {
            check("{ list { x } }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(mapOf("list" to null), act.getData())
                assertTrue(act.errors[0] is NonNullableFieldWasNullError)
                assertTrue(
                    act.errors[0].message.contains(
                        "The field at path '/list[1]/x' was declared as a non null type"
                    )
                )
            }
        }
    }

    @Test
    fun `non-null exceptions are bubbled when a field is null due to an error`() {
        Conformer(
            "type Query { nonNullField: String! }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "nonNullField" to DataFetcher { _ ->
                        throw RuntimeException("Data fetching error!")
                    }
                )
            )
        ) {
            check("query { nonNullField }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(null, act.getData<Any?>())
            }
        }
    }

    @Test
    fun `error occurred during value serialization`() {
        Conformer(
            "type Query { intButWasString: Int }",
            resolvers = mapOf(
                "Query" to mapOf("intButWasString" to DataFetcher { "" })
            )
        ) {
            check("{ intButWasString }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(mapOf("intButWasString" to null), act.getData())
                assertTrue(act.errors[0] is SerializationError)
                assertTrue(
                    act.errors[0].message.contains(
                        "Can't serialize value (/intButWasString)"
                    )
                )
            }
        }
    }

    @Test
    fun `list type execution`() {
        Conformer(
            "type Query { listField: [String] }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "listField" to DataFetcher { listOf("item1", "item2", "item3") }
                )
            )
        ) {
            check("query { listField }") { _, (act) ->
                assertEquals(
                    mapOf("listField" to listOf("item1", "item2", "item3")),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `non-null list type execution`() {
        Conformer(
            "type Query { nonNullListField: [String]! }",
            resolvers = mapOf(
                "Query" to mapOf("nonNullListField" to DataFetcher { null })
            )
        ) {
            check("{ nonNullListField }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(null, act.getData<Any?>())
            }
        }
    }

    @Test
    fun `list field with empty items`() {
        Conformer(
            "type Query { listField: [Query] }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "listField" to DataFetcher { emptyList<Map<String, Any?>>() }
                )
            )
        ) {
            check("{ listField { __typename } }") { _, (act) ->
                assertEquals(mapOf("listField" to emptyList<Map<String, Any?>>()), act.getData())
            }
        }
    }

    @Test
    fun `list field with non-null items and a null item`() {
        Conformer(
            "type Query { nonNullItemListField: [String!] }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "nonNullItemListField" to DataFetcher { listOf("item1", null, "item3") }
                )
            )
        ) {
            check("query { nonNullItemListField }", checkNoModernErrors = false) { _, (act) ->
                assertEquals(mapOf("nonNullItemListField" to null), act.getData())
                assertEquals(1, act.errors.size)
                assertTrue(act.errors[0] is NonNullableFieldWasNullError)
                assertTrue(
                    act.errors[0].message.contains(
                        "The field at path '/nonNullItemListField[1]' was declared as a non null type"
                    )
                )
            }
        }
    }

    @Test
    fun `list field preserves original order despite asynchronous delays`() {
        Conformer(
            """
                type Query { delayedItems: [DelayedItem] }
                type DelayedItem { value: String }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "delayedItems" to DataFetcher {
                        // Return three items with identifiers to indicate the original order
                        listOf(mapOf("id" to 0), mapOf("id" to 1), mapOf("id" to 2))
                    }
                ),
                "DelayedItem" to mapOf(
                    "value" to DataFetcher { env ->
                        val source = env.getSource<Map<String, Any>>() ?: error("Source is null")
                        val id = source["id"] as Int
                        // Use scopedFuture to simulate an asynchronous computation with a delay.
                        // The delays are set so that the item with id 1 completes first.
                        scopedFuture {
                            delay(
                                when (id) {
                                    0 -> 200L
                                    1 -> 50L
                                    2 -> 100L
                                    else -> 0L
                                }
                            )
                            "Item $id"
                        }
                    }
                )
            )
        ) {
            check("{ delayedItems { value } }") { _, (act) ->
                val expected = mapOf(
                    "delayedItems" to listOf(
                        mapOf("value" to "Item 0"),
                        mapOf("value" to "Item 1"),
                        mapOf("value" to "Item 2")
                    )
                )
                assertEquals(expected, act.getData())
            }
        }
    }

    @Test
    fun `query with nested list of objects`() {
        Conformer(
            """
                type Query { users: [User] }
                type User { name: String age: Int nameAndAge: String }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "users" to DataFetcher {
                        listOf(
                            mapOf("name" to "Alice", "age" to 30),
                            mapOf("name" to "Bob", "age" to 25)
                        )
                    }
                ),
                "User" to mapOf(
                    "nameAndAge" to DataFetcher {
                        scopedFuture {
                            val source = it.getSource<Map<String, Any?>?>()!!
                            "${source["name"]} is ${source["age"]} years old"
                        }
                    },
                )
            )
        ) {
            check("{ users { name age age nameAndAge } }") { _, (act) ->
                assertEquals(
                    mapOf(
                        "users" to listOf(
                            mapOf("name" to "Alice", "age" to 30, "nameAndAge" to "Alice is 30 years old"),
                            mapOf("name" to "Bob", "age" to 25, "nameAndAge" to "Bob is 25 years old")
                        )
                    ),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `query with list of lists`() {
        Conformer(
            """
                type Query { matrix: [[MatrixItem]] }
                type MatrixItem { value: Int valuePlusOne: Int }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "matrix" to DataFetcher {
                        listOf(
                            listOf(mapOf("value" to 1), mapOf("value" to 2), mapOf("value" to 3)),
                            listOf(mapOf("value" to 4), mapOf("value" to 5), mapOf("value" to 6))
                        )
                    }
                ),
                "MatrixItem" to mapOf(
                    "valuePlusOne" to DataFetcher {
                        it.getSource<Map<String, Int>>()!!["value"]!! + 1
                    }
                )
            )
        ) {
            check(
                "{matrix { value valuePlusOne }}"
            ) { _, (act) ->
                assertEquals(
                    mapOf(
                        "matrix" to listOf(
                            listOf(mapOf("value" to 1, "valuePlusOne" to 2), mapOf("value" to 2, "valuePlusOne" to 3), mapOf("value" to 3, "valuePlusOne" to 4)),
                            listOf(mapOf("value" to 4, "valuePlusOne" to 5), mapOf("value" to 5, "valuePlusOne" to 6), mapOf("value" to 6, "valuePlusOne" to 7))
                        )
                    ),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `query with object containing a non-nullable list of lists of objects`() {
        Conformer(
            """
                type Cell { x: Int }
                type Obj { cells: [[Cell!]!]! }
                type Query { obj: Obj }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "obj" to DataFetcher {
                        mapOf(
                            "cells" to listOf(
                                listOf(mapOf("x" to 1), mapOf("x" to 2)),
                                listOf(mapOf("x" to 3), mapOf("x" to 4)),
                            )
                        )
                    }
                ),
            )
        ) {
            check("{ obj { cells { x } } }") { _, (act) ->
                assertEquals(
                    mapOf(
                        "obj" to mapOf(
                            "cells" to listOf(
                                listOf(mapOf("x" to 1), mapOf("x" to 2)),
                                listOf(mapOf("x" to 3), mapOf("x" to 4)),
                            )
                        )
                    ),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `type resolver arguments -- interface`() {
        Conformer(
            """
                interface I { x: Int! }
                type Foo implements I { x: Int! }
                type Query { i(x: Int!): I }
            """,
            resolvers = mapOf(
                "Query" to mapOf("i" to DataFetcher { mapOf("x" to it.arguments["x"]) })
            ),
            typeResolvers = mapOf("I" to TypeResolvers.const("Foo"))
        ) {
            check("{ i(x: 0) { __typename, x } }") { _, (act) ->
                assertEquals(
                    mapOf("i" to mapOf("__typename" to "Foo", "x" to 0)),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `type resolver arguments -- union`() {
        Conformer(
            """
                union U = Foo
                type Foo { x: Int! }
                type Query { u(x: Int!): U }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "u" to DataFetcher { mapOf("x" to it.arguments["x"]) }
                )
            ),
            typeResolvers = mapOf("U" to TypeResolvers.const("Foo"))
        ) {
            check("{ u(x: 0) { __typename ... on Foo { x } } }") { _, (act) ->
                assertEquals(
                    mapOf("u" to mapOf("__typename" to "Foo", "x" to 0)),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `datafetcher returns POJO`() {
        data class User(val name: String, val age: Int)

        Conformer(
            """
                type Query { user: User }
                type User { name: String age: Int }
            """,
            resolvers = mapOf(
                "Query" to mapOf("user" to DataFetcher { User("Alice", 30) })
            )
        ) {
            check("{ user { name age } } ") { _, (act) ->
                assertEquals(
                    mapOf("user" to mapOf("name" to "Alice", "age" to 30)),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `enum type execution`() {
        Conformer(
            """
                enum Episode { NEWHOPE EMPIRE JEDI }
                type Query { favoriteEpisode: Episode }
            """,
            resolvers = mapOf(
                "Query" to mapOf("favoriteEpisode" to DataFetcher { "EMPIRE" })
            )
        ) {
            check("{ favoriteEpisode }") { _, (act) ->
                assertEquals(
                    mapOf("favoriteEpisode" to "EMPIRE"),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `input object type execution`() {
        Conformer(
            """
                input ReviewInput { stars: Int! commentary: String }
                type Mutation { submitReview(review: ReviewInput!): Boolean }
                type Query { dummy: String }
            """,
            resolvers = mapOf(
                "Mutation" to mapOf(
                    "submitReview" to DataFetcher { env ->
                        val review = env.getArgument("review") as Map<String, Any>
                        review["stars"] as Int >= 4
                    }
                )
            )
        ) {
            check(
                "mutation { submitReview(review: { stars: 5, commentary: \"Great!\" })}"
            ) { _, (act) ->
                assertEquals(mapOf("submitReview" to true), act.getData())
            }
        }
    }

    @Test
    fun `ensure serial execution of mutations`() {
        var counter = 0

        Conformer(
            """
                type Mutation { increment(amount: Int!): Int }
                type Query { dummy: String }
            """,
            resolvers = mapOf(
                "Mutation" to mapOf(
                    "increment" to DataFetcher { env ->
                        scopedFuture {
                            val amount = env.getArgument<Int>("amount")!!
                            // delay by a random amount between 100 and 200ms
                            delay((100..200).random().toLong())
                            counter += amount
                            counter
                        }
                    }
                )
            )
        ) {
            check(
                """
                    mutation {
                        a: increment(amount: 1)
                        b: increment(amount: 2)
                        c: increment(amount: 3)
                    }
                """,
                checkResultsEqual = false
            ) { _, (act) ->
                // Check that the result is correct
                assertEquals(mapOf("a" to 1, "b" to 3, "c" to 6), act.getData())
            }
        }
    }

    @Test
    fun `query with variables`() {
        Conformer(
            "type Query {echo(input: String!): String }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "echo" to DataFetcher { it.getArgument<String>("input") }
                )
            )
        ) {
            check(
                "query (\$input: String!) { echo(input: \$input) }",
                variables = mapOf("input" to "Hello, World!")
            )
        }
    }

    @Test
    fun `execute query with interface type`() {
        Conformer(
            """
                interface Character { id: ID! name: String! }
                type Human implements Character { id: ID! name: String! homePlanet: String }
                type Droid implements Character { id: ID! name: String! primaryFunction: String }
                type Query { character: Character }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "character" to DataFetcher {
                        mapOf(
                            "__typename" to "Droid",
                            "id" to "1",
                            "name" to "R2-D2",
                            "primaryFunction" to "Astromech"
                        )
                    }
                )
            ),
            typeResolvers = mapOf("Character" to TypeResolvers.typename)
        ) {
            check(
                """
                    {
                        character {
                            id
                            name
                            ... on Droid { primaryFunction }
                        }
                    }
                """
            ) { _, (act) ->
                assertEquals(
                    mapOf("character" to mapOf("id" to "1", "name" to "R2-D2", "primaryFunction" to "Astromech")),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `execution with fragments and inline fragments`() {
        Conformer(
            """
                type Query { character: Character }
                interface Character { id: ID! name: String! }
                type Human implements Character { id: ID! name: String! homePlanet: String }
                type Droid implements Character { id: ID! name: String! primaryFunction: String }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "character" to DataFetcher {
                        mapOf(
                            "__typename" to "Human",
                            "id" to "1000",
                            "name" to "Luke Skywalker",
                            "homePlanet" to "Tatooine"
                        )
                    }
                )
            ),
            typeResolvers = mapOf("Character" to TypeResolvers.typename)
        ) {
            check(
                """
                    { character { ...CharacterFields } }
                    fragment CharacterFields on Character {
                        id
                        name
                        ... on Human { homePlanet }
                        ... on Droid { primaryFunction }
                    }
                """
            ) { _, (act) ->
                assertEquals(
                    mapOf("character" to mapOf("id" to "1000", "name" to "Luke Skywalker", "homePlanet" to "Tatooine")),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `execution with union types`() {
        Conformer(
            """
                type Query { search: SearchResult }
                union SearchResult = Human | Droid
                type Human { id: ID! name: String! homePlanet: String }
                type Droid { id: ID! name: String! primaryFunction: String }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "search" to DataFetcher {
                        mapOf(
                            "__typename" to "Human",
                            "id" to "1000",
                            "name" to "Luke Skywalker",
                            "homePlanet" to "Tatooine"
                        )
                    }
                )
            ),
            typeResolvers = mapOf("SearchResult" to TypeResolvers.typename)
        ) {
            check(
                """
                    {
                        search {
                            ... on Human { id name homePlanet }
                            ... on Droid { id name primaryFunction }
                        }
                    }
                """
            ) { _, (act) ->
                assertEquals(
                    mapOf("search" to mapOf("id" to "1000", "name" to "Luke Skywalker", "homePlanet" to "Tatooine")),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `execution with union types -- fragments can be spread with different contextual type constraints`() {
        Conformer(
            """
                type Query { u: Union }
                type Foo { x: Int }
                type Bar { y: Int }
                union Union = Foo | Bar
            """,
            resolvers = mapOf(
                "Query" to mapOf("u" to DataFetcher { mapOf("x" to 1) })
            ),
            typeResolvers = mapOf("Union" to TypeResolvers.const("Foo"))
        ) {
            check(
                """
                    {
                        u1: u { ... on Bar { ...Frag }}
                        u2: u { ...Frag }
                    }
                    fragment Frag on Union { __typename  }
                """
            )
        }
    }

    @Test
    fun `execution with interface and union type narrowing`() {
        Conformer(
            """
                type Query { searchResults(query: String): [SearchResult!]! }

                union SearchResult = Dog | Cat | Toy | Store
                interface Pet { id: ID! name: String! }

                type Dog implements Pet { id: ID! name: String! breed: String! goodBoy: Boolean! }
                type Cat implements Pet { id: ID! name: String! lives: Int! mousesPerDay: Int! }
                type Toy { id: ID! name: String! price: Float! }
                type Store { id: ID! name: String! location: String! }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "searchResults" to DataFetcher { _ ->
                        // Return a mix of different types to test narrowing
                        listOf(
                            mapOf(
                                "__typename" to "Dog",
                                "id" to "1",
                                "name" to "Rover",
                                "breed" to "Labrador",
                                "goodBoy" to true
                            ),
                            mapOf(
                                "__typename" to "Cat",
                                "id" to "2",
                                "name" to "Whiskers",
                                "lives" to 9,
                                "mousesPerDay" to 3
                            ),
                            mapOf(
                                "__typename" to "Toy",
                                "id" to "3",
                                "name" to "Ball",
                                "price" to 9.99
                            )
                        )
                    }
                )
            ),
            typeResolvers = mapOf(
                "SearchResult" to TypeResolvers.typename,
                "Pet" to TypeResolvers.typename
            )
        ) {
            check(
                """
                    {
                        searchResults {
                            ... on Pet { id name }
                            ... on Dog { breed goodBoy }
                            ... on Cat { lives mousesPerDay }
                            ... on Toy { id name price }
                        }
                    }
                """
            ) { _, (act) ->
                val data = act.getData<Map<String, List<Map<String, Any?>>>>()
                val results = data["searchResults"]!!
                assertEquals(3, results.size)

                // Verify Dog fields - should include both Pet and Dog fields
                assertEquals("1", results[0]["id"])
                assertEquals("Rover", results[0]["name"])
                assertEquals("Labrador", results[0]["breed"])
                assertEquals(true, results[0]["goodBoy"])

                // Verify Cat fields - should include both Pet and Cat fields
                assertEquals("2", results[1]["id"])
                assertEquals("Whiskers", results[1]["name"])
                assertEquals(9, results[1]["lives"])
                assertEquals(3, results[1]["mousesPerDay"])

                // Verify Toy fields
                assertEquals("3", results[2]["id"])
                assertEquals("Ball", results[2]["name"])
                assertEquals(9.99, results[2]["price"])
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `merges fields with same name but different types in union fragments`() {
        Conformer(
            """
            type Query { sections: [Section!]! }

            union Section = FooSection | BarSection

            type FooSection { items: [FooItem!]! }
            type BarSection { items: [BarItem!]! }
            type BarItem { item: BarSubItem! }
            type FooItem { item: FooSubItem! }
            type BarSubItem { otherData: String! }
            type FooSubItem { data: String! }
        """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "sections" to DataFetcher { _ ->
                        listOf(
                            mapOf(
                                "__typename" to "BarSection",
                                "items" to listOf(mapOf("item" to mapOf("otherData" to "bar data")))
                            ),
                            mapOf(
                                "__typename" to "FooSection",
                                "items" to listOf(mapOf("item" to mapOf("data" to "foo data")))
                            )
                        )
                    }
                )
            ),
            typeResolvers = mapOf("Section" to TypeResolvers.typename)
        ) {
            check(
                """
                    {
                        sections {
                            ... on BarSection { items { item { otherData } } }
                            ... on FooSection { items { item { data } } }
                        }
                    }
                """.trimIndent(),
            ) { _, (act) ->
                val data = act.getData<Map<String, List<Map<String, Any>>>>()
                val results = data["sections"]!!

                assertEquals(2, results.size)

                // Verify BarSection fields
                val barSection = results[0]
                val barItems = barSection["items"] as List<Map<String, Any>>
                assertEquals(1, barItems.size)
                val barItem = barItems[0]
                val barSubItem = barItem["item"] as Map<String, Any>
                assertEquals("bar data", barSubItem["otherData"])

                // Verify FooSection fields
                val fooSection = results[1]
                val fooItems = fooSection["items"] as List<Map<String, Any>>
                assertEquals(1, fooItems.size)
                val fooItem = fooItems[0]
                val fooSubItem = fooItem["item"] as Map<String, Any>
                assertEquals("foo data", fooSubItem["data"])
            }
        }
    }

    @Test
    fun `conditionals -- fields`() {
        Conformer(
            "type Query { a:Int b:Int c:Int }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "a" to DataFetcher { 1 },
                    "b" to DataFetcher { 2 },
                    "c" to DataFetcher { 3 }
                )
            )
        ) {
            check(
                """
                    query(${"$"}skip: Boolean!, ${"$"}include: Boolean!) {
                        a
                        b @skip(if: ${"$"}skip)
                        c @include(if: ${"$"}include)
                    }
                """,
                variables = mapOf("skip" to true, "include" to false)
            ) { _, (act) ->
                assertEquals(mapOf("a" to 1), act.getData())
            }
        }
    }

    @Test
    fun `conditionals -- inline fragments`() {
        Conformer("type Query { x: Int }") {
            // Test various combinations of skip/include directives on inline fragments
            check("{ ... @skip(if:true) { x } }")
            check("{ ... @skip(if:false) { x } }")
            check("{ ... @include(if:true) { x } }")
            check("{ ... @include(if:false) { x } }")

            // Multiple directives on an inline fragment
            check("{ ... @skip(if:false) @include(if:false) { x } }")
            check("{ ... @skip(if:false) @include(if:true) { x } }")
            check("{ ... @skip(if:true) @include(if:false) { x } }")
            check("{ ... @skip(if:true) @include(if:true) { x } }")

            // Effectively repeated directives
            check("{ ... @skip(if:false) { x @skip(if:false) } }")
            check("{ ... @skip(if:false) { x @skip(if:true) } }")
            check("{ ... @skip(if:true) { x @skip(if:false) } }")
            check("{ ... @skip(if:true) { x @skip(if:true) } }")
            check("{ ... @include(if:false) { x @include(if:false) } }")
            check("{ ... @include(if:false) { x @include(if:true) } }")
            check("{ ... @include(if:true) { x @include(if:false) } }")
            check("{ ... @include(if:true) { x @include(if:true) } }")

            // Effectively repeated directives, with variables
            """
                query (${'$'}a:Boolean!, ${'$'}b:Boolean!) {
                    ... @skip(if:${'$'}a) {
                        x @skip(if:${'$'}b)
                    }
                }
            """.trimIndent().let { q ->
                for (a in listOf(true, false)) {
                    for (b in listOf(true, false)) {
                        check(q, mapOf("a" to a, "b" to b))
                    }
                }
            }
        }
    }

    @Test
    fun `conditionals -- fragment spreads`() {
        Conformer("type Query { x: Int }") {
            // Single directives on a fragment spread
            check("fragment F on Query { x } { ...F @skip(if:true) }")
            check("fragment F on Query { x } { ...F @skip(if:false) }")
            check("fragment F on Query { x } { ...F @include(if:true) }")
            check("fragment F on Query { x } { ...F @include(if:false) }")

            // Multiple directives on a fragment spread
            check("fragment F on Query { x } { ...F @skip(if:false) @include(if:false) }")
            check("fragment F on Query { x } { ...F @skip(if:false) @include(if:true) }")
            check("fragment F on Query { x } { ...F @skip(if:true) @include(if:false) }")
            check("fragment F on Query { x } { ...F @skip(if:true) @include(if:true) }")

            // Effectively repeated directives
            check("fragment F on Query { x @skip(if:false) } { ...F @skip(if:false) }")
            check("fragment F on Query { x @skip(if:false) } { ...F @skip(if:true) }")
            check("fragment F on Query { x @skip(if:true) } { ...F @skip(if:false) }")
            check("fragment F on Query { x @skip(if:true) } { ...F @skip(if:true) }")
            check("fragment F on Query { x @include(if:false) } { ...F @include(if:false) }")
            check("fragment F on Query { x @include(if:false) } { ...F @include(if:true) }")
            check("fragment F on Query { x @include(if:true) } { ...F @include(if:false) }")
            check("fragment F on Query { x @include(if:true) } { ...F @include(if:true) }")

            // Effectively repeated skip, with variables
            """
                fragment F on Query { x @skip(if:${'$'}b) }
                query (${'$'}a:Boolean!, ${'$'}b:Boolean!) { ...F @skip(if:${'$'}a) }
            """.trimIndent().let { q ->
                for (a in listOf(true, false)) {
                    for (b in listOf(true, false)) {
                        check(q, mapOf("a" to a, "b" to b))
                    }
                }
            }

            // Effectively repeated include, with variables
            """
                fragment F on Query { x @include(if:${'$'}b) }
                query (${'$'}a:Boolean!, ${'$'}b:Boolean!) { ...F @include(if:${'$'}a) }
            """.trimIndent().let { q ->
                for (a in listOf(true, false)) {
                    for (b in listOf(true, false)) {
                        check(q, mapOf("a" to a, "b" to b))
                    }
                }
            }

            // fragment is spread multiple times with different directives
            check(
                """
                    fragment F on Query { x }
                    { ...F @skip(if:true), ...F }
                """.trimIndent()
            )

            // fragment is spread in the context of skip/include
            check(
                """
                    fragment F on Query { x }
                    { ... @skip(if:true) { ...F } }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `conditionals -- object selections`() {
        Conformer(
            """
                type Query { obj:Obj }
                type Obj { a:Int }
            """,
            resolvers = mapOf(
                "Query" to mapOf("obj" to DataFetcher { emptyMap<String, Any?>() }),
                "Obj" to mapOf("a" to DataFetcher { 1 })
            )
        ) {
            check(
                """
                    query (${'$'}v:Boolean!) {
                       obj @skip(if:${'$'}v) { at:__typename }
                       obj { __typename }
                    }
                """.trimIndent(),
                mapOf("v" to true)
            )
        }
    }

    @Test
    fun `conditionals -- unions and inline fragments`() {
        Conformer(
            """
                type Foo { x: Int }
                type Bar { y: Int }
                union Union = Foo | Bar
                type Query { u(arg:Int): Union }
            """,
            resolvers = mapOf(
                "Query" to mapOf("u" to DataFetcher { emptyMap<String, Any?>() }),
                "Obj" to mapOf("a" to DataFetcher { 1 })
            ),
            typeResolvers = mapOf("Union" to TypeResolvers.const("Foo"))
        ) {
            check(
                """
                    query (${'$'}v: Boolean!) {
                      u(arg: 1) {
                        ... on Foo @skip(if: ${'$'}v) { __typename }
                        ... on Bar { __typename }
                      }
                    }
                """.trimIndent(),
                mapOf("v" to true)
            )
        }
    }

    @Test
    fun `conditionals -- interface spreads`() {
        Conformer(
            """
                interface I { i:Int }
                type A implements I { a:Int, i:Int }
                type Query { i:I }
            """,
            resolvers = mapOf(
                "Query" to mapOf("i" to DataFetcher { emptyMap<String, Any?>() }),
                "A" to mapOf("a" to DataFetcher { 1 }, "i" to DataFetcher { 2 })
            ),
            typeResolvers = mapOf("I" to TypeResolvers.const("A"))
        ) {
            check(
                """
                    query (${'$'}v: Boolean!) {
                      i {
                        ... on I { i }
                        ... on A @skip(if: ${'$'}v) { a }
                      }
                    }
                """.trimIndent(),
                mapOf("v" to true)
            )
        }
    }

    @Test
    fun `conditionals -- repeated root field selections may use conditional directives`() {
        Conformer(
            """
                type Query { x:Int }
                type Mutation { x:Int }
                type Subscription { x:Int }
            """.trimIndent()
        ) {
            check("query { x @include(if:true), x }")
            check("mutation { x @include(if:true), x }")
            check("subscription { x @include(if:true), x }")
        }
    }

    @Test
    fun `execution with field aliases`() {
        Conformer(
            """
                type Query { user(id: ID!): User }
                type User { id: ID! name: String! }
            """,
            resolvers = mapOf(
                "Query" to mapOf(
                    "user" to DataFetcher { env ->
                        val id = env.getArgument<String>("id")
                        mapOf("id" to id, "name" to if (id == "1") "Alice" else "Bob")
                    }
                )
            )
        ) {
            check(
                """
                    {
                        firstUser: user(id: "1") { id name }
                        secondUser: user(id: "2") { id name }
                    }
                """
            ) { _, (act) ->
                assertEquals(
                    mapOf(
                        "firstUser" to mapOf("id" to "1", "name" to "Alice"),
                        "secondUser" to mapOf("id" to "2", "name" to "Bob")
                    ),
                    act.getData()
                )
            }
        }
    }

    @Test
    fun `invokes resolvers the correct number of times based on CollectFields grouping`() {
        Conformer(
            "type Query { x: Int }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { 0 }))
        ) {
            check("{ x x x@skip(if:false) }")

            // unaliased fields can all be collected into the same group, the resolver should be called once
            check(
                """
                    {
                        x
                        x
                        x @skip(if:false)
                        x @include(if:true)
                        ... on Query { x }
                        ... { x }
                        ... F
                    }
                    fragment F on Query { x }
                """.trimIndent(),
            )

            // aliased fields are collected into different groups
            check(
                """
                    {
                        x
                        a:x
                        b:x @skip(if:false)
                        c:x @include(if:true)
                        ... on Query { d:x }
                        ... { e:x }
                        ... F
                    }
                    fragment F on Query { f:x }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `execution with variables and default values`() {
        Conformer(
            "type Query { greeting(name: String = \"World\"): String }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "greeting" to DataFetcher { env ->
                        val name = env.getArgument<String>("name")
                        "Hello, $name!"
                    }
                )
            )
        ) {
            val query = """
                query(${"$"}name: String) {
                    greeting(name: ${"$"}name)
                }
            """.trimIndent()

            // test without providing variables
            check(query) { _, (act) ->
                assertEquals(mapOf("greeting" to "Hello, World!"), act.getData())
            }

            // with a variable value
            check(query, variables = mapOf("name" to "GraphQL")) { _, (act) ->
                assertEquals(mapOf("greeting" to "Hello, GraphQL!"), act.getData())
            }

            // query without a variable or argument
            check("{ greeting }") { _, (act) ->
                assertEquals(mapOf("greeting" to "Hello, World!"), act.getData())
            }
        }
    }

    @Test
    fun `execution fails with missing required variables`() {
        Conformer(
            "type Query { echo(input: String!): String }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "echo" to DataFetcher { it.getArgument<String>("input") }
                )
            )
        ) {
            check(
                "query(\$input: String!) { echo(input: \$input) }",
                checkNoModernErrors = false
            ) { _, (act) ->
                assertEquals(1, act.errors.size)
                assertTrue(act.errors[0].message.contains("Variable 'input' has an invalid value"))
            }
        }
    }

    @Test
    fun `spreads -- fragment spreads are reusable at different depths`() {
        Conformer(
            "type Query { x:Int, q:Query }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { 1 }, "q" to DataFetchers.emptyMap))
        ) {
            check(
                """
                    {
                      ... {
                        ...F
                        q {
                          ...F
                          __typename @include(if: true)
                        }
                      }
                    }
                    fragment F on Query { __typename }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `spreads -- repeated fragment spreads in the same selection are traversed only once`() {
        // see CollectFields wrt handling of FragmentSpreads
        Conformer(
            "type Query { x:Int }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { 1 }))
        ) {
            check(
                """
                    fragment F on Query { x }
                    { ...F, ...F }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `spreads -- repeated fragment spreads may use conditional directives with variables`() {
        Conformer(
            "type Query { x:Int }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { 1 }))
        ) {
            check(
                """
                    query (${'$'}v: Boolean!) {
                      ...F @include(if: ${'$'}v)
                      ...F
                    }
                    fragment F on Query { x }
                """.trimIndent(),
                mapOf("v" to false)
            )
        }
    }

    @Test
    fun `spreads -- abstract-abstract spreads`() {
        Conformer(
            """
                interface I_A { a:Int }
                interface I_B { b:Int }
                type Obj implements I_A & I_B { a:Int, b:Int }
                type Query {  b(b:Int): I_B }
            """.trimIndent(),
            resolvers = mapOf(
                "Query" to mapOf("b" to DataFetchers.emptyMap),
                "Obj" to mapOf("a" to DataFetcher { 1 }, "b" to DataFetcher { 2 }),
            ),
            typeResolvers = mapOf(
                "I_A" to TypeResolvers.const("Obj"),
                "I_B" to TypeResolvers.const("Obj"),
            )
        ) {
            check(
                """
                    {
                      b {
                        ... on I_A { a }
                      }
                    }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `spreads -- ordered merge of conditional fragment spreads`() {
        Conformer(
            "type Query { x:Int, q:Query }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { 1 }, "q" to DataFetchers.emptyMap))
        ) {
            check(
                """
                    query (${'$'}v:Boolean!) {
                      ...F@skip(if: ${'$'}v)
                      q { b: x }
                      ...F
                    }

                    fragment F on Query {
                      q { a: x }
                    }
                """.trimIndent(),
                mapOf("v" to false)
            )
        }
    }

    @Test
    fun `field merging -- simple subselections`() {
        Conformer(
            "type Query { x:Int, q:Query }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { 1 }, "q" to DataFetchers.emptyMap)),
        ) {
            check(
                """
                    {
                      q { a:x }
                      q { b:x }
                    }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `field merging -- deep objects`() {
        Conformer(
            "type Query { x:Int, q:Query }",
            resolvers = mapOf(
                "Query" to mapOf(
                    "q" to DataFetchers.emptyMap,
                    "x" to DataFetcher { 1 }
                )
            )
        ) {
            check(
                """
                    {
                      q { x }
                      q @skip(if: false) {
                        q { x }
                      }
                      q {
                        q {
                          __typename
                          q {
                            __typename
                          }
                        }
                      }
                    }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `field merging -- transitive fragment spread`() {
        Conformer("type Query { x:Int }") {
            check(
                """
                    { ...F1 ...F2 }
                    fragment F1 on Query { __typename }
                    fragment F2 on Query { ...F1 }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `field merging -- fragment spreads in subselections`() {
        Conformer(
            "type Query { q:Query, x:Int }",
            resolvers = mapOf(
                "Query" to mapOf("x" to DataFetcher { 1 }, "q" to DataFetchers.emptyMap),
            )
        ) {
            check(
                """
                    {
                      q { ...F }
                      q { ...F }
                    }
                    fragment F on Query { x }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `field merging -- transitive fragment spreads in subselections`() {
        Conformer(
            "type Query { q:Query }",
            resolvers = mapOf(
                "Query" to mapOf("x" to DataFetcher { 1 }, "q" to DataFetchers.emptyMap),
            )
        ) {
            check(
                """
                    { ...F }
                    fragment F on Query {
                      q { ...G }
                      q { ...G }
                    }
                    fragment G on Query { __typename }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `field merging -- constrained transitive fragment spreads in subselections`() {
        Conformer(
            "type Query { q:Query }",
            resolvers = mapOf(
                "Query" to mapOf("x" to DataFetcher { 1 }, "q" to DataFetchers.emptyMap),
            )
        ) {
            check(
                """
                    query (${'$'}var:Boolean!) { ...F }
                    fragment F on Query {
                      q { ...G }
                      q { ...G @skip(if:${'$'}var) }
                      q { ...G }
                      q { ...G @skip(if:${'$'}var) }
                      q { ...G }
                    }
                    fragment G on Query { __typename }
                """.trimIndent(),
                mapOf("var" to true)
            )
        }
    }

    @Test
    fun `field merging -- constrained subselections in conditional fragment`() {
        Conformer(
            "type Query { q:Query, x:Int }",
            resolvers = mapOf(
                "Query" to mapOf("x" to DataFetcher { 1 }, "q" to DataFetchers.emptyMap),
            )
        ) {
            check(
                """
                    query (${'$'}var: Boolean!) {
                      ...F
                      q { a:x }
                    }

                    fragment F on Query {
                      q { x }
                      ... on Query @include(if: ${'$'}var) {
                        q { b:x }
                      }
                    }
                """.trimIndent(),
                mapOf("var" to true)
            )
        }
    }

    @Test
    fun `datafetcher returns both data and errors`() {
        Conformer(
            """
                type Obj { x:Int, y:Int }
                type Query { obj: Obj }
            """.trimIndent(),
            resolvers = mapOf(
                "Query" to mapOf(
                    "obj" to DataFetcher {
                        DataFetcherResult.newResult<Map<String, Any?>>()
                            .data(mapOf("x" to null, "y" to 1))
                            .error(
                                GraphQLError.newError()
                                    .path(it.executionStepInfo.path.segment("x"))
                                    .message("err")
                                    .build()
                            )
                            .build()
                    }
                ),
            )
        ) {
            check("{ obj { x y } }", checkNoModernErrors = false, checkFetchesEqual = false)
        }
    }
}
