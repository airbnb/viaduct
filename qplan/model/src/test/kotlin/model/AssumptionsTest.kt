package model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import model.lowering.SourceSchemaAdapter
import model.registry.ResolverTarget
import model.testing.TestWorld
import model.testing.testRoot
import viaduct.graphql.schema.ViaductSchema

class AssumptionsTest {
    @Test
    fun `schema holder exposes the canonical schema without implementing it`() {
        val fixture = TestWorld.fromSDL(SCHEMA_SDL)
        val holder: Any = fixture.schemas

        assertFalse(holder is ViaductSchema)
        assertSame(fixture.schemas.loweredSchema, fixture.schema)
        assertSame(fixture.schema, fixture.assumptions.schema)
        assertSame(
            fixture.schema.requireField("Query", "node"),
            SourceSchemaAdapter(fixture.schema).field("Query", "node"),
        )
    }

    @Test
    fun `constructs a resolver fragment variable with its defining field`() {
        val fixture = TestWorld.fromSDL(SCHEMA_SDL)
        val assumptions = fixture.assumptions
        val variableField = assumptions.schema.requireObjectField("Query", "node")

        val fragment =
            fixture.schemas.fragmentFrom(
                source =
                    """
                fragment ignored on Query {
                  node(filter: ${'$'}filter) {
                    id
                  }
                }
                    """.trimIndent(),
                variableField = variableField,
            )

        val node = fragment.subselections.single()
        assertEquals(
            Arguments.Variable.of(variableField, "filter"),
            node.key.arguments.fieldExpressions().getValue("filter"),
        )
        assertNotEquals(
            Arguments.Variable.of(variableField, "other"),
            node.key.arguments.fieldExpressions().getValue("filter"),
        )
    }

    @Test
    fun `parses and validates a named fragment against the retained schema`() {
        val fixture = TestWorld.fromSDL(SCHEMA_SDL)
        val assumptions = fixture.assumptions

        val fragment =
            fixture.schemas.fragmentFrom(
                """
                fragment ignored on Query {
                  result: node(filter: {tags: "one", role: ADMIN}) {
                    id
                  }
                }
                """.trimIndent(),
            )

        assertEquals(assumptions.schema.requireQueryTypeDef(), fragment.nominalType)
        val node = fragment.subselections.single()
        assertEquals("node", node.key.field.name)

        val filter =
            assertIs<EngineInputObjectData>(
                node.key.arguments.fieldExpressions().getValue("filter"),
            )
        val role = assertIs<String>(filter["role"])
        assertEquals("ADMIN", role)
        val limit = assertIs<Int>(filter["limit"])
        assertEquals(10, limit)
        val tags = assertIs<EngineInputListData>(filter["tags"])
        assertEquals(
            "one",
            assertIs<String>(tags.single()),
        )

        val id = node.subselections.single()
        assertEquals("id", id.key.field.name)
    }

    @Test
    fun `rejects a named fragment that is invalid for the retained schema`() {
        val fixture = TestWorld.fromSDL(SCHEMA_SDL)

        val exception =
            assertFailsWith<IllegalArgumentException> {
                fixture.schemas.fragmentFrom(
                    """
                    fragment ignored on Query {
                      missing
                    }
                    """.trimIndent(),
                )
            }

        assertContains(exception.message.orEmpty(), "missing")
    }

    @Test
    fun `parses a fragment from a schema with explicit variable bindings`() {
        val schemas = TestWorld.fromSDL(SCHEMA_SDL).schemas
        val schema = schemas.loweredSchema
        val filterType = schema.requireType("Filter") as ViaductSchema.Input
        val filter =
            toEngineInputObjectData(
                expectedType = filterType,
                value = mapOf("limit" to 5),
            )

        val fragment =
            schemas.fragmentFrom(
                source =
                    """
                    fragment ignored on Query {
                      node(filter: ${'$'}filter) {
                        id
                      }
                    }
                    """.trimIndent(),
                bindings = mapOf("filter" to filter),
            )

        assertEquals(
            filter,
            fragment.subselections.single().key.arguments
                .fieldExpressions()
                .getValue("filter"),
        )
    }

