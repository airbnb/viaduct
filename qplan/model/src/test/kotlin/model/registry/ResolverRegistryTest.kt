package model.registry

import kotlinx.coroutines.runBlocking

import viaduct.graphql.schema.ViaductSchema

import model.requireQueryTypeDef
import model.requireObjectField
import model.requireField
import model.requireType
import model.Arguments
import model.EngineErrorData
import model.EngineObjectDataEntry
import model.Fragment
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.Selection
import model.SelectionForest
import model.RootFieldReferenceData
import model.nodeReferenceIdentityOrNull
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.objectOf
import model.outputValue
import model.schemaType
import model.selectionForestOf
import model.testing.FieldResolverDefinition
import model.testing.GJSchema
import model.testing.TestWorld
import model.testing.testRoot
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf
import model.testing.resolverRegistryOf
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.api.EngineObjectData

class ResolverRegistryTest {
    @Test
    fun `selective field resolver receives demand without output projection`() = runBlocking {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      name: String!
                      age: Int!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
            )
        val schema = world.schema
        val query = schema.requireQueryTypeDef()
        val userField = schema.requireObjectField("Query", "user")
        val demand =
            schema.fragmentFrom(
                "fragment ignored on User { name }",
            ).subselections
        var observedDemand: SelectionForest? = null
        var observedExecutionContext: ResolutionExecutionContext? = null
        val resolver =
            FieldResolver.ofSelective(
                field = userField,
                fragmentTemplates =
                    ResolverFragmentTemplates(
                        objectFragmentTemplate = materializeSelectionForestOf(),
                        queryFragmentTemplate = materializeSelectionForestOf(),
                    ),
                queryType = query,
                function = { _, _, _, selections, executionContext ->
                    observedDemand = selections
                    observedExecutionContext = executionContext
                    schema.objectOf("User") {
                        "name" setTo "Ada"
                        "age" setTo 37
                    }
                },
            )

        val output =
            resolver(
                input = engineObjectDataOf(query),
                arguments = Arguments.Resolved.of(userField, emptyMap()),
                selections = demand,
                selectiveResolvers = world.assumptions.selectiveResolvers,
                executionContext = ResolutionExecutionContext.Unsupported,
            )

