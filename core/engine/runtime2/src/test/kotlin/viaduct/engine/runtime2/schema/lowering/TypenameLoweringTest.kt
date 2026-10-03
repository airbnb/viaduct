package viaduct.engine.runtime2.schema.lowering

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.runtime2.schema.objectTypes
import viaduct.graphql.schema.ViaductSchema

class TypenameLoweringTest {
    @Test
    fun `lowered schema owns ordinary typename fields and an all-source-objects interface`() {
        val lowered = lowerSchema(graphQLSchema(SCHEMA))
        val allSourceObjects =
            assertIs<ViaductSchema.Interface>(
                lowered.requireType(ALL_SOURCE_OBJECTS_TYPE),
            )
        val objectTypes =
            listOf("Query", "A", "B")
                .map { name -> assertIs<ViaductSchema.Object>(lowered.requireType(name)) }

        assertEquals(objectTypes.toSet(), allSourceObjects.possibleObjectTypes)
        assertEquals(
            setOf(LOWERED_TYPENAME_FIELD),
            allSourceObjects.fields.mapTo(linkedSetOf()) { it.name },
        )
        listOf("Query", "Item", "Node", "A", "B").forEach { typeName ->
            val field = lowered.requireField(typeName, LOWERED_TYPENAME_FIELD)
            assertEquals(typeName, field.containingDef.name)
            assertTrue(field.args.isEmpty())
            assertSame(lowered.requireType("String"), field.type.baseTypeDef)
            assertTrue(!field.type.isNullable)
        }

        assertTrue(
            assertIs<ViaductSchema.Union>(lowered.requireType("Choice"))
                .possibleObjectTypes
                .isNotEmpty(),
        )
    }

    @Test
    fun `typename proxy references are canonical and the lowered schema is deterministic`() {
        val source = graphQLSchema(SCHEMA)
        val first = lowerSchema(source)
        val second = lowerSchema(source)

        val allSourceObjects =
            assertIs<ViaductSchema.Interface>(first.requireType(ALL_SOURCE_OBJECTS_TYPE))
        allSourceObjects.possibleObjectTypes.forEach { possibleType ->
            assertSame(first.requireType(possibleType.name), possibleType)
            assertSame(
                first.requireType("String"),
                possibleType.field(LOWERED_TYPENAME_FIELD)!!.type.baseTypeDef,
            )
        }
        assertEquals(first.fingerprint(), second.fingerprint())
    }

    private fun ViaductSchema.fingerprint(): List<String> =
        types.values.flatMap { type ->
            buildList {
                add("${type.kind}:${type.name}")
                if (type is ViaductSchema.OutputRecord) {
                    add("${type.name}<:${type.supers.joinToString { it.name }}")
                }
                if (type is ViaductSchema.Record) {
                    type.fields.forEach { field ->
                        add("${type.name}.${field.name}:${field.type}")
                    }
                }
                if (type is ViaductSchema.CompositeTypeDef) {
                    add(
                        "${type.name}=>${type.possibleObjectTypes.joinToString { it.name }}",
                    )
                }
            }
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
            """
    }
}
