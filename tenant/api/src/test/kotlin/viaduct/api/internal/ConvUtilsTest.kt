@file:Suppress("ForbiddenImport")

package viaduct.api.internal

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLType
import io.kotest.property.exhaustive.exhaustive
import io.kotest.property.forAll
import io.kotest.property.forNone
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.mkRawSelectionSet
import viaduct.engine.api.select.SelectionsParser
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.ConvMemo

class ConvUtilsTest {
    @Test
    fun `ConvMemo_memoizeIf -- false`() {
        val memo = ConvMemo()

        val conv1 = memo.memoizeIf("foo", false) {
            Conv(Int::toString, String::toInt)
        }
        val conv2 = memo.memoizeIf("foo", false) {
            Conv(Int::toString, String::toInt)
        }
        memo.finalize()

        assertFalse(conv1 === conv2)
    }

    @Test
    fun `ConvMemo_memoizeIf -- true`() {
        val memo = ConvMemo()

        val conv1 = memo.memoizeIf("foo", true) {
            Conv(Int::toString, String::toInt)
        }
        val conv2 = memo.memoizeIf("foo", true) {
            Conv(Int::toString, String::toInt)
        }
        memo.finalize()

        assertSame(conv1, conv2)
    }

    @Test
    fun `GraphQLType_supportsSelections`(): Unit =
        runBlocking {
            val schema = MockSchema.mk(
                """
                extend type Query { placeholder: Int }
                union Union = Query
                interface Interface { x:Int }
                input Input { x:Int }
                enum Enum { A }
                """.trimIndent()
            )

            fun decorate(type: GraphQLType): List<GraphQLType> =
                listOf(
                    type,
                    GraphQLList(type),
                    GraphQLNonNull(type),
                    GraphQLNonNull(GraphQLList(type)),
                    GraphQLList(GraphQLNonNull(type)),
                    GraphQLList(GraphQLList(type)),
                )

            val (hasSelections, noSelections) = schema.schema
                .allTypesAsList
                .filterNot { it.name.startsWith("__") }
                .partition { t ->
                    t.name in setOf("Query", "Union", "Interface")
                }

            hasSelections.flatMap(::decorate)
                .exhaustive()
                .forAll { it.supportsSelections }

            noSelections.flatMap(::decorate)
                .exhaustive()
                .forNone { it.supportsSelections }
        }

    @Test
    fun `GraphQLCompositeType_mappableFields`() {
        val schema = MockSchema.mk(
            """
                extend type Query { placeholder: Int }
                union Union = Query
                interface Interface { x:Int }
                type Obj { x:Int, y:Int }
            """.trimIndent()
        )

        // union
        assertEquals(
            setOf("__typename"),
            schema.schema.getTypeAs<GraphQLCompositeType>("Union")
                .mappableFields.map { it.name }.toSet()
        )

        // object
        assertEquals(
            setOf("__typename", "x", "y"),
            schema.schema.getObjectType("Obj")
                .mappableFields.map { it.name }.toSet()
        )

        // interface
        assertEquals(
            setOf("__typename", "x"),
            schema.schema.getTypeAs<GraphQLCompositeType>("Interface")
                .mappableFields.map { it.name }.toSet()
        )
    }

    @Test
    fun `mkSelectionConvs -- map is keyed by field name when selectionSet is null`() {
        val schema = MockSchema.mk("type Obj { x:Int }")
        val map = mkSelectionConvs(
            schema,
            schema.schema.getObjectType("Obj"),
            null
        ) { _, _ ->
            Conv.identity<Int>()
        }
        assertEquals(setOf("x", "__typename"), map.keys)
    }

    @Test
    fun `mkSelectionConvs -- map is keyed by selection name when selectionSet is non-null`() {
        val schema = MockSchema.mk("type Obj { x:Int }")
        val map = mkSelectionConvs(
            schema,
            schema.schema.getObjectType("Obj"),
            mkRawSelectionSet(
                SelectionsParser.parse(
                    "Obj",
                    "x1:x, x2:x, type1:__typename, type2:__typename"
                ),
                schema,
                emptyMap()
            )
        ) { _, _ ->
            Conv.identity<Int>()
        }
        assertEquals(setOf("x1", "x2", "type1", "type2"), map.keys)
    }
}
