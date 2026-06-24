package viaduct.arbitrary.graphql

import io.kotest.property.RandomSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema

class ResolverConfigTest : KotestPropertyBase() {
    @Test
    fun `fieldResolverOutputSelectionSet -- simple`() {
        val rc = ResolverConfigImpl(
            "extend type Query { x:Int @resolver }".asViaductSchema,
            Config.default,
            randomSource
        )
        assertEquals(
            setOf("Query" to "x"),
            rc.fieldResolverOutputSelectionSet("Query" to "x")
        )
    }

    @Test
    fun `fieldResolverOutputSelectionSet -- excludes nodes with resolvers`() {
        val rc = ResolverConfigImpl(
            """
                extend type Query { obj:Obj @resolver }
                type Obj { a:A, b:B }
                type A implements Node @resolver { id:ID! }
                type B implements Node { id:ID! }
            """.asViaductSchema,
            Config.default,
            randomSource
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
        val rc = ResolverConfigImpl(
            """
                extend type Query { obj:Obj @resolver }
                type Obj { obj: Obj }
            """.asViaductSchema,
            Config.default + (IncludeRequiredResolvers to false),
            randomSource
        )

        assertEquals(
            setOf("Query" to "obj", "Obj" to "obj"),
            rc.fieldResolverOutputSelectionSet("Query" to "obj")
        )
    }

    @Test
    fun `fieldResolverOutputSelectionSet -- forked cycle`() {
        val rc = ResolverConfigImpl(
            """
                extend type Query { foo: Foo @resolver }
                type Foo { x:Obj @resolver, b:Obj }
                type Obj { x:Int, obj: Obj }
            """.asViaductSchema,
            Config.default + (IncludeRequiredResolvers to false),
            randomSource
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
        ResolverConfigImpl(
            schema,
            Config.default +
                (IncludeRequiredResolvers to false) +
                (UndeclaredFieldResolverWeight to 0.0),
            randomSource
        ).let { rc ->
            assertEquals(emptySet<Coordinate>(), rc.fieldResolvers)
        }

        // enabled
        ResolverConfigImpl(
            schema,
            Config.default + (UndeclaredFieldResolverWeight to 1.0),
            randomSource
        ).let { rc ->
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
    fun `IncludeRequiredResolvers -- default includes root fields when undeclared field weight is zero`() {
        val schema = """
            extend type Query { x: Int }
            extend type Mutation { x: Int }
            extend type Subscription { x: Int }
        """.asViaductSchema

        val rc = ResolverConfigImpl(
            schema,
            Config.default + (UndeclaredFieldResolverWeight to 0.0),
            randomSource
        )
        assertEquals(
            setOf(
                "Query" to "x",
                "Mutation" to "x",
                "Subscription" to "x"
            ),
            rc.fieldResolvers
        )
    }

    @Test
    fun `IncludeRequiredResolvers -- root fields`() {
        val schema = """
            extend type Query { x: Int }
            extend type Mutation { x: Int }
            extend type Subscription { x: Int }
        """.asViaductSchema

        // disabled
        ResolverConfigImpl(
            schema,
            Config.default + (IncludeRequiredResolvers to false),
            randomSource
        ).let { rc ->
            assertEquals(emptySet<Coordinate>(), rc.fieldResolvers)
        }

        // enabled
        ResolverConfigImpl(
            schema,
            Config.default + (IncludeRequiredResolvers to true),
            randomSource
        ).let { rc ->
            assertEquals(
                setOf(
                    "Query" to "x",
                    "Mutation" to "x",
                    "Subscription" to "x"
                ),
                rc.fieldResolvers
            )
        }
    }

    @Test
    fun `nodeResolverOutputSelectionSet -- simple`() {
        val rc = ResolverConfigImpl(
            """
                extend type Query { obj: Obj }
                type Obj implements Node @resolver { id:ID!, a:Int @resolver, b:Int }
            """.asViaductSchema,
            Config.default,
            randomSource
        )

        assertEquals(
            setOf("Obj" to "b"),
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
        ResolverConfigImpl(
            schema,
            Config.default + (UndeclaredNodeResolverWeight to 0.0),
            randomSource
        ).let { rc ->
            assertEquals(emptySet<String>(), rc.nodeResolvers)
        }

        // enabled
        ResolverConfigImpl(
            schema,
            Config.default + (UndeclaredNodeResolverWeight to 1.0),
            randomSource
        ).let { rc ->
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
        val rc1 = ResolverConfigImpl(schema, fieldResolvers = setOf("Query" to "a"), nodeResolvers = setOf("Foo"))
        val rc2 = ResolverConfigImpl(schema, fieldResolvers = setOf("Query" to "b"), nodeResolvers = setOf("Bar"))

        val combined = rc1.plus(rc2)

        assertEquals(setOf("Query" to "a", "Query" to "b"), combined.fieldResolvers)
        assertEquals(setOf("Foo", "Bar"), combined.nodeResolvers)
    }

    @Test
    fun `plus -- throws if schemas differ`() {
        val schema1 = "extend type Query { x:Int @resolver }".asViaductSchema
        val schema2 = "extend type Query { x:Int @resolver }".asViaductSchema
        val rc1 = ResolverConfigImpl(schema1, fieldResolvers = emptySet(), nodeResolvers = emptySet())
        val rc2 = ResolverConfigImpl(schema2, fieldResolvers = emptySet(), nodeResolvers = emptySet())

        assertThrows<IllegalArgumentException> { rc1.plus(rc2) }
    }

    @Test
    fun `fieldResolverOutputSelectionSet -- throws for non-resolver coord`() {
        val rc = ResolverConfigImpl(
            "extend type Query { x:Int }".asViaductSchema,
            Config.default + (IncludeRequiredResolvers to false),
            randomSource
        )
        assertThrows<IllegalArgumentException> {
            rc.fieldResolverOutputSelectionSet("Query" to "x")
        }
    }

    @Test
    fun `nodeResolverOutputSelectionSet -- throws for non-resolver type`() {
        val rc = ResolverConfigImpl(
            "type Foo implements Node { id:ID! }".asViaductSchema,
            Config.default,
            randomSource
        )
        assertThrows<IllegalArgumentException> {
            rc.nodeResolverOutputSelectionSet("Foo")
        }
    }

    @Test
    fun `nodeResolverOutputSelectionSet -- cycle`() {
        val rc = ResolverConfigImpl(
            "type Obj implements Node @resolver { id:ID!, obj:Obj }".asViaductSchema,
            Config.default + (IncludeRequiredResolvers to false),
            randomSource
        )

        assertEquals(
            setOf("Obj" to "obj"),
            rc.nodeResolverOutputSelectionSet("Obj")
        )
    }

    @Test
    fun `nodeResolverOutputSelectionSet -- nested composites`() {
        val rc = ResolverConfigImpl(
            """
                type Obj implements Node @resolver { id:ID!, child:Child }
                type Child { x:Int, grandchild:Grandchild }
                type Grandchild { y:Int }
            """.asViaductSchema,
            Config.default,
            randomSource
        )

        assertEquals(
            setOf(
                "Obj" to "child",
                "Child" to "x",
                "Child" to "grandchild",
                "Grandchild" to "y"
            ),
            rc.nodeResolverOutputSelectionSet("Obj")
        )
    }

    @Test
    fun `nodeResolverOutputSelectionSet -- excludes resolvers`() {
        val rc = ResolverConfigImpl(
            """
                type Obj implements Node @resolver { id:ID!, x:Int @resolver, y:Int }
            """.asViaductSchema,
            Config.default,
            randomSource
        )

        assertEquals(
            setOf("Obj" to "y"),
            rc.nodeResolverOutputSelectionSet("Obj")
        )
    }

    @Test
    fun `fieldResolverOutputSelectionSet -- interface expansion`() {
        val rc = ResolverConfigImpl(
            """
                extend type Query { node:Animal @resolver }
                interface Animal { name:String }
                type Dog implements Animal { name:String, breed:String }
                type Cat implements Animal { name:String, lives:Int }
            """.asViaductSchema,
            Config.default,
            randomSource
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
        val rc = ResolverConfigImpl(
            schema,
            Config.default +
                (IncludeRequiredResolvers to false) +
                (UndeclaredFieldResolverWeight to 0.0) +
                (UndeclaredNodeResolverWeight to 0.0),
            randomSource
        )

        // @resolver-annotated field is included; non-annotated field is excluded
        assertTrue(rc.fieldResolvers.contains("Query" to "declared"), rc.fieldResolvers.toString())
        assertFalse(rc.fieldResolvers.contains("Query" to "undeclared"), rc.fieldResolvers.toString())
        // @resolver-annotated node type is included; non-annotated node type is excluded
        assertEquals(setOf("Foo"), rc.nodeResolvers)
    }

    @Test
    fun `factory -- declared resolver directives own selectivity`() {
        val schema = ViaductSchema(
            """
            directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION | OBJECT
            interface Node { id: ID! }
            type Query {
              selectiveField: Int @resolver(isSelective: true)
              plainField: Int @resolver
              undeclaredField: Int
            }
            type SelectiveNode implements Node @resolver(isSelective: true) { id: ID! }
            type PlainNode implements Node @resolver { id: ID! }
            """.asSchema
        )
        val rc = ResolverConfigImpl(
            schema,
            Config.default +
                (IncludeRequiredResolvers to false) +
                (UndeclaredFieldResolverWeight to 0.0) +
                (UndeclaredNodeResolverWeight to 0.0),
            randomSource
        )

        assertTrue(rc.isSelective("Query" to "selectiveField"))
        assertFalse(rc.isSelective("Query" to "plainField"))
        assertTrue(rc.isSelective("SelectiveNode" to null))
        assertFalse(rc.isSelective("PlainNode" to null))
        assertThrows<IllegalArgumentException> {
            rc.isSelective("Query" to "undeclaredField")
        }
    }

    @Test
    fun `factory -- declared resolver directives own batching`() {
        val schema = ViaductSchema(
            """
            directive @resolver(isSelective: Boolean! = false, isBatching: Boolean! = false) on FIELD_DEFINITION | OBJECT
            interface Node { id: ID! }
            type Query {
              batchingField: Int @resolver(isBatching: true)
              plainField: Int @resolver
              undeclaredField: Int
            }
            type BatchingNode implements Node @resolver(isBatching: true) { id: ID! }
            type PlainNode implements Node @resolver { id: ID! }
            """.asSchema
        )
        val rc = ResolverConfigImpl(
            schema,
            Config.default +
                (IncludeRequiredResolvers to false) +
                (UndeclaredFieldResolverWeight to 0.0) +
                (UndeclaredNodeResolverWeight to 0.0),
            randomSource
        )

        assertTrue(rc.isBatching("Query" to "batchingField"))
        assertFalse(rc.isBatching("Query" to "plainField"))
        assertTrue(rc.isBatching("BatchingNode" to null))
        assertFalse(rc.isBatching("PlainNode" to null))
        assertThrows<IllegalArgumentException> {
            rc.isBatching("Query" to "undeclaredField")
        }
    }

    @Test
    fun `factory -- generated resolvers sample SelectiveResolverWeight`() {
        val schema = """
            extend type Query { obj: Obj }
            type Obj { generatedField: Int }
        """.asViaductSchema
        val rc = ResolverConfigImpl(
            schema,
            Config.default +
                (IncludeRequiredResolvers to false) +
                (UndeclaredFieldResolverWeight to 1.0) +
                (UndeclaredNodeResolverWeight to 0.0) +
                (SelectiveResolverWeight to 1.0),
            randomSource
        )

        assertTrue(rc.isSelective("Obj" to "generatedField"))
    }

    @Test
    fun `factory -- generated resolvers sample BatchingResolverWeight`() {
        val schema = """
            extend type Query { obj: Obj }
            type Obj implements Node { id: ID!, generatedField: Int }
        """.asViaductSchema
        val rc = ResolverConfigImpl(
            schema,
            Config.default +
                (IncludeRequiredResolvers to false) +
                (UndeclaredFieldResolverWeight to 1.0) +
                (UndeclaredNodeResolverWeight to 1.0) +
                (BatchingResolverWeight to 1.0),
            randomSource
        )

        assertTrue(rc.isBatching("Obj" to "generatedField"))
        assertTrue(rc.isBatching("Obj" to null))
    }

    @Test
    fun `factory -- field breaker makes non-null object cycle inhabited`() {
        val schema = """
            extend type Query { obj: Obj! @resolver }
            type Obj { obj: Obj! }
        """.asViaductSchema

        val rc = ResolverConfigImpl(
            schema,
            Config.default +
                (UndeclaredFieldResolverWeight to 0.0) +
                (UndeclaredNodeResolverWeight to 0.0),
            randomSource
        )

        assertTrue(rc.fieldResolvers.contains("Obj" to "obj"), rc.fieldResolvers.toString())
        assertFalse(rc.containsUninhabitedResolvers())
    }

    @Test
    fun `factory -- field breaker makes non-null node cycle inhabited when field sampling is disabled`() {
        val schema = """
            extend type Query { obj: Obj! @resolver }
            type Obj implements Node { id: ID!, obj: Obj! }
        """.asViaductSchema

        val rc = ResolverConfigImpl(
            schema,
            Config.default +
                (UndeclaredFieldResolverWeight to 0.0) +
                (UndeclaredNodeResolverWeight to 0.0),
            randomSource
        )

        assertTrue(rc.fieldResolvers.contains("Obj" to "obj"), rc.fieldResolvers.toString())
        assertFalse(rc.isSelective("Obj" to "obj"))
        assertFalse(rc.containsUninhabitedResolvers())
    }

    @Test
    fun `factory -- cycle mitigation is disabled when ensure inhabited resolver graphs is false`() {
        val schema = """
            extend type Query { obj: Obj! @resolver }
            type Obj { obj: Obj! }
        """.asViaductSchema

        val rc = ResolverConfigImpl(
            schema,
            Config.default + (IncludeRequiredResolvers to false),
            randomSource
        )

        assertTrue(rc.containsUninhabitedResolvers())
    }

    @Test
    fun `factory -- field breaker makes nullable abstract cycle inhabited`() {
        val schema = """
            schema { query: Root }
            type Root { subject: Subject @resolver }
            interface Subject { cycle: Subject }
            type SubjectImpl implements Subject { cycle: Subject }
        """.asViaductSchema

        val rc = ResolverConfigImpl(
            schema,
            Config.default,
            randomSource
        )

        assertTrue(rc.fieldResolvers.contains("SubjectImpl" to "cycle"), rc.fieldResolvers.toString())
        assertFalse(rc.isSelective("SubjectImpl" to "cycle"))
        assertFalse(rc.containsUninhabitedResolvers())
    }

    @Test
    fun `factory -- field breaker makes nullable fields and lists inhabited`() {
        val schema = """
            extend type Query { obj: Obj! @resolver }
            type Obj {
              nullableObj: Obj
              objList: [Obj!]!
            }
        """.asViaductSchema

        val rc = ResolverConfigImpl(
            schema,
            Config.default,
            randomSource
        )

        assertTrue(rc.fieldResolvers.contains("Obj" to "nullableObj"), rc.fieldResolvers.toString())
        assertTrue(rc.fieldResolvers.contains("Obj" to "objList"), rc.fieldResolvers.toString())
        assertFalse(rc.containsUninhabitedResolvers())
    }

    @Test
    fun `factory -- field breaker makes one hard recursive edge among simple fields inhabited`() {
        val schema = """
            extend type Query { obj: Obj! @resolver }
            type Obj { obj:Obj! }
        """.asViaductSchema

        // disabled
        ResolverConfigImpl(
            schema,
            Config.default + (IncludeRequiredResolvers to false),
            randomSource
        ).let {
            assertTrue(it.containsUninhabitedResolvers())
        }

        // enabled
        ResolverConfigImpl(
            schema,
            Config.default,
            randomSource
        ).let {
            assertFalse(it.containsUninhabitedResolvers())
        }
    }

    @Test
    fun `factory -- field breaker makes chained hard edges inhabited`() {
        val schema = """
            extend type Query { obj: Obj! @resolver }
            type Obj { child: Child! }
            type Child { grandchild: Grandchild! }
            type Grandchild { obj: Obj! }
        """.asViaductSchema

        // disabled
        ResolverConfigImpl(
            schema,
            Config.default + (IncludeRequiredResolvers to false),
            randomSource
        ).let {
            assertTrue(it.containsUninhabitedResolvers())
        }

        // enabled
        ResolverConfigImpl(
            schema,
            Config.default,
            randomSource
        ).let {
            assertFalse(it.containsUninhabitedResolvers())
        }
    }

    @Test
    fun `factory -- field breaker breaks uninhabited interface cycles`() {
        val schema = """
            extend type Query { child: Child! }
            type Child { recursive: Recursive }
            interface Recursive { next: Recursive! }
            type RecursiveChild implements Recursive { next: Recursive! }
        """.trimIndent().asViaductSchema

        val rc = ResolverConfigImpl(
            schema,
            Config.default + (SelectiveResolverWeight to 0.0),
            RandomSource.seeded(1)
        )

        assertEquals(setOf("Query" to "child", "RecursiveChild" to "next"), rc.fieldResolvers)
        assertFalse(rc.isSelective("RecursiveChild" to "next"))
        assertFalse(rc.containsUninhabitedResolvers())
    }
}
