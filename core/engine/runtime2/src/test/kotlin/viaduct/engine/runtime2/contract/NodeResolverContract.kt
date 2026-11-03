@file:Suppress("DEPRECATION")

package viaduct.engine.runtime2.contract

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.airbnb.node.NodeResolverContext
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.IDEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.NodeReferenceIdentity
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.additionalContextForResolver
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.nodeResolverOf
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.nodeReferenceOf
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOrchestrationTask
import viaduct.graphql.schema.ViaductSchema

/**
 * Contract for source fields whose node outputs resolve through root references to `Query.node`.
 */
interface NodeResolverContract : ResolverContract {
    @Test
    fun `reference tails restore opted-in context without interpreting ordinary maps or requiring id demand`() {
        val fields = mapOf("id" to "correlation-id", "requestTag" to "trace-1")
        for (retained in listOf(NodeResolverContext(fields), fields)) {
            for (idSelection in listOf("", "id")) {
                val observedContexts = ConcurrentLinkedQueue<Any>()
                val observer = object : ResolutionObserver {
                    override val onQueryOERPrepared: (SharedOrchestrationTask<*>, Int?) -> Unit = { task, _ ->
                        if (task.objectOER.occurrence.target.type.name == "User") {
                            val context = requireNotNull(task.objectOER.source.additionalContextForResolver)
                            assertSame(context, task.objectOER.occurrence.additionalContextForResolver)
                            observedContexts.add(context)
                        }
                    }
                }
                val world = TestWorld.fromSDL(
                    selectiveResolvers = selectiveResolvers,
                    schemaSDL = "interface Node { id: ID! } type User implements Node { id: ID! label: String } type Query { user: User }",
                    fieldResolvers = { schemas ->
                        val schema = schemas.loweredSchema
                        mapOf(
                            schema.requireObjectField("Query", "user") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.nodeReferenceOf("User", "original")
                            },
                            schema.requireObjectField("User", "label") to fieldResolverOf(schema.emptyFragmentOf("User")) { _, _ -> "healthy" },
                        )
                    },
                    nodeResolvers = { schemas ->
                        val schema = schemas.loweredSchema
                        val type = schema.requireType("User") as ViaductSchema.Object
                        mapOf(
                            type to nodeResolverOf { id ->
                                if (id == "original") schema.nodeReferenceOf("User", "forwarded") else engineObjectDataOf(type, additionalContextForResolver = retained)
                            }
                        )
                    },
                )

                val result = resolveAndValidate(world, "{ user { $idSelection label } }", resolutionObserver = observer)
                val user = assertIs<ObjectEngineResult>(result.getCell(world.schema.contractKey("Query", "user")).get())
                assertEquals("healthy", user.getCell(world.schema.contractKey("User", "label")).get())
                if (idSelection.isNotEmpty()) assertEquals(IDEngineResult.of("original"), user.getCell(world.schema.contractKey("User", "id")).get())
                val context = observedContexts.single()
                if (retained is NodeResolverContext) {
                    val restored = assertIs<NodeResolverContext>(context)
                    assertEquals(NodeReferenceIdentity(world.schema.requireType("User") as ViaductSchema.Object, "original"), restored.identity)
                    assertSame(fields, restored.retainedContext)
                    assertEquals(null, retained.identity)
                } else {
                    assertSame(retained, context)
                }
            }
        }
        assertEquals("correlation-id", fields["id"])
    }

    @Test
    fun `retains successor demand beneath a node reference`() {
        if (this !is ObjectFragmentResolverContract) return
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      viewer: Viewer! @resolver(result: {item: {id: "item-1"}})
                    }
                    type Viewer {
                      item: Item!
                      result: Int! @resolver(of: "item { leaf { value } }", result: "value(item.leaf.value)")
                    }
                    type Item implements Node
                      @nodeResolver(result: [{id: "item-1", result: {leaf: {}}}]) {
                      id: ID!
                      leaf: Leaf!
                    }
                    type Leaf { value: Int! @resolver(result: 7) }
                    """.trimIndent(),
            )
        val schema = testWorld.schema
        val result = resolveAndValidate(testWorld, "query { viewer { result } }")
        val viewer =
            assertIs<ObjectEngineResult>(
                result.getCell(schema.contractKey("Query", "viewer")).get(),
            )
        assertEquals(
            7,
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
                                schema.loweredSchema.nodeReferenceOf("Foo", "source-id")
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
            IDEngineResult.of("source-id"),
            resolvedFoo.getCell(world.schema.contractKey("Foo", "id")).get(),
        )
        assertEquals(1, nodeApplications.get())
        assertEquals(1, targetApplications.get())
    }

    @Test
    fun `resolves an empty query through field and node resolvers`() {
        val invocationObserver = object : ResolverApplicationArguments() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
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
            resolutionObserver = invocationObserver,
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
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                if (observation.field.name == "viewer") {
                    require(observation.input.hasExactlyFields())
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      viewer: Viewer! @resolver(result: {card: {profile: {id: "profile-1"}}})
                    }
                    type Viewer { card: Card! }
                    type Card { profile: Profile! }
                    type Profile implements Node
                      @nodeResolver(result: [{id: "profile-1", result: {name: 7}}]) {
                      id: ID!
                      name: Int!
                    }
                    """.trimIndent(),
            )
        val schema = testWorld.schema
        val result =
            resolveAndValidate(
                testWorld,
                "query { viewer { card { profile { id name } } } }",
                resolutionObserver = invocationObserver,
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
            IDEngineResult.of("profile-1"),
            profile.getCell(schema.contractKey("Profile", "id")).get(),
        )
        assertEquals(
            7,
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
                                    schema.loweredSchema.nodeReferenceOf("User", "$group-user"),
                                    schema.loweredSchema.nodeReferenceOf("Admin", "$group-admin"),
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
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                val field = observation.field
                observedFields.add(field.name)
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      matrix: [[User!]!]! @resolver(result: [[{id: "a"}, {id: "b"}], [{id: "c"}]])
                    }
                    type User implements Node
                      @nodeResolver(result: [
                        {id: "a", result: {name: 1}},
                        {id: "b", result: {name: 2}},
                        {id: "c", result: {name: 3}}
                      ]) {
                      id: ID!
                      name: Int!
                    }
                    """.trimIndent(),
            )
        val schema = testWorld.schema
        val result =
            resolveAndValidate(testWorld, "query { matrix { id name } }", resolutionObserver = invocationObserver)
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