        assertSame(demand, observedDemand)
        assertSame(ResolutionExecutionContext.Unsupported, observedExecutionContext)
        assertEquals(setOf("name", "age"), assertIs<EngineObjectData.Sync>(output).getSelections().toSet())
    }

    @Test
    fun `nonselective field resolver factory projects output in selective worlds`() = runBlocking {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      name: String!
                      age: Int!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
            )
        val schema = world.schema
        val query = schema.requireQueryTypeDef()
        val userField = schema.requireObjectField("Query", "user")
        val resolver =
            FieldResolver.of(
                field = userField,
                fragmentTemplates =
                    ResolverFragmentTemplates(
                        objectFragmentTemplate = materializeSelectionForestOf(),
                        queryFragmentTemplate = materializeSelectionForestOf(),
                    ),
                queryType = query,
                function = { _, _, _, _ ->
                    schema.objectOf("User") {
                        "name" setTo "Ada"
                        "age" setTo 37
                    }
                },
            )

        val output =
            resolver(
                input = engineObjectDataOf(query),
                arguments = Arguments.Resolved.of(userField, emptyMap()),
                selections =
                    schema.fragmentFrom(
                        "fragment ignored on User { name }",
                    ).subselections,
                selectiveResolvers = world.assumptions.selectiveResolvers,
                executionContext = ResolutionExecutionContext.Unsupported,
            )

        assertEquals(setOf("name"), assertIs<EngineObjectData.Sync>(output).getSelections().toSet())
    }

    @Test
    fun `selection-aware nonselective resolver receives demand and projects output`() = runBlocking {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      name: String!
                      age: Int!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
            )
        val schema = world.schema
        val query = schema.requireQueryTypeDef()
        val userField = schema.requireObjectField("Query", "user")
        val demand =
            schema.fragmentFrom(
                "fragment ignored on User { name }",
            ).subselections
        var observedDemand: SelectionForest? = null
        val resolver =
            FieldResolver.ofSelectionAwareNonselective(
                field = userField,
                fragmentTemplates =
                    ResolverFragmentTemplates(
                        objectFragmentTemplate = materializeSelectionForestOf(),
                        queryFragmentTemplate = materializeSelectionForestOf(),
                    ),
                queryType = query,
                function = { _, _, _, selections, _ ->
                    observedDemand = selections
                    schema.objectOf("User") {
                        "name" setTo "Ada"
                        "age" setTo 37
                    }
                },
            )

        val output =
            resolver(
                input = engineObjectDataOf(query),
                arguments = Arguments.Resolved.of(userField, emptyMap()),
                selections = demand,
                selectiveResolvers = world.assumptions.selectiveResolvers,
                executionContext = ResolutionExecutionContext.Unsupported,
            )

        assertSame(demand, observedDemand)
        assertEquals(setOf("name"), assertIs<EngineObjectData.Sync>(output).getSelections().toSet())
    }

    @Test
    fun `lowers node and field resolvers to field coordinates`() = runBlocking {
        val observedFields = mutableListOf<String>()
        val world =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = { schema ->
                    val user = schema.requireType("User") as ViaductSchema.Object
                    mapOf(
                        user to
                            nodeResolverOf { id ->
                                observedFields += "node"
                                assertEquals("42", id)
                                schema.objectOf("User") {
                                    "id" setTo "lookup-id"
                                    "name" setTo "Ada"
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    val userField = schema.requireField("Query", "user")
                    val queryFragment = schema.emptyFragmentOf("Query")
                    mapOf<ViaductSchema.Field, FieldResolverDefinition>(
                        userField to
                            fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { parent, arguments ->
                                    observedFields += "user"
                                    assertEquals(schema.requireQueryTypeDef(), parent.schemaType)
                                    assertTrue(parent.getSelections().none())
                                    assertTrue(arguments.fieldValues.isEmpty())
                                    schema.objectOf("User") {
                                        "id" setTo "42"
                                    }
                                },
                            ),
                    )
                },
            )
        val schema = world.schema
        val query = schema.objectOf("Query")
        val user =
            schema.objectOf("User") {
                "id" setTo "42"
                "name" setTo "Ada"
            }
        val userField = schema.requireObjectField("Query", "user")
        val queryNode = schema.requireObjectField("Query", "node")
        val registry = world.resolverRegistry
        val assumptions = world.assumptions

        assertEquals(registry, assumptions.resolverRegistry)
        assertTrue(userField in registry)
        assertTrue(queryNode in registry)
        assertTrue(registry.mayDemandFrom(userField).isEmpty())
        assertTrue(registry.mayDemandFrom(queryNode).isEmpty())
        val reference =
            registry
                .resolver(userField)(
                    input = query,
                    arguments = Arguments.Resolved.of(userField, emptyMap()),
                    selectiveResolvers = false,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
        val nodeReference = assertIs<RootFieldReferenceData>(reference)
        assertEquals("User", nodeReference.nodeReferenceIdentityOrNull()?.type?.name)
        assertEquals("42", nodeReference.nodeReferenceIdentityOrNull()?.id)
        val nodeValue =
            assertIs<EngineObjectData.Sync>(
                registry.resolver(queryNode)(
                    input = query,
                    arguments = nodeReference.arguments,
                    selections =
                        schema.fragmentFrom(
                            """
                            fragment ignored on User {
                              id
                              name
                            }
                            """.trimIndent(),
                        ).subselections,
                    selectiveResolvers = assumptions.selectiveResolvers,
                    executionContext = ResolutionExecutionContext.Unsupported,
                ),
        )
        assertEquals(user.schemaType, nodeValue.schemaType)
        assertEquals(user.getSelections().toSet(), nodeValue.getSelections().toSet())
        user.getSelections().forEach { selection ->
            assertEquals(user.get(selection), nodeValue.get(selection))
        }
        assertEquals(listOf("user", "node"), observedFields)
    }

    @Test
    fun `field resolver receives response-preserving query fragment value`() = runBlocking {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      source: Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val query = schema.requireQueryTypeDef()
                    val source = schema.requireObjectField("Query", "source")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          aliased: source
                                        }
                                        """.trimIndent(),
                                    ),
                            ) { _, queryValue, _ ->
                                assertEquals(query, queryValue.schemaType)
                                queryValue.get("aliased")
                            },
                    )
                },
            )
        val schema = world.schema
        val query = schema.requireQueryTypeDef()
        val source = schema.requireObjectField("Query", "source")
        val consumer = schema.requireObjectField("Query", "consumer")
        val resolver = world.resolverRegistry.resolver(consumer)
        val queryValue =
            engineObjectDataOf(
                schemaType = query,
                fields =
                    listOf(
                        EngineObjectDataEntry.of(
                            selection = "aliased",
                            field = source,
                            value = 7,
                        ),
                    ),
            )

        val result =
            resolver(
                input = engineObjectDataOf(query),
                queryValue = queryValue,
                arguments = Arguments.Resolved.of(consumer, emptyMap()),
                selectiveResolvers = world.assumptions.selectiveResolvers,
                executionContext = ResolutionExecutionContext.Unsupported,
            )

        assertEquals(7, result)
        val resolverOccurrenceId = ResolverOccurrenceId.at(schema.testRoot(), emptyList())
        assertEquals(
            "aliased",
            resolver
                .instantiateQueryMaterializationSelections(resolverOccurrenceId)
                .single()
                .responseKey,
        )
    }

    @Test
    fun `root query input is an empty query object`() {
        val fixture = Fixture()
        val root = fixture.assumptions.resolverRegistry.createRootQueryInput()

        assertEquals(fixture.schema.requireQueryTypeDef(), root.schemaType)
        assertEquals(emptySet(), root.getSelections().toSet())
    }

    @Test
    fun `preserves every Node source list layer`() {
        val schema =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type User implements Node {
                      id: ID!
                    }

                    type Query {
                      user: User!
                      users: [User!]!
                      matrix: [[User!]!]!
                    }
                    """.trimIndent(),
            ).schema

        assertNotNull(schema.requireQueryTypeDef().field("user"))
        assertNotNull(schema.requireQueryTypeDef().field("users"))
        assertNotNull(schema.requireQueryTypeDef().field("matrix"))
        val user = schema.requireType("User") as ViaductSchema.Object
        val node = schema.requireType("Node") as ViaductSchema.Interface
        assertEquals(setOf(user), node.possibleObjectTypes)
        val matrix = schema.requireField("Query", "matrix")
        val inner = checkNotNull(matrix.type.unwrapList())
        val element = checkNotNull(inner.unwrapList())
        assertEquals(user, element.baseTypeDef)
        assertFalse(matrix.type.isNullable)
        assertFalse(inner.isNullable)
        assertFalse(element.isNullable)
    }

    @Test
    fun `Query node dispatch keeps producer arguments separate`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type User implements Node {
                      id: ID!
                    }

                    type Query {
                      seed: ID!
                      user(id: ID!): User!
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val user = schema.requireType("User") as ViaductSchema.Object
                    mapOf(user to nodeResolverOf { _: String -> error("Not invoked") })
                },
                fieldResolvers = { schema ->
                    val user = schema.requireField("Query", "user")
                    mapOf(
                        schema.requireField("Query", "seed") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ -> error("Not invoked") },
                            ),
                        user to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        "fragment ignored on Query { seed }",
                                    ),
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        val schema = world.schema
        val producer = schema.requireObjectField("Query", "user")
        val queryNode = schema.requireObjectField("Query", "node")

        assertEquals(setOf("id"), producer.args.mapTo(linkedSetOf(), ViaductSchema.FieldArg::name))
        assertTrue(world.resolverRegistry.resolver(producer).variables.isEmpty())
        val nodeResolver = world.resolverRegistry.resolver(queryNode)
        assertTrue(nodeResolver.variables.isEmpty())
        assertEquals(setOf("id"), queryNode.args.mapTo(linkedSetOf(), ViaductSchema.FieldArg::name))
        assertTrue(nodeResolver.objectFragment.isEmpty())
    }

    @Test
    fun `field resolvers return the complete nullable output-value algebra`() = runBlocking {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      scalar: String!
                      list: [String]
                      nullable: String
                      failed: String
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val fragment = schema.emptyFragmentOf("Query")
                    mapOf<ViaductSchema.Field, FieldResolverDefinition>(
                        schema.requireField("Query", "scalar") to
                            fieldResolverOf(fragment) { _, _ -> "value" },
                        schema.requireField("Query", "list") to
                            fieldResolverOf(fragment) { _, _ ->
                                listOf("value", null)
                            },
                        schema.requireField("Query", "nullable") to
                            fieldResolverOf(fragment) { _, _ -> null },
                        schema.requireField("Query", "failed") to
                            fieldResolverOf(fragment) { _, _ -> EngineErrorData.of() },
                    )
                },
            )
        val schema = world.schema
        val parent = schema.objectOf("Query")
        val outputs =
            listOf("scalar", "list", "nullable", "failed").associateWith { fieldName ->
                val field = schema.requireObjectField("Query", fieldName)
                world.resolverRegistry
                    .resolver(field)(
                        input = parent,
                        arguments = Arguments.Resolved.of(field, emptyMap()),
                        selections = selectionForestOf(),
                        selectiveResolvers = world.assumptions.selectiveResolvers,
                        executionContext = ResolutionExecutionContext.Unsupported,
                    )
            }

        assertEquals("value", outputs.getValue("scalar"))
        assertEquals(
            listOf("value", null),
            outputs.getValue("list"),
        )
        assertEquals(null, outputs.getValue("nullable"))
        assertIs<EngineErrorData>(outputs.getValue("failed"))

        outputs.forEach { (_, output) ->
            val projection =
                with(world.assumptions) {
                    output.snipToDemand(selectionForestOf())
                }
            assertEquals(output, projection)
        }
    }

    @Test
    fun `distinguishes missing executors from foreign schema definitions`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val registry = world.resolverRegistry
        val userField = schema.requireObjectField("User", "name")

        val missingField =
            assertFailsWith<MissingResolverException> {
                registry.resolver(userField)
            }
        assertEquals("User", missingField.typeName)
        assertEquals("name", missingField.fieldName)

        val foreignSchema = TestWorld.fromSDL(SCHEMA_SDL).schema
        assertFailsWith<IllegalArgumentException> {
            registry.resolver(foreignSchema.requireObjectField("User", "name"))
        }
    }

    @Test
    fun `rejects a field resolver whose object fragment is not its canonical parent type`() {
        assertFailsWith<IllegalArgumentException> {
            worldWithFragmentType { schema ->
                schema.requireType("User") as ViaductSchema.Object
            }
        }

        val foreignQuery = TestWorld.fromSDL(SCHEMA_SDL).schema.requireQueryTypeDef()
        assertFailsWith<IllegalArgumentException> {
            worldWithFragmentType { foreignQuery }
        }
    }

    @Test
    fun `rejects foreign resolver coordinate definitions`() {
        val foreignSchema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val foreignUser = foreignSchema.requireType("User") as ViaductSchema.Object
        val foreignUserField = foreignSchema.requireField("Query", "user")

        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = {
                    mapOf(foreignUser to nodeResolverOf { _: String -> error("Not invoked") })
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val queryFragment = schema.emptyFragmentOf("Query")
                    mapOf(
                        foreignUserField to
                            fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        }
    }

    @Test
    fun `node resolver registration requires a canonical Node interface`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      id: ID!
                    }

                    type Query {
                      user: User
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val user = schema.requireType("User") as ViaductSchema.Object
                    mapOf(user to nodeResolverOf { _: String -> error("Not invoked") })
                },
            )
        }
    }

    @Test
    fun `rejects node resolvers for object types that do not implement Node`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type User implements Node {
                      id: ID!
                    }

                    type Other {
                      id: ID!
                    }

                    type Query {
                      user: User
                      other: Other
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val other = schema.requireType("Other") as ViaductSchema.Object
                    mapOf(other to nodeResolverOf { _: String -> error("Not invoked") })
                },
            )
        }
    }

    @Test
    fun `rejects field resolvers registered at abstract coordinates`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val queryFragment = schema.emptyFragmentOf("Query")
                    val nodeFragment = schema.emptyFragmentOf("Node")
                    mapOf(
                        schema.requireField("Query", "user") to
                            fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.requireField("Node", "name") to
                            fieldResolverOf(
                                objectFragment = nodeFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        }
    }

    @Test
    fun `fills missing Query resolvers by nullability and preserves supplied resolvers`() = runBlocking {
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { supplied: Int, nullable: Int, required: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "supplied") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    )
                },
            )
        val schema = world.schema
        val registry = world.resolverRegistry
        val query = schema.objectOf("Query")

        suspend fun resolve(fieldName: String): Any? {
            val field = schema.requireObjectField("Query", fieldName)
            return registry.resolver(field)(
                input = query,
                arguments = Arguments.Resolved.of(field, emptyMap()),
                selectiveResolvers = false,
                executionContext = ResolutionExecutionContext.Unsupported,
            )
        }

        assertEquals(7, resolve("supplied"))
        assertEquals(null, resolve("nullable"))
        assertIs<EngineErrorData>(resolve("required"))
    }

    @Test
    fun `requires a field resolver for every Query field in a canonical registry`() {
        val schema = GJSchema.fromSDL("type Query { missing: Int }")
        assertFailsWith<IllegalArgumentException> {
            resolverRegistryOf(
                schema = schema,
                nodeResolvers = emptyMap(),
                fieldResolvers = emptyMap(),
                variableProviders = emptyMap(),
            )
        }
    }

    @Test
    fun `rejects field resolvers for node id and generated typename fields`() {
        listOf("id", "V_A_typename").forEach { fieldName ->
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA_SDL,
                    nodeResolvers = { schema ->
                        val user = schema.requireType("User") as ViaductSchema.Object
                        mapOf(user to nodeResolverOf { _: String -> error("Not invoked") })
                    },
                    fieldResolvers = { schema ->
                        val fragment = schema.emptyFragmentOf("User")
                        mapOf(
                            schema.requireField("User", fieldName) to
                                fieldResolverOf(
                                    objectFragment = fragment,
                                    function = { _, _ -> error("Not invoked") },
                                ),
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `snips selected fields recursively through objects and lists`() {
        val world =
            TestWorld.fromSDL(
                """
                type Record {
                  id: ID!
                  name: String!
                  friend: Record
                  peers: [Record]
                }

                type Query {
                  record: Record
                }
                """.trimIndent(),
            )
        val schema = world.schema
        val record = schema.requireType("Record") as ViaductSchema.Object
        fun key(fieldName: String): ObjectEngineResult.GroundKey =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Record", fieldName),
                emptyMap(),
            )
        fun selection(
            fieldName: String,
            subselections: SelectionForest = selectionForestOf(),
        ): Selection =
            Selection.of(
                key = key(fieldName),
                possibleTypes = setOf(record),
                subselections = subselections,
            )
        val friend =
            schema.objectOf("Record") {
                "id" setTo "friend"
                "name" setTo "Friend"
            }
        val peer =
            schema.objectOf("Record") {
                "id" setTo "peer"
                "name" setTo "Peer"
            }
        val source =
            schema.objectOf("Record") {
                "id" setTo "target"
                "name" setTo "Target"
                "friend" setTo friend
                "peers" setTo listOf(peer, null)
            }
        val idSelection = selection("id")
        val nameSelection = selection("name")
        val selections =
            selectionForestOf(
                idSelection,
                selection("friend", selectionForestOf(idSelection)),
                selection("peers", selectionForestOf(nameSelection)),
            ) +
                selectionForestOf(
                    Selection.of(
                        key = key("name"),
                        possibleTypes = emptySet(),
                        subselections = selectionForestOf(),
                    ),
                )

        val result =
            assertIs<EngineObjectData.Sync>(
                with(world.assumptions) {
                    source.snipToDemand(selections)
                },
            )

        assertEquals(
            setOf(
                "id",
                "friend",
                "peers",
            ),
            result.getSelections().toSet(),
        )
        assertEquals(
            "target",
            result.get("id"),
        )
        val snippedFriend =
            assertIs<EngineObjectData.Sync>(result.get("friend"))
        assertEquals(
            setOf("id"),
            snippedFriend.getSelections().toSet(),
        )
        val peers = assertIs<List<*>>(result.get("peers"))
        val snippedPeer = assertIs<EngineObjectData.Sync>(peers.first())
        assertEquals(
            setOf("name"),
            snippedPeer.getSelections().toSet(),
        )
        assertEquals(null, peers.last())
    }

    @Test
    fun `snipToDemand omits selections conditioned on another concrete type`() {
        val fixture = Fixture()
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
            }

        val result =
            assertIs<EngineObjectData.Sync>(
                with(fixture.assumptions) {
                    source.snipToDemand(
                        fixture.schema.fragmentFrom(
                            """
                            fragment ignored on Admin {
                              level
                            }
                            """.trimIndent(),
                        ).subselections,
                    )
                },
            )

        assertEquals(emptySet(), result.getSelections().toSet())
    }

    @Test
    fun `field-resolver snipToDemand stops before an argument-bearing field resolver`() {
        val fixture = Fixture()
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
            }

        val result =
            assertIs<EngineObjectData.Sync>(
                with(fixture.assumptions) {
                    source.snipToDemand(
                        fixture.schema.fragmentFrom(
                            """
                            fragment ignored on User {
                              search {
                                id
                              }
                            }
                            """.trimIndent(),
                        ).subselections,
                    )
                },
            )

        assertEquals(emptySet(), result.getSelections().toSet())
    }

    @Test
    fun `snipToDemand rejects a present argument-bearing field`() {
        val fixture = Fixture()
        val search = fixture.schema.requireObjectField("User", "search")
        val source =
            engineObjectDataOf(
                schemaType = fixture.user,
                fields =
                    listOf(
                        EngineObjectDataEntry.of(
                            selection = search.name,
                            field = search,
                            value = null,
                        ),
                    ),
            )
        val demand =
            fixture.schema.fragmentFrom(
                """
                fragment ignored on User {
                  search(limit: 1) {
                    id
                  }
                }
                """.trimIndent(),
            ).subselections

        val failure =
            assertFailsWith<IllegalArgumentException> {
                source.snipToDemand(demand)
            }

        assertEquals(
            "Resolver output must not supply argument-bearing field User/search",
            failure.message,
        )
    }

    @Test
    @Ignore("Runtime argument-bearing resolver-output validation is disabled")
    fun `field resolver rejects output containing an argument-bearing field`() = runBlocking {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      value(index: Int): String
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val itemType = schema.requireType("Item") as ViaductSchema.Object
                    val valueField = schema.requireObjectField("Item", "value")
                    mapOf(
                        schema.requireObjectField("Query", "item") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ ->
                                    engineObjectDataOf(
                                        schemaType = itemType,
                                        fields =
                                            listOf(
                                                EngineObjectDataEntry.of(
                                                    selection = valueField.name,
                                                    field = valueField,
                                                    value = "one",
                                                ),
                                            ),
                                    )
                                },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val itemField = world.schema.requireObjectField("Query", "item")
        val resolver = world.resolverRegistry.resolver(itemField)

        val failure =
            assertFailsWith<IllegalArgumentException> {
                resolver(
                    input = world.schema.objectOf("Query"),
                    arguments = Arguments.Resolved.of(itemField, emptyMap()),
                    selectiveResolvers = world.selectiveResolvers,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            }

        assertEquals(
            "Resolver output must not supply argument-bearing field Item/value",
            failure.message,
        )
    }

    @Test
    fun `snipToDemand does not expand resolver demand`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      firstName: String!
                      lastName: String!
                      greeting: String!
                    }

                    type Query {
                      viewer: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "viewer") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.requireField("User", "greeting") to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on User {
                                          firstName
                                          lastName
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val source =
            world.objectOf("User") {
                "firstName" setTo "Ada"
                "lastName" setTo "Lovelace"
            }
        val demand =
            world.fragmentFrom(
                """
                fragment ignored on User {
                  greeting
                }
                """.trimIndent(),
            ).subselections

        val result =
            assertIs<EngineObjectData.Sync>(
                source.snipToDemand(demand),
            )

        assertEquals(emptySet(), result.getSelections().toSet())
    }

    @Test
    fun `snipToDemand retains present resolver fields including null and error values`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      value: Int
                      nullValue: Int
                      errorValue: Int
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    listOf("value", "nullValue", "errorValue").associate { fieldName ->
                        schema.requireField("Item", fieldName) to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Item"),
                                function = { _, _ -> error("Not invoked") },
                            )
                    }
                },
            )
        val world = testWorld.assumptions
        val error = EngineErrorData.of()
        val source =
            world.objectOf("Item") {
                "value" setTo 7
                "nullValue" setTo null
                "errorValue" setTo error
            }
        val demand =
            world.fragmentFrom(
                """
                fragment ignored on Item {
                  value
                  nullValue
                  errorValue
                }
                """.trimIndent(),
            ).subselections

        val result =
            assertIs<EngineObjectData.Sync>(
                source.snipToDemand(demand),
            )

        assertEquals(setOf("value", "nullValue", "errorValue"), result.getSelections().toSet())
        assertEquals(7, result.get("value"))
        assertTrue(result.isPresent("nullValue"))
        assertEquals(null, result.get("nullValue"))
        assertEquals(error, result.outputValue("errorValue"))
    }

    @Test
    fun `field-resolver snipToDemand has no implicit node ownership boundary`() {
        val fixture = Fixture(withNodeResolver = true)
        val source =
            fixture.assumptions.objectOf("User") {
                "id" setTo "target"
                "name" setTo "Target"
            }

        val result =
            assertIs<EngineObjectData.Sync>(
                with(fixture.assumptions) {
                    source.snipToDemand(
                        fixture.schema.fragmentFrom(
                            """
                            fragment ignored on Node {
                              name
                            }
                            """.trimIndent(),
                        ).subselections,
                    )
                },
            )

        assertEquals(
            setOf("name"),
            result.getSelections().toSet(),
        )
    }

    @Test
    fun `selection factory distinguishes empty composites and rejects subselections on leaves`() {
        val fixture = Fixture()

        val leaf = fixture.selection("Node", "id")
        val emptyComposite = fixture.selection("User", "friend")

        assertTrue(leaf.isLeaf)
        assertTrue(leaf.subselections.isEmpty())
        assertFalse(emptyComposite.isLeaf)
        assertTrue(emptyComposite.subselections.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            Selection.of(
                key = leaf.key,
                possibleTypes = leaf.possibleTypes,
                subselections = selectionForestOf(emptyComposite),
            )
        }
    }

    private fun worldWithFragmentType(
        fragmentType: (ViaductSchema) -> ViaductSchema.CompositeTypeDef,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL = SCHEMA_SDL,
            fieldResolvers = { schema ->
                val fragment =
                    Fragment.of(fragmentType(schema), selectionForestOf())
                val userField = schema.requireField("Query", "user")
                mapOf(
                    userField to
                        fieldResolverOf(
                            objectFragment = fragment,
                            function = { _, _ -> schema.objectOf("User") },
                        ),
                )
            },
        )

    private class Fixture(
        withNodeResolver: Boolean = false,
    ) {
        private val world =
            TestWorld.fromSDL(
                schemaSDL = SCHEMA_SDL,
                nodeResolvers = { schema ->
                    if (withNodeResolver) {
                        val user = schema.requireType("User") as ViaductSchema.Object
                        mapOf(user to nodeResolverOf { _: String -> error("Not invoked") })
                    } else {
                        emptyMap()
                    }
                },
                fieldResolvers = { schema ->
                    val queryFragment = schema.emptyFragmentOf("Query")
                    val userFragment = schema.emptyFragmentOf("User")
                    mapOf(
                        schema.requireField("Query", "user") to
                            fieldResolverOf(
                                objectFragment = queryFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                        schema.requireField("User", "search") to
                            fieldResolverOf(
                                objectFragment = userFragment,
                                function = { _, _ -> error("Not invoked") },
                            ),
                    )
                },
            )
        val schema = world.schema
        val assumptions = world.assumptions
        val user = schema.requireType("User") as ViaductSchema.Object
        val userField = schema.requireField("Query", "user")

        fun key(fieldName: String): ObjectEngineResult.Key =
            ObjectEngineResult.Key.of(
                field = schema.requireField(user.name, fieldName),
                arguments = emptyMap(),
            )

        fun selection(
            typeName: String,
            fieldName: String,
            possibleTypes: Set<ViaductSchema.Object> =
                (schema.requireType(typeName) as ViaductSchema.CompositeTypeDef).possibleObjectTypes,
        ): Selection {
            return Selection.of(
                key =
                    ObjectEngineResult.Key.of(
                        field = schema.requireField(typeName, fieldName),
                        arguments = emptyMap(),
                    ),
                possibleTypes = possibleTypes,
                subselections = selectionForestOf(),
            )
        }
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Node {
              id: ID!
              name: String!
            }

            type User implements Node {
              id: ID!
              name: String!
              friend: User
              peers: [User]
              search(limit: Int): User
            }

            type Admin implements Node {
              id: ID!
              name: String!
              level: Int!
            }

            type Query {
              user: User
            }
            """.trimIndent()
    }
}
