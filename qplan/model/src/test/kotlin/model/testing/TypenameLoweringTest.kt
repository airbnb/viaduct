@file:Suppress("ForbiddenImport")

package model.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.fragmentFrom
import model.merge
import model.objectKey
import model.objectOf
import model.operationSelectionsFrom
import model.registry.ResolutionExecutionContext
import model.requireField
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import viaduct.graphql.schema.ViaductSchema

class TypenameLoweringTest {
    @Test
    fun `lowered schema owns ordinary typename fields and an all-source-objects interface`() {
        val world = TestWorld.fromSDL(SCHEMA)
        val schema = world.schemas
        val allSourceObjects =
            assertIs<ViaductSchema.Interface>(schema.loweredSchema.requireType("V_A_AllSourceObjects"))
        val objectTypes =
            listOf("Query", "A", "B")
                .map { name -> schema.loweredSchema.requireType(name) as ViaductSchema.Object }

        assertEquals(objectTypes.toSet(), allSourceObjects.possibleObjectTypes)
        assertEquals(
            setOf("V_A_typename"),
            allSourceObjects.fields.mapTo(linkedSetOf(), ViaductSchema.Field::name),
        )
        listOf(
            "Query",
            "Item",
            "Node",
            "A",
            "B",
        ).forEach { typeName ->
            val field = schema.loweredSchema.requireField(typeName, "V_A_typename")
            assertEquals(typeName, field.containingDef.name)
            assertTrue(field.args.isEmpty())
            assertEquals("String", field.type.baseTypeDef.name)
            assertTrue(!field.type.isNullable)
        }
        listOf("A_V_A_Bridge", "Node_V_A_Bridge").forEach { typeName ->
            assertFailsWith<IllegalStateException> {
                schema.loweredSchema.requireType(typeName)
            }
        }
        assertIs<ViaductSchema.Union>(schema.loweredSchema.requireType("Choice"))
        listOf(
            "Query",
            "Item",
            "Node",
            "Choice",
            "A",
            "B",
        ).forEach { typeName ->
            assertFailsWith<IllegalStateException> {
                schema.loweredSchema.requireField(typeName, "__typename")
            }
        }
    }

    @Test
    fun `internal fragments lower object interface union and node typename selections`() {
        val schema = TestWorld.fromSDL(SCHEMA).schemas

        val objectFragment = schema.fragmentFrom("fragment F on A { __typename }")
        val objectSelection = objectFragment.subselections.single()
        assertEquals("A", objectSelection.key.field.containingDef.name)
        assertEquals("V_A_typename", objectSelection.key.field.name)
        assertEquals(
            "V_A_typename",
            objectFragment.materializeSelections.single().responseKey,
        )

        val interfaceSelection =
            schema.fragmentFrom("fragment F on Item { __typename }").subselections.single()
        assertEquals("Item", interfaceSelection.key.field.containingDef.name)
        assertEquals(
            "A",
            interfaceSelection.key.objectKey(schema.loweredSchema.requireType("A") as ViaductSchema.Object)
                .field.containingDef.name,
        )

        val unionFragment = schema.fragmentFrom("fragment F on Choice { kind: __typename }")
        val unionSelection = unionFragment.subselections.single()
        assertEquals("V_A_AllSourceObjects", unionSelection.key.field.containingDef.name)
        assertEquals(
            "kind",
            unionFragment.materializeSelections.single().responseKey,
        )

        val nodeSelection =
            schema.fragmentFrom("fragment F on Query { node { __typename } }")
                .subselections
                .single()
        assertEquals(
            "V_A_typename",
            nodeSelection.subselections.single().key.field.name,
        )
    }

    @Test
    fun `external operations erase typename while preserving composite selections`() {
        val world = TestWorld.fromSDL(SCHEMA)

        assertTrue(world.schemas.operationSelectionsFrom("query { __typename }").isEmpty())

        val nested =
            world.schemas.operationSelectionsFrom(
                """
                query {
                  a {
                    __typename
                    ...OnlyTypename
                    ... on A {
                      nested: __typename
                    }
                  }
                }

                fragment OnlyTypename on A {
                  aliased: __typename
                }
                """.trimIndent(),
            )
        val a = nested.merge(world.schema.requireQueryTypeDef()).single()
        assertEquals("a", a.key.field.name)
        assertTrue(a.subselections.isEmpty())
    }

    @Test
    fun `generated typename resolvers are argumentless dependency-free constants`() =
        runBlocking {
            val world = TestWorld.fromSDL(SCHEMA)
            val schema = world.schemas
            val registry = world.resolverRegistry
            val allSourceObjects =
                schema.loweredSchema.requireType("V_A_AllSourceObjects") as ViaductSchema.Interface

            allSourceObjects.possibleObjectTypes.forEach { type ->
                val field = schema.loweredSchema.requireObjectField(type.name, "V_A_typename")
                val resolver = registry.resolver(field)
                assertTrue(field in registry)
                assertTrue(field.args.isEmpty())
                assertTrue(resolver.objectFragment.isEmpty())
                assertTrue(resolver.variables.isEmpty())
                assertTrue(registry.mayDemandFrom(field).isEmpty())
                assertEquals(
                    type.name,
                    resolver(
                        input = schema.loweredSchema.objectOf(type.name),
                        arguments = Arguments.Resolved.of(field, emptyMap()),
                        selectiveResolvers = world.assumptions.selectiveResolvers,
                        executionContext = ResolutionExecutionContext.Unsupported,
                    ),
                )
            }
        }

    @Test
    fun `rejects source names in the typename lowering namespace`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL("type Query { V_A_typename: String }")
            }

        assertTrue(exception.message.orEmpty().contains("reserved token V_A"))
        assertTrue(exception.message.orEmpty().contains("V_A_typename"))
    }

    private companion object {
        val SCHEMA =
            """
            interface Node {
              id: ID!
            }

            interface Item {
              value: Int!
            }

            union Choice = A | B

            type A implements Node & Item {
              id: ID!
              value: Int!
            }

            type B implements Item {
              value: Int!
            }

            type Query {
              a: A!
              item: Item
              choice: Choice
              node: Node
            }
            """.trimIndent()
    }
}
