package viaduct.graphql.utils

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GraphQLTypeRelationsTest : Assertions() {
    private class Fixture(sdl: String) {
        var schema: GraphQLSchema
        var rels: GraphQLTypeRelations

        val String.asCompositeType: GraphQLCompositeType get() =
            schema.getType(this) as GraphQLCompositeType

        init {
            val amendedSdl = """
              type Query { placeholder: Int }
              schema { query: Query }
              $sdl
            """
            schema = mkSchema(amendedSdl)
            rels = GraphQLTypeRelations(schema)
        }

        fun isSpreadable(
            a: String,
            b: String
        ): Boolean {
            val aa = a.asCompositeType
            val bb = b.asCompositeType
            val isSpreadable = rels.isSpreadable(aa, bb)
            val inSpreadableTypes = rels.spreadableTypes(aa).contains(bb)
            return isSpreadable && inSpreadableTypes
        }

        fun relationUnwrapped(
            a: String,
            b: String
        ): GraphQLTypeRelation.Relation = rels.relationUnwrapped(a.asCompositeType, b.asCompositeType)

        fun assertSpreadable(
            a: String,
            b: String
        ) = assertSpreadable(setOf(a), setOf(b))

        fun assertSpreadable(
            parentTypes: Set<String>,
            fragmentTypes: Set<String>
        ) {
            parentTypes.forEach { parent ->
                fragmentTypes.forEach { fragment ->
                    assertTrue(isSpreadable(parent, fragment), "$parent -> $fragment")
                }
            }
        }

        fun assertNarrowingSequence(vararg types: String) {
            types.indices.forEach { i ->
                types.indices.forEach { j ->
                    val ti = types[i]
                    val tj = types[j]
                    when (i.compareTo(j)) {
                        -1 -> assertEquals(GraphQLTypeRelation.WiderThan, relationUnwrapped(ti, tj))
                        0 -> assertEquals(GraphQLTypeRelation.Same, relationUnwrapped(ti, tj))
                        1 -> assertEquals(GraphQLTypeRelation.NarrowerThan, relationUnwrapped(ti, tj))
                    }
                }
            }
        }

        fun assertMutuallySpreadable(vararg types: String) = assertSpreadable(types.toSet(), types.toSet())

        fun assertPossibleObjectTypes(
            type: String,
            vararg possibleObjTypes: String
        ) {
            val actual = rels.possibleObjectTypes(type.asCompositeType).toList().map { it.name }
            assertEquals(possibleObjTypes.toSet(), actual.toSet())
        }
    }

    @Test
    fun identity() {
        val f =
            Fixture(
                """
                    type Object { x: Int }
                    union Union = Object
                    interface Interface { id: ID! }
                """.trimIndent()
            )
        listOf("Object", "Union", "Interface").forEach { t ->
            f.assertSpreadable(t, t)

            assertEquals(
                GraphQLTypeRelation.Same,
                f.relationUnwrapped(t, t)
            )
        }
    }

    @Test
    fun `isSpreadable -- union-member`() {
        val f =
            Fixture(
                """
                    type Object { x: Int }
                    union Union = Object
                """.trimIndent()
            )
        f.assertMutuallySpreadable("Object", "Union")
    }

    @Test
    fun `isSpreadable -- interface-impl`() {
        val f =
            Fixture(
                """
                    interface Interface { id: ID! }
                    type Impl implements Interface { id: ID! }
                """.trimIndent()
            )
        f.assertMutuallySpreadable("Interface", "Impl")
    }

    @Test
    fun `unrelated types`() {
        val f =
            Fixture(
                """
                    type Foo { id: ID! }
                    type Bar { id: ID! }
                    interface Interface { id: ID! }
                    type Baz { placeholder: ID! }
                    union Union = Baz
                """.trimIndent()
            )

        val types = listOf("Foo", "Bar", "Interface", "Union")
        types.forEach { a ->
            types
                .filter { it != a }
                .forEach { b ->
                    assertEquals(
                        GraphQLTypeRelation.None,
                        f.relationUnwrapped(a, b)
                    )
                    assertFalse(f.isSpreadable(a, b))
                }
        }
    }

    @Test
    fun `interface coparents`() {
        val f =
            Fixture(
                """
                    interface Int1 { id: ID! }
                    interface Int2 { id: ID! }
                    interface Int3 { id: ID! }
                    type Object implements Int1 & Int2 & Int3 { id: ID! }
                """.trimIndent()
            )

        val types = setOf("Int1", "Int2", "Int3")
        f.assertSpreadable(types, types)

        types.forEach { a ->
            types
                .filter { it != a }
                .forEach { b ->
                    assertEquals(
                        GraphQLTypeRelation.Coparent,
                        f.relationUnwrapped(a, b)
                    )
                }
        }
    }

    @Test
    fun `interface chain`() {
        val f =
            Fixture(
                """
                    interface Int1 { id: ID! }
                    interface Int2 implements Int1 { id: ID! }
                    interface Int3 implements Int1 & Int2 { id: ID! }
                    type Object implements Int1 & Int2 & Int3 { id: ID! }
                """
            )

        f.assertMutuallySpreadable("Int1", "Int2", "Int3", "Object")
        f.assertNarrowingSequence("Int1", "Int2", "Int3", "Object")
    }

    @Test
    fun `isSpreadable -- union coparent spreadability`() {
        val f =
            Fixture(
                """
                type Object { id: ID! }
                union Union1 = Object
                union Union2 = Object
                """.trimIndent()
            )

        f.assertMutuallySpreadable("Object", "Union1", "Union2")
        assertEquals(
            GraphQLTypeRelation.Coparent,
            f.relationUnwrapped("Union1", "Union2")
        )
    }

    @Test
    fun `isSpreadable -- union-interface coparents`() {
        val f =
            Fixture(
                """
                    type Object implements Interface{ id: ID! }
                    union Union = Object
                    interface Interface { id: ID! }
                """.trimIndent()
            )

        f.assertMutuallySpreadable("Object", "Union", "Interface")
    }

    @Test
    fun `isSpreadable -- union siblings are not spreadable`() {
        // See rule 5.5.2.3.1 :
        // In the scope of an object type, the only valid object type fragment
        // spread is one that applies to the same type that is in scope.
        val f =
            Fixture(
                """
                    type Foo { id: ID! }
                    type Bar { id: ID! }
                    union Union = Foo | Bar
                """.trimIndent()
            )

        assertFalse(f.isSpreadable("Foo", "Bar"))
        assertFalse(f.isSpreadable("Bar", "Foo"))
    }

    @Test
    fun `throws when type is not in schema`() {
        val schema =
            mkSchema(
                """
                    schema { query: Query }
                    type Query { placeholder: Int }
                    type Foo { id: ID! }
                """.trimIndent()
            )
        val rels = GraphQLTypeRelations(schema)

        val foo = schema.getType("Foo") as GraphQLCompositeType
        val other = GraphQLObjectType.newObject().name("Other").build()

        assertThrows<IllegalArgumentException> {
            rels.relationUnwrapped(foo, other)
        }

        assertThrows<IllegalArgumentException> {
            rels.relationUnwrapped(other, foo)
        }

        assertThrows<IllegalArgumentException> {
            rels.isSpreadable(foo, other)
        }

        assertThrows<IllegalArgumentException> {
            rels.spreadableTypes(other)
        }
    }

    @Test
    fun `union members`() {
        val f =
            Fixture(
                """
                    interface Interface { id: ID }
                    type Foo implements Interface { id: ID }
                    type Bar implements Interface { id: ID }
                    union FooOrBar = Foo | Bar
                """.trimIndent()
            )
        // assert that the union is wider than its members and that
        // its members are narrower than the union
        f.assertNarrowingSequence("FooOrBar", "Foo")
        f.assertNarrowingSequence("FooOrBar", "Bar")

        // assert that for each member, it and all of its parents are
        // mutually spreadable
        f.assertMutuallySpreadable("Foo", "FooOrBar", "Interface")
        f.assertMutuallySpreadable("Bar", "FooOrBar", "Interface")

        // assert that union members are not spreadable
        assertFalse(f.isSpreadable("Foo", "Bar"))
    }

    @Test
    fun `possibleObjectTypes -- interfaces`() {
        Fixture(
            """
                interface Interface { id: ID }
                interface Mid implements Interface { id: ID, x: Int }
                type Foo implements Interface { id: ID }
                type Bar implements Mid & Interface { id: ID, x: Int }
            """.trimIndent()
        ).apply {
            assertPossibleObjectTypes("Interface", "Foo", "Bar")
            assertPossibleObjectTypes("Mid", "Bar")
        }
    }

    @Test
    fun `possibleObjectTypes -- object`() {
        Fixture(
            """
                interface Interface { id: ID }
                interface Mid implements Interface { id: ID, x: Int }
                type Foo implements Interface { id: ID }
                type Bar implements Mid & Interface { id: ID, x: Int }
                type Baz { y: Int }
                union Union = Bar | Baz
            """.trimIndent()
        ).apply {
            assertPossibleObjectTypes("Foo", "Foo")
            assertPossibleObjectTypes("Bar", "Bar")
        }
    }

    @Test
    fun `possibleObjectTypes -- union`() {
        Fixture(
            """
                type Foo { id: ID }
                type Bar { id: ID, x: Int }
                type Baz { y: Int }
                union Union = Bar | Baz
            """.trimIndent()
        ).apply {
            assertPossibleObjectTypes("Union", "Bar", "Baz")
        }
    }

    @Test
    fun `abstract-abstract interfaces without concrete impl`() {
        Fixture(
            """
                interface A { x: Int }
                interface B implements A { x: Int }
            """.trimIndent()
        ).apply {
            val a = "A".asCompositeType
            val b = "B".asCompositeType
            assertFalse(rels.isSpreadable(a, b))
            assertFalse(rels.isSpreadable(b, a))
            assertFalse(rels.spreadableTypes(a).contains(b))
            assertFalse(rels.spreadableTypes(b).contains(a))
        }
    }
}

fun mkSchema(sdl: String): GraphQLSchema {
    val tdr = SchemaParser().parse(sdl)
    return SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
}
