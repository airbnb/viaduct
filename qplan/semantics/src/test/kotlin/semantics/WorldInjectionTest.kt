@file:Suppress("ForbiddenImport")

package semantics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.Assumptions
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fragmentFrom
import model.objectOf
import model.registry.ResolutionExecutionContext
import model.registry.ResolverRegistry
import model.requireField
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.FieldResolverDefinition
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.nodeResolverOf
import semantics.contract.selectionValues
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

class WorldInjectionTest {
    @Test
    fun `guice assembles one complete reasoning world from qualified inputs`() =
        runBlocking {
            val testWorld =
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA_SDL,
                    nodeResolvers = { schema ->
                        val user = schema.requireType("User") as ViaductSchema.Object
                        mapOf(
                            user to
                                nodeResolverOf { id ->
                                    schema.objectOf("User") {
                                        "id" setTo id
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
                                    function = { _, _ ->
                                        schema.objectOf("User") {
                                            "id" setTo "field"
                                        }
                                    },
                                ),
                        )
                    },
                )

            val schema = testWorld.schema
            val registry = testWorld.resolverRegistry
            val world = testWorld.assumptions

            assertEquals(schema, world.schema)
            assertEquals(registry, world.resolverRegistry)
            assertEquals(registry, testWorld.instance(ResolverRegistry::class.java))
            assertEquals(world, testWorld.instance(Assumptions::class.java))

            val userField = schema.requireObjectField("Query", "user")
            val nodeReference =
                assertIs<model.RootFieldReferenceData>(
                    registry
                        .resolver(userField)(
                        input = world.objectOf("Query"),
                        queryValue = engineObjectDataOf(world.schema.requireQueryTypeDef()),
                        arguments = Arguments.Resolved.of(userField, emptyMap()),
                        selectiveResolvers = false,
                        executionContext = ResolutionExecutionContext.Unsupported,
                    ),
                )

            val queryNode = schema.requireObjectField("Query", "node")
            val selections =
                world.fragmentFrom(
                    """
                fragment ignored on User {
                  id
                }
                    """.trimIndent(),
                ).subselections
            val field =
                assertIs<EngineObjectData.Sync>(
                    registry
                        .resolver(queryNode)(
                        input = world.objectOf("Query"),
                        queryValue = engineObjectDataOf(world.schema.requireQueryTypeDef()),
                        arguments = nodeReference.arguments,
                        selections = selections,
                        selectiveResolvers = world.selectiveResolvers,
                        executionContext = ResolutionExecutionContext.Unsupported,
                    ),
                )
            assertEquals(
                "field",
                field.selectionValues()["id"],
            )

            assertEquals(userField, schema.requireObjectField("Query", "user"))
            assertFailsWith<IllegalStateException> {
                schema.requireType("User_V_A_Bridge")
            }
            assertEquals(
                schema.requireField("User", "id"),
                selections.single().key.field,
            )
        }

    @Test
    fun `guice supplies required query resolvers when resolver inputs are omitted`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions

        assertFalse(world.schema.requireObjectField("User", "id") in world.resolverRegistry)
        world.resolverRegistry.resolver(
            world.schema.requireObjectField("Query", "user"),
        )
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
            }

            type Query {
              user: User
            }
            """.trimIndent()
    }
}
