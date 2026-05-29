package viaduct.arbitrary.graphql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.Config
import viaduct.engine.api.Coordinate

class ResolverCoordinatesTest {
    @Test
    fun `fieldResolverOutputSelectionSet -- simple`() {
        val rc = ResolverCoordinates(
            """
                extend type Query {
                    x:Int @resolver
                }
            """.asViaductSchema
        )

        assertEquals(
            setOf("Query" to "x"),
            rc.fieldResolverOutputSelectionSet("Query" to "x")
        )
    }

    @Test
    fun `fieldResolverOutputSelectionSet -- does not traverse into nodes with resolvers`() {
        val rc = ResolverCoordinates(
            """
                extend type Query { obj:Obj @resolver }
                type Obj { a:A, b:B }
                type A implements Node @resolver { id:ID! }
                type B implements Node { id:ID! }
            """.asViaductSchema
        )

        assertEquals(
            setOf(
                "Query" to "obj",
                "Obj" to "a",
                "Obj" to "b",
                // A fields are not included because they are covered by the A node resolver
                // B fields are included because it is a node without a resolver
                "B" to "id"
            ),
            rc.fieldResolverOutputSelectionSet("Query" to "obj")
        )
    }

    @Test
    fun `fieldResolverOutputSelectionSet -- simple cycle`() {
        val rc = ResolverCoordinates(
            """
                extend type Query {
                    obj:Obj @resolver
                }
                type Obj {
                    obj: Obj
                }
            """.asViaductSchema
        )

        assertEquals(
            setOf("Query" to "obj", "Obj" to "obj"),
            rc.fieldResolverOutputSelectionSet("Query" to "obj")
        )
    }

    @Test
    fun `fieldResolverOutputSelectionSet -- forked cycle`() {
        val rc = ResolverCoordinates(
            """
                extend type Query { foo: Foo @resolver }
                type Foo { x:Obj @resolver, b:Obj }
                type Obj { x:Int, obj: Obj }
            """.asViaductSchema
        )

        assertEquals(
            setOf(
                "Query" to "foo",
                "Foo" to "b",
                "Obj" to "x",
                "Obj" to "obj"
            ),
            rc.fieldResolverOutputSelectionSet("Query" to "foo")
        )

        assertEquals(
            setOf(
                "Foo" to "x",
                "Obj" to "x",
                "Obj" to "obj"
            ),
            rc.fieldResolverOutputSelectionSet("Foo" to "x")
        )
    }

    @Test
    fun `UndeclaredFieldResolverWeight`() {
        val schema = """
            extend type Query { foo: Foo }
            type Foo { x:Int, foo:Foo }
        """.asViaductSchema

        // disabled
        ResolverCoordinates(schema, Config.default + (UndeclaredFieldResolverWeight to 0.0))
            .let { rc ->
                assertEquals(emptySet<Coordinate>(), rc.fieldResolvers)
            }

        // enabled
        ResolverCoordinates(schema, Config.default + (UndeclaredFieldResolverWeight to 1.0))
            .let { rc ->
                val exp = listOf(
                    "Query" to "foo",
                    "Foo" to "x",
                    "Foo" to "foo"
                )
                assertTrue(
                    rc.fieldResolvers.containsAll(exp),
                    rc.fieldResolvers.toString()
                )
            }
    }

    @Test
    fun `nodeResolverOutputSelectionSet -- simple`() {
        val rc = ResolverCoordinates(
            """
                extend type Query { obj: Obj }
                type Obj implements Node @resolver { id:ID!, a:Int @resolver, b:Int }
            """.asViaductSchema
        )

        assertEquals(
            setOf(
                "Obj" to "id",
                "Obj" to "b"
            ),
            rc.nodeResolverOutputSelectionSet("Obj")
        )
    }

    @Test
    fun `UndeclaredNodeResolverWeight`() {
        val schema = """
            type Foo implements Node { id:ID! }
            type Bar implements Node { id:ID! }
            type Baz { id:ID! }
        """.asViaductSchema

        // disabled
        ResolverCoordinates(schema, Config.default + (UndeclaredNodeResolverWeight to 0.0))
            .let { rc ->
                assertEquals(emptySet<String>(), rc.nodeResolvers)
            }

        // enabled
        ResolverCoordinates(schema, Config.default + (UndeclaredNodeResolverWeight to 1.0))
            .let { rc ->
                val exp = setOf("Foo", "Bar")
                assertTrue(rc.nodeResolvers.containsAll(exp), rc.nodeResolvers.toString())
            }
    }