    @Test
    fun `ground input object construction rejects unresolved variables`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.requireType("Filter") as ViaductSchema.Input
        val variableField = schema.requireObjectField("Query", "node")

        assertFailsWith<ClassCastException> {
            toEngineInputObjectData(
                expectedType = filterType,
                value =
                    mapOf(
                        "limit" to Arguments.Variable.of(variableField, "nested"),
                    ),
            )
        }
    }

    @Test
    fun `constructs empty fragments that GraphQL text cannot express`() {
        val assumptions = TestWorld.fromSDL(SCHEMA_SDL).assumptions

        val worldFragment = assumptions.emptyFragmentOf("Query")
        val schemaFragment = assumptions.schema.emptyFragmentOf("Query")

        assertEquals(assumptions.schema.requireQueryTypeDef(), worldFragment.nominalType)
        assertEquals(assumptions.schema.requireQueryTypeDef(), schemaFragment.nominalType)
        assertTrue(worldFragment.subselections.isEmpty())
        assertTrue(schemaFragment.subselections.isEmpty())
    }

    @Test
    fun `constructs assumptions from a schema and resolver registry`() {
        val world = TestWorld.fromSDL(schemaSDL = SCHEMA_SDL)
        val assumptions = world.assumptions

        assertEquals(world.schema, assumptions.schema)

        val schema = assumptions.schema
        val query = schema.requireQueryTypeDef()
        val node = assertIs<ViaductSchema.Interface>(schema.requireType("Node"))
        val user = assertIs<ViaductSchema.Object>(schema.requireType("User"))
        val admin = assertIs<ViaductSchema.Object>(schema.requireType("Admin"))
        val actor = assertIs<ViaductSchema.Union>(schema.requireType("Actor"))

        assertEquals(query, schema.requireType("Query"))
        listOf("Int", "String", "ID").forEach { scalarName ->
            assertIs<ViaductSchema.Scalar>(schema.requireType(scalarName))
        }
        assertEquals(setOf(user, admin), node.possibleObjectTypes)
        assertEquals(setOf(user, admin), actor.possibleObjectTypes)

        val typeName = schema.requireField("Node", "V_A_typename")
        assertEquals(node, typeName.containingDef)
        assertEquals(emptyList(), typeName.args)
        assertEquals("String", typeName.type.baseTypeDef.name)
        assertFalse(typeName.type.isNullable)
        assertFalse(typeName.type.isList)

        val actors = schema.requireField("Query", "actors")
        listOf(
            schema.requireField("Node", "id"),
            schema.requireField("User", "name"),
            schema.requireField("Admin", "level"),
            actors,
        ).forEach { field ->
            assertTrue(field.args.isEmpty())
        }
        val emptyArguments = Arguments.Resolved.of(actors, emptyMap())
        val otherEmptyArguments = Arguments.Resolved.of(typeName, emptyMap())
        assertEquals(emptyArguments, otherEmptyArguments)
        assertEquals(actor, actors.type.baseTypeDef)
        assertEquals(1, actors.type.listDepth)
        assertFalse(actors.type.nullableAtDepth(0))
        assertFalse(actors.type.baseTypeNullable)

        val nodeField = schema.requireField("Query", "node")
        assertEquals(query, nodeField.containingDef)
        assertTrue(nodeField.args.isNotEmpty())
        val filterArgument = nodeField.requireArg("filter")
        assertEquals(nodeField, filterArgument.containingDef)
        assertEquals("filter", filterArgument.name)
        assertFalse(!filterArgument.type.isNullable && !filterArgument.hasDefault)
        val filterDefault =
            assertIs<CoercedDefaultValue.Present>(filterArgument.coercedDefaultValue())
        val filterValue = assertIs<EngineInputObjectData>(filterDefault.value)
        val nodeArguments =
            Arguments.Resolved.of(
                field = nodeField,
                fields = mapOf("filter" to filterValue),
            )
        assertEquals(filterValue, nodeArguments.fieldValues.getValue("filter"))
        val defaultRole = assertIs<String>(filterValue["role"])
        assertEquals("MEMBER", defaultRole)
        val defaultLimit = assertIs<Int>(filterValue["limit"])
        assertEquals(10, defaultLimit)
        val tags =
            assertIs<EngineInputListData>(
                filterValue["tags"],
            )
        assertEquals(
            "a",
            assertIs<String>(tags.single()),
        )
        val nodeKey =
            ObjectEngineResult.Key.of(
                field = nodeField,
                arguments = mapOf("filter" to filterValue),
            )
        assertEquals(nodeField, nodeKey.field)
        assertEquals(
            filterValue,
            nodeKey.arguments.fieldExpressions()["filter"],
        )
        assertEquals(
            nodeKey,
            ObjectEngineResult.Key.of(
                field = nodeField,
                arguments = mapOf("filter" to filterValue),
            ),
        )
        val interfaceIdKey =
            ObjectEngineResult.Key.of(
                field = schema.requireField("Node", "id"),
                arguments = emptyMap(),
            )
        val objectIdKey =
            ObjectEngineResult.Key.of(
                field = schema.requireField("User", "id"),
                arguments = emptyMap(),
            )
        assertFalse(interfaceIdKey == objectIdKey)

        val friendField = schema.requireField("User", "friend")
        assertTrue(friendField.args.isNotEmpty())
        val limitArgument: ViaductSchema.FieldArg =
            friendField.requireArg("limit")
        assertEquals(friendField, limitArgument.containingDef)
        assertEquals("limit", limitArgument.name)
        assertFalse(!limitArgument.type.isNullable && !limitArgument.hasDefault)

        val filter = assertIs<ViaductSchema.Input>(schema.requireType("Filter"))
        val limitInputField: ViaductSchema.Field = filter.requireField("limit")
        assertEquals(filter, limitInputField.containingDef)
        assertEquals("limit", limitInputField.name)
        assertFalse(!limitInputField.type.isNullable && !limitInputField.hasDefault)
        val tagsElementType = checkNotNull(filter.requireField("tags").type.unwrapList())
        assertFalse(tagsElementType.isNullable)
    }

    @Test
    fun `nested input errors become an argument error`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val node = schema.requireObjectField("Query", "node")
        listOf(
            mapOf(
                "tags" to listOf(ArgumentResolutionError),
            ),
        ).forEach { filter ->
            val arguments =
                Arguments.of(
                    node,
                    mapOf(
                        "filter" to filter,
                    ),
                )

            assertSame(Arguments.Error, arguments)
        }
    }

    @Test
    fun `object values reject argument-bearing passive fields`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val friendValue = schema.objectOf("Profile")

        assertFailsWith<IllegalArgumentException> {
            schema.objectOf("Profile") {
                field("friend", "limit" to 1) setTo friendValue
            }
        }
    }

    @Test
    fun `input-like factories convert host values according to the schema`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.requireType("Filter") as ViaductSchema.Input
        val filter =
            toEngineInputObjectData(
                expectedType = filterType,
                value =
                    mapOf(
                        "limit" to 20,
                        "tags" to listOf("one", "two"),
                        "role" to "ADMIN",
                    ),
            )

        assertEquals(
            20,
            filter.getValue("limit"),
        )
        assertEquals(
            listOf("one", "two"),
            filter.getValue("tags"),
        )
        assertEquals(
            "ADMIN",
            assertIs<String>(filter.getValue("role")),
        )

        val nodeField = schema.requireField("Query", "node")
        val arguments =
            Arguments.Resolved.of(
                field = nodeField,
                fields =
                    mapOf(
                        "filter" to
                            mapOf(
                                "limit" to 30,
                                "tags" to listOf("nested"),
                            ),
                    ),
            )
        val nestedFilter =
            assertIs<EngineInputObjectData>(arguments.fieldValues.getValue("filter"))
        assertEquals(
            30,
            nestedFilter["limit"],
        )
    }

    @Test
    fun `input-like factories apply declared defaults unless explicitly overridden`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val friendField = schema.requireField("User", "friend")
        val defaultFriendArguments = Arguments.Resolved.of(friendField, emptyMap())

        assertEquals(
            3,
            defaultFriendArguments.fieldValues.getValue("limit"),
        )

        val nodeField = schema.requireField("Query", "node")
        val defaultArguments = Arguments.Resolved.of(nodeField, emptyMap())
        val defaultFilter =
            assertIs<EngineInputObjectData>(defaultArguments.fieldValues.getValue("filter"))

        assertEquals(
            10,
            defaultFilter["limit"],
        )
        assertEquals(
            "MEMBER",
            assertIs<String>(defaultFilter["role"]),
        )

        val explicitFilter =
            toEngineInputObjectData(
                expectedType = schema.requireType("Filter") as ViaductSchema.Input,
                value = mapOf("limit" to 20, "role" to null),
            )
        assertEquals(
            20,
            explicitFilter.getValue("limit"),
        )
        assertTrue(explicitFilter.containsKey("role"))
        assertEquals(null, explicitFilter.getValue("role"))

        val explicitNullFriendArguments =
            Arguments.Resolved.of(
                field = friendField,
                fields = mapOf("limit" to null),
            )
        assertTrue(explicitNullFriendArguments.fieldValues.containsKey("limit"))
        assertEquals(null, explicitNullFriendArguments.fieldValues.getValue("limit"))
    }

    @Test
    fun `input-like factories reject values that do not match the schema`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filterType = schema.requireType("Filter") as ViaductSchema.Input

        assertFailsWith<ClassCastException> {
            toEngineInputObjectData(
                expectedType = filterType,
                value = mapOf("limit" to "twenty"),
            )
        }
        assertFailsWith<ClassCastException> {
            toEngineInputObjectData(
                expectedType = filterType,
                value = mapOf("tags" to listOf(null)),
            )
        }
        assertFailsWith<ClassCastException> {
            toEngineInputObjectData(
                expectedType = filterType,
                value = mapOf("missing" to 1),
            )
        }
        assertFailsWith<ClassCastException> {
            Arguments.Resolved.of(
                field = schema.requireField("Query", "node"),
                fields = mapOf("filter" to 1),
            )
        }
        assertFailsWith<ClassCastException> {
            toEngineInputObjectData(
                expectedType = filterType,
                value = mapOf("role" to "MISSING"),
            )
        }
    }

    @Test
    fun `rejects non-standard scalar declarations`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        scalar Date

                        type Query {
                          today: Date
                        }
                        """.trimIndent(),
                )
            }

        assertContains(exception.message.orEmpty(), "Date")
    }

    @Test
    fun `rejects non-standard directive definitions`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        directive @authenticated on FIELD_DEFINITION

                        type Query {
                          secret: String @authenticated
                        }
                        """.trimIndent(),
                )
            }

        assertContains(exception.message.orEmpty(), "authenticated")
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
              name: String!
              role: Role!
              friend(limit: Int = 3): User
            }

            type Admin implements Node {
              id: ID!
              level: Int!
            }

            type Profile {
              friend(limit: Int = 3): Profile
            }

            union Actor = User | Admin

            enum Role {
              MEMBER
              ADMIN
            }

            enum OtherRole {
              MEMBER
            }

            input Filter {
              limit: Int = 10
              tags: [String!]
              role: Role = MEMBER
            }

            type Query {
              node(filter: Filter = {tags: ["a"]}): Node!
              actors: [Actor!]!
            }
            """.trimIndent()
    }
}

private fun Arguments.Variable.testInstance(path: List<PathComponent>): Arguments.Variable =
    instantiate(
        ResolverOccurrenceId.at((target as ResolverTarget.FieldTarget).field.testRoot(), path),
    )

private val Arguments.Variable.testId: VariableInstanceId
    get() = requireNotNull(instanceId)
