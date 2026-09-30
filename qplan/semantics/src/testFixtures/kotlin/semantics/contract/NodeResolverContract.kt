package semantics.contract

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import model.EngineErrorData
import model.EngineIDResult
import model.EngineOutputListData
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.RootFieldReferenceData
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.outputValue
import model.requireField
import model.requireObjectField
import model.requireType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf
import org.junit.jupiter.api.Test
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.ResolverInvocationObservation
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * Contract for source fields whose node outputs resolve through root references to `Query.node`.
 */
interface NodeResolverContract : ResolverContract {
    @Test
    fun `retains successor demand beneath a node reference`() {
        if (this !is ObjectFragmentResolverContract) return
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Node { id: ID! }
                    type Viewer { item: Item!, result: String! }
                    type Leaf { value: String! }
                    type Item implements Node { id: ID!, leaf: Leaf! }
                    type Query { viewer: Viewer! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val viewer = schema.loweredSchema.requireObjectField("Query", "viewer")
                    val result = schema.loweredSchema.requireObjectField("Viewer", "result")
                    val value = schema.loweredSchema.requireObjectField("Leaf", "value")
                    mapOf(
                        viewer to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Viewer") {
                                    "item" setTo
                                        schema.loweredSchema.objectOf("Item") {
                                            "id" setTo "item-1"
                                        }
                                }
                            },
                        value to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Leaf")) { _, _ ->
                                "resolved"
                            },
                        result to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Result on Viewer { item { leaf { value } } }",
                                ),
                            ) { input, _ ->
                                val item =
                                    assertIs<EngineObjectData.Sync>(input.outputValue("item"))
                                val leaf =
                                    assertIs<EngineObjectData.Sync>(item.outputValue("leaf"))
                                leaf.outputValue("value")
                            },
                    )
                },
                nodeResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.contractObjectType("Item") to
                            nodeResolverOf { id ->
                                schema.loweredSchema.objectOf("Item") {
                                    "id" setTo id
                                    "leaf" setTo schema.loweredSchema.objectOf("Leaf")
                                }
                            },
                    )
                },
            )
        val schema = testWorld.schema
        val result = resolveAndValidate(testWorld, "query { viewer { result } }")
        val viewer =
            assertIs<ObjectEngineResult>(
                result.getCell(schema.contractKey("Query", "viewer")).get(),
            )
        assertEquals(
            "resolved",
            viewer.getCell(schema.contractKey("Viewer", "result")).get(),
        )
    }

    @Test
    fun `node resolver root reference retains the originating id`() {
        val nodeApplications = AtomicInteger()
        val targetApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Node { id: ID! }
                    interface ReferencedFoo { id: ID!, value: String! }
                    type Foo implements Node & ReferencedFoo { id: ID!, value: String! }
                    type Query { foo: Foo!, referencedFoo: ReferencedFoo! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val foo = schema.loweredSchema.requireObjectField("Query", "foo")
                    val referencedFoo = schema.loweredSchema.requireObjectField("Query", "referencedFoo")
                    mapOf(
                        foo to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Foo") { "id" setTo "source-id" }
                            },
                        referencedFoo to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                targetApplications.incrementAndGet()
                                schema.loweredSchema.objectOf("Foo") {
                                    "id" setTo "target-id"
                                    "value" setTo "from-reference"
                                }
                            },
                    )
                },
                nodeResolvers = { schema ->
                    val foo = schema.loweredSchema.requireType("Foo") as ViaductSchema.Object
                    val referencedFoo = schema.loweredSchema.requireObjectField("Query", "referencedFoo")
                    mapOf(
                        foo to
                            nodeResolverOf { id ->
                                nodeApplications.incrementAndGet()
                                assertEquals("source-id", id)
                                RootFieldReferenceData.of(
                                    path = listOf(referencedFoo),
                                    arguments = emptyMap(),
                                )
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val result = resolveAndValidate(testWorld, "query { foo { id value } }")
        val resolvedFoo =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "foo")).get(),
            )

        assertEquals(
            "from-reference",
            resolvedFoo.getCell(world.schema.contractKey("Foo", "value")).get(),
        )
        assertEquals(
            EngineIDResult.of("source-id"),
            resolvedFoo.getCell(world.schema.contractKey("Foo", "id")).get(),
        )
        assertEquals(1, nodeApplications.get())
        assertEquals(1, targetApplications.get())
    }

    @Test
    fun `awaits completion for node in required selection set`() {
        val failedNodeCompleted = AtomicBoolean()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Node { id: ID! }

                    type Query { baz: Baz! }

                    type Baz implements Node {
                      id: ID!
                      name: String!
                      anotherBaz: Baz!
                      z: Int!
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.contractObjectType("Baz") to
                            nodeResolverOf { id ->
                                when (id) {
                                    "1" ->
                                        schema.loweredSchema.objectOf("Baz") {
                                            "id" setTo id
                                        }
                                    "2" -> {
                                        failedNodeCompleted.set(true)
                                        EngineErrorData.of()
                                    }
                                    else -> error("Unexpected Baz ID: $id")
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val baz = schema.loweredSchema.requireObjectField("Query", "baz")
                    val anotherBaz = schema.loweredSchema.requireObjectField("Baz", "anotherBaz")
                    val z = schema.loweredSchema.requireObjectField("Baz", "z")
                    mapOf(
                        baz to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Baz") {
                                    "id" setTo "1"
                                }
                            },
                        anotherBaz to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Baz")) { _, _ ->
                                schema.loweredSchema.objectOf("Baz") {
                                    "id" setTo "2"
                                }
                            },
                        z to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Z on Baz { anotherBaz { name } }",
                                ),
                            ) { input, _ ->
                                input.get("anotherBaz")
                                5
                            },
                    )
                },
            )
        val schema = testWorld.schema
        val result = resolveAndValidate(testWorld, "query { baz { z } }")
        val baz =
            assertIs<ObjectEngineResult>(
                result.getCell(schema.contractKey("Query", "baz")).get(),
            )

        assertTrue(failedNodeCompleted.get())
        assertIs<ErrorEngineResult>(
            baz.getCell(schema.contractKey("Baz", "z")).get(),
        )
    }

    @Test
    fun `resolves an empty query through field and node resolvers`() {
        val invocationObserver = object : ResolverApplicationArguments() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val input = observation.input
                if (
                    field.containingDef.name == "Query" &&
                    field.name.startsWith("viewer") ||
                    field.containingDef.name == "User" &&
                    field.name == "greeting"
                ) {
                    require(input.hasExactlyFields())
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      viewer(id: ID!): User!
                        @resolver(result: {id: "idFrom(${'$'}id)"})
                    }

                    type User implements Node
                      @nodeResolver(result: [{id: "1", result: {name: 7}}]) {
                      id: ID!
                      name: Int!
                      greeting(prefix: Int!): Int!
                        @resolver(result: "sumplus1(${'$'}prefix)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        resolveAndValidate(
            testWorld,
            """
                query {
                  viewer(id: "1") {
                    id
                    name
                    greeting(prefix: 5)
                  }
                }
            """.trimIndent(),
            resolverObserver = invocationObserver,
        )
        invocationObserver.assertArguments(
            world.schema.requireObjectField("Query", "viewer"),
            mapOf("id" to "1"),
        )
        invocationObserver.assertArguments(
            world.schema.requireObjectField("User", "greeting"),
            mapOf("prefix" to 5),
        )
    }

    @Test
    fun `resolves a nested passive node through Query node`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Node { id: ID! }
                    type Profile implements Node { id: ID!, name: String! }
                    type Card { profile: Profile! }
                    type Viewer { card: Card! }
                    type Query { viewer: Viewer! }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.contractObjectType("Profile") to
                            nodeResolverOf { id ->
                                schema.loweredSchema.objectOf("Profile") {
                                    "id" setTo id
                                    "name" setTo "Ada"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireField("Query", "viewer") to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                            ) { input, _ ->
                                require(input.hasExactlyFields())
                                schema.loweredSchema.objectOf("Viewer") {
                                    "card" setTo
                                        objectOf("Card") {
                                            "profile" setTo
                                                objectOf("Profile") {
                                                    "id" setTo "profile-1"
                                                }
                                        }
                                }
                            },
                    )
                },
            )
        val schema = testWorld.schema
        val result =
            resolveAndValidate(
                testWorld,
                "query { viewer { card { profile { id name } } } }",
            )
        val viewer =
            assertIs<ObjectEngineResult>(
                result.getCell(schema.contractKey("Query", "viewer")).get(),
            )
        val card =
            assertIs<ObjectEngineResult>(
                viewer.getCell(schema.contractKey("Viewer", "card")).get(),
            )
        val profileKey = schema.contractKey("Card", "profile")
        val profile =
            assertIs<ObjectEngineResult>(
                card.getCell(profileKey).get(),
            )

        assertEquals(expectedPassiveResultKeys(card.type, setOf(profileKey)), card.keys)
        assertEquals(
            EngineIDResult.of("profile-1"),
            profile.getCell(schema.contractKey("Profile", "id")).get(),
        )
        assertEquals(
            "Ada",
            profile.getCell(schema.contractKey("Profile", "name")).get(),
        )
    }

    @Test
    fun `dispatches argument-bearing abstract node lists`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Node { id: ID! }
                    type User implements Node { id: ID!, name: String! }
                    type Admin implements Node { id: ID!, level: Int! }
                    type Query { nodes(group: String!): [Node!]! }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.contractObjectType("User") to
                            nodeResolverOf { id ->
                                schema.loweredSchema.objectOf("User") {
                                    "id" setTo id
                                    "name" setTo "user-$id"
                                }
                            },
                        schema.loweredSchema.contractObjectType("Admin") to
                            nodeResolverOf { id ->
                                schema.loweredSchema.objectOf("Admin") {
                                    "id" setTo id
                                    "level" setTo 7
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val nodes = schema.loweredSchema.requireField("Query", "nodes")
                    mapOf(
                        nodes to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                            ) { input, arguments ->
                                require(input.hasExactlyFields())
                                val group =
                                    arguments.fieldValues.getValue("group") as String
                                listOf(
                                    schema.loweredSchema.objectOf("User") {
                                        "id" setTo "$group-user"
                                    },
                                    schema.loweredSchema.objectOf("Admin") {
                                        "id" setTo "$group-admin"
                                    },
                                )
                            },
                    )
                },
            )
        val schema = testWorld.schema
        val result =
            resolveAndValidate(
                testWorld,
                """
                query {
                  first: nodes(group: "first") {
                    id
                    ... on User { name }
                    ... on Admin { level }
                  }
                  second: nodes(group: "second") { id }
                }
                """.trimIndent(),
            )
        val nodesField = schema.requireObjectField("Query", "nodes")
        val firstKey = ObjectEngineResult.GroundKey.of(nodesField, mapOf("group" to "first"))
        val secondKey = ObjectEngineResult.GroundKey.of(nodesField, mapOf("group" to "second"))

        assertEquals(
            setOf(firstKey, secondKey),
            result.keys,
        )
        val first = assertIs<ListEngineResult>(result.getCell(firstKey).get())
        val expectedTypes = listOf("User", "Admin")
        assertEquals(
            expectedTypes,
            first.zip(expectedTypes).map { (cell, _) ->
                assertIs<ObjectEngineResult>(cell.get()).type.name
            },
        )
    }

    @Test
    fun `dispatches every nested node-list reference occurrence`() {
        val observedFields = ConcurrentLinkedQueue<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                observedFields.add(field.name)
            }
        }
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Node { id: ID! }
                    type User implements Node { id: ID!, name: String! }
                    type Query { matrix: [[User!]!]! }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.contractObjectType("User") to
                            nodeResolverOf { id ->
                                schema.loweredSchema.objectOf("User") {
                                    "id" setTo id
                                    "name" setTo "user-$id"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val matrix = schema.loweredSchema.requireField("Query", "matrix")

                    fun row(vararg ids: String): EngineOutputListData =
                        ids.map { id ->
                            schema.loweredSchema.objectOf("User") {
                                "id" setTo id
                            }
                        }
                    mapOf(
                        matrix to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                function = { _, _ ->
                                    listOf(row("a", "b"), row("c"))
                                },
                            ),
                    )
                },
            )
        val schema = testWorld.schema
        val result =
            resolveAndValidate(testWorld, "query { matrix { id name } }", resolverObserver = invocationObserver)
        val matrix =
            assertIs<ListEngineResult>(
                result.getCell(schema.contractKey("Query", "matrix")).get(),
            )
        val resolvedTypes =
            matrix.map { row ->
                assertIs<ListEngineResult>(row.get()).map { nodeCell ->
                    assertIs<ObjectEngineResult>(nodeCell.get()).type.name
                }
            }.flatten()

        assertEquals(listOf("User", "User", "User"), resolvedTypes)
        assertEquals(3, observedFields.count { it == "node" })
    }
}