    @Test
    fun `plus -- combines field and node resolvers`() {
        val schema = """
            extend type Query { a:Int @resolver, b:Int @resolver }
            type Foo implements Node @resolver { id:ID! }
            type Bar implements Node @resolver { id:ID! }
        """.asViaductSchema
        val rc1 = ResolverCoordinates(schema, fieldResolvers = setOf("Query" to "a"), nodeResolvers = setOf("Foo"))
        val rc2 = ResolverCoordinates(schema, fieldResolvers = setOf("Query" to "b"), nodeResolvers = setOf("Bar"))

        val combined = rc1.plus(rc2)

        assertEquals(setOf("Query" to "a", "Query" to "b"), combined.fieldResolvers)
        assertEquals(setOf("Foo", "Bar"), combined.nodeResolvers)
    }

    @Test
    fun `plus -- throws if schemas differ`() {
        val schema1 = "extend type Query { x:Int @resolver }".asViaductSchema
        val schema2 = "extend type Query { x:Int @resolver }".asViaductSchema
        val rc1 = ResolverCoordinates(schema1, fieldResolvers = emptySet(), nodeResolvers = emptySet())
        val rc2 = ResolverCoordinates(schema2, fieldResolvers = emptySet(), nodeResolvers = emptySet())

        assertThrows<IllegalArgumentException> { rc1.plus(rc2) }
    }

    @Test
    fun `fieldResolverOutputSelectionSet -- throws for non-resolver coord`() {
        val rc = ResolverCoordinates(
            "extend type Query { x:Int }".asViaductSchema
        )

        assertThrows<IllegalArgumentException> {
            rc.fieldResolverOutputSelectionSet("Query" to "x")
        }
    }

    @Test
    fun `nodeResolverOutputSelectionSet -- throws for non-resolver type`() {
        val rc = ResolverCoordinates(
            "type Foo implements Node { id:ID! }".asViaductSchema
        )

        assertThrows<IllegalArgumentException> {
            rc.nodeResolverOutputSelectionSet("Foo")
        }
    }

    @Test
    fun `nodeResolverOutputSelectionSet -- cycle`() {
        val rc = ResolverCoordinates(
            """
                type Obj implements Node @resolver { id:ID!, obj:Obj }
            """.asViaductSchema
        )

        assertEquals(
            setOf("Obj" to "id", "Obj" to "obj"),
            rc.nodeResolverOutputSelectionSet("Obj")
        )
    }

    @Test
    fun `nodeResolverOutputSelectionSet -- nested composites`() {
        val rc = ResolverCoordinates(
            """
                type Obj implements Node @resolver { id:ID!, child:Child }
                type Child { x:Int, grandchild:Grandchild }
                type Grandchild { y:Int }
            """.asViaductSchema
        )

        assertEquals(
            setOf(
                "Obj" to "id",
                "Obj" to "child",
                "Child" to "x",
                "Child" to "grandchild",
                "Grandchild" to "y"
            ),
            rc.nodeResolverOutputSelectionSet("Obj")
        )
    }

    @Test
    fun `nodeResolverOutputSelectionSet -- stops at field resolvers`() {
        val rc = ResolverCoordinates(
            """
                type Obj implements Node @resolver { id:ID!, computed:Int @resolver, plain:Int }
            """.asViaductSchema
        )

        assertEquals(
            setOf("Obj" to "id", "Obj" to "plain"),
            rc.nodeResolverOutputSelectionSet("Obj")
        )
    }

    @Test
    fun `fieldResolverOutputSelectionSet -- interface expansion`() {
        val rc = ResolverCoordinates(
            """
                extend type Query { node:Animal @resolver }
                interface Animal { name:String }
                type Dog implements Animal { name:String, breed:String }
                type Cat implements Animal { name:String, lives:Int }
            """.asViaductSchema
        )

        assertEquals(
            setOf(
                "Query" to "node",
                "Dog" to "name",
                "Dog" to "breed",
                "Cat" to "name",
                "Cat" to "lives"
            ),
            rc.fieldResolverOutputSelectionSet("Query" to "node")
        )
    }

    @Test
    fun `factory -- declared @resolver directives`() {
        val schema = """
            extend type Query { declared:Int @resolver, undeclared:Int }
            type Foo implements Node @resolver { id:ID! }
            type Bar implements Node { id:ID! }
        """.asViaductSchema
        val rc = ResolverCoordinates(
            schema,
            Config.default + (UndeclaredFieldResolverWeight to 0.0) + (UndeclaredNodeResolverWeight to 0.0)
        )

        // @resolver-annotated field is included; non-annotated field is excluded
        assertTrue(rc.fieldResolvers.contains("Query" to "declared"), rc.fieldResolvers.toString())
        assertFalse(rc.fieldResolvers.contains("Query" to "undeclared"), rc.fieldResolvers.toString())
        // @resolver-annotated node type is included; non-annotated node type is excluded
        assertEquals(setOf("Foo"), rc.nodeResolvers)
    }
}
