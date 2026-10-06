package viaduct.engine.runtime2.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.testRoot
import viaduct.graphql.schema.ViaductSchema

class ObjectEngineResultKeyTest {
    @Test
    fun `object fields always construct object keys with structural equality`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val user = schema.requireType("User") as ViaductSchema.Object
        val field = user.requireField("id")

        val general =
            ObjectEngineResult.Key.of(
                field = field as ViaductSchema.Field,
                arguments = emptyMap(),
            )
        val precise = ObjectEngineResult.GroundKey.of(field, emptyMap())

        assertIs<ObjectEngineResult.GroundKey>(general)
        assertEquals(precise, general)
        assertSame(field, general.field)
    }

    @Test
    fun `abstract fields construct plain keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val field = schema.requireField("Node", "id")

        val key = ObjectEngineResult.Key.of(field, emptyMap())

        assertFalse(key is ObjectEngineResult.GroundKey)
    }

    @Test
    fun `concrete fields with open arguments construct object keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val field = schema.requireObjectField("Query", "find")
        val variable = Arguments.Variable.of(field, "id")

        val key =
            ObjectEngineResult.Key.of(
                field = field as ViaductSchema.Field,
                arguments = Arguments.of(field, mapOf("id" to variable)),
            )

        assertIs<ObjectEngineResult.ObjectKey>(key)
        assertFalse(key is ObjectEngineResult.GroundKey)
        assertSame(field, key.field)
    }

    @Test
    fun `symbolic key equality includes variable instance identity`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  source: Int
                  consume(value: Int): Int
                }
                """.trimIndent(),
            ).schema
        val source = schema.requireObjectField("Query", "source")
        val consume = schema.requireObjectField("Query", "consume")
        val variable = Arguments.Variable.of(source, "value")
        val firstOccurrence =
            ResolverOccurrenceId.at(source.testRoot(), listOf(ListEngineResult.Index.of(0)))
        val secondOccurrence =
            ResolverOccurrenceId.at(source.testRoot(), listOf(ListEngineResult.Index.of(1)))
        val first = variable.instantiate(firstOccurrence)
        val equalFirst = variable.instantiate(firstOccurrence)
        val second = variable.instantiate(secondOccurrence)

        fun key(variable: Arguments.Variable) =
            ObjectEngineResult.Key.of(
                consume,
                Arguments.of(consume, mapOf("value" to variable)),
            )

        assertEquals(key(first), key(equalFirst))
        assertNotEquals(key(first), key(second))
    }

    @Test
    fun `symbolic abstract key specializes without losing variable identity`() {
        val schema =
            TestWorld.fromSDL(
                """
                interface Item {
                  computed(factor: Int): Int
                }

                type ConcreteItem implements Item {
                  computed(factor: Int): Int
                }

                type Query {
                  source: Int
                  item: Item
                }
                """.trimIndent(),
            ).schema
        val source = schema.requireObjectField("Query", "source")
        val abstractField = schema.requireField("Item", "computed")
        val concreteType = schema.requireType("ConcreteItem") as ViaductSchema.Object
        val variable = Arguments.Variable.of(source, "factor")
        val resolverOccurrenceId =
            ResolverOccurrenceId.at(source.testRoot(), listOf(ListEngineResult.Index.of(1)))
        val arguments =
            Arguments.Template
                .of(
                    abstractField,
                    Arguments.of(abstractField, mapOf("factor" to variable)),
                )
                .instantiate(abstractField, resolverOccurrenceId)
        val symbolicKey =
            ObjectEngineResult.Key.of(
                field = abstractField,
                arguments = arguments,
            )
        val specialized =
            selectionForestOf(
                Selection.of(
                    key = symbolicKey,
                    possibleTypes = setOf(concreteType),
                    subselections = selectionForestOf(),
                ),
            ).merge(concreteType)
                .keys()
                .single()

        assertFalse(symbolicKey is ObjectEngineResult.ObjectKey)
        assertIs<ObjectEngineResult.ObjectKey>(specialized)
        assertEquals(
            resolverOccurrenceId,
            specialized.arguments
                .usedVariables()
                .single()
                .instanceId
                ?.resolverOccurrenceId,
        )
    }

    @Test
    fun `list index rejects negative positions`() {
        ListEngineResult.Index.of(2)
        assertFailsWith<IllegalArgumentException> { ListEngineResult.Index.of(-1) }
    }

    @Test
    fun `selection paths contain only object keys`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val user = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Query", "user"), emptyMap())
        val id = ObjectEngineResult.GroundKey.of(schema.requireObjectField("User", "id"), emptyMap())

        assertEquals(listOf(user, id), listOf<PathComponent>(user, id).toSelectionPath())
        assertNull(listOf<PathComponent>(user, ListEngineResult.Index.of(0), id).toSelectionPath())
        assertNull((null as List<PathComponent>?).toSelectionPath())
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
              find(id: ID!): User
            }
            """.trimIndent()
    }
}
