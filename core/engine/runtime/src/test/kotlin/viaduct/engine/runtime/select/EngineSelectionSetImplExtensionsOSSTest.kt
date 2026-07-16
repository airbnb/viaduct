package viaduct.engine.runtime.select

import graphql.GraphQLContext
import graphql.language.AstPrinter
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.InlineFragment
import graphql.language.Node
import graphql.language.SelectionSet
import graphql.language.TypeName
import graphql.parser.Parser
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import java.util.Locale
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.fragment.FragmentSource
import viaduct.engine.api.fragment.FragmentVariables
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.execution.constraints.Constraints
import viaduct.graphql.utils.GraphQLTypeRelation

class EngineSelectionSetImplExtensionsOSSTest : Assertions() {
    private val schema =
        mkSchema(
            """
            schema { query: Query }

            type Query {
              node(id: ID!): Node
            }

            interface Node { id: ID! }
            type Foo implements Node {
              id: ID!
              int: Int
              node: Node
            }

            type Bar implements Node { id: ID!, x: Int }
            """.trimIndent()
        )

    private fun assertEquals(
        a: Node<*>,
        b: Node<*>
    ) {
        assertEquals(
            AstPrinter.printAstCompact(a),
            AstPrinter.printAstCompact(b)
        )
    }

    private fun mk(
        type: String,
        selections: String,
        vars: Map<String, Any?> = emptyMap(),
        schema: GraphQLSchema = this.schema
    ): EngineSelectionSetImpl =
        EngineSelectionSetImpl.create(
            SelectionsParser.parse(type, selections),
            vars,
            ViaductSchema(schema),
        )

    @Test
    fun `toNodelikeSelectionSet -- document`() {
        val node =
            mk(
                "Foo",
                """
                fragment BarFrag on Bar { x }
                fragment Main on Foo {
                    node {
                        ... BarFrag
                        ... on Foo { int }
                    }
                }
                """.trimIndent()
            )
        val query =
            node
                .toNodelikeSelectionSet("node", emptyList())

        val node2 =
            query
                .selectionSetForField("Query", "node")
                .selectionSetForType("Foo")

        assertTrue(
            node2.selectionSetForField("Foo", "node")
                .selectionSetForType("Bar")
                .containsField("Bar", "x")
        )

        assertTrue(
            node2.selectionSetForField("Foo", "node")
                .selectionSetForType("Foo")
                .containsField("Foo", "int")
        )
    }

    @Test
    fun `toNodelikeSelectionSet -- does not throw on Node selectionsets`() {
        assertDoesNotThrow {
            mk("Foo", "__typename").toNodelikeSelectionSet("node", emptyList())
            mk("Node", "__typename").toNodelikeSelectionSet("node", emptyList())
        }
    }

    @Test
    fun `toNodelikeSelectionSet -- throws on non-node selectionsets`() {
        val ss = mk("Query", "__typename")
        assertThrows<IllegalArgumentException> {
            ss.toNodelikeSelectionSet("node", emptyList())
        }
    }

    @Test
    fun `toSelectionSet -- empty`() {
        val selections =
            mk("Node", "__typename @skip(if:true)")
                .toSelectionSet()
                .selections

        assertTrue(selections.isEmpty())
    }

    @Test
    fun `toSelectionSet -- field`() {
        val selections = mk("Node", "id").toSelectionSet()

        assertEquals(
            SelectionSet(
                listOf(
                    InlineFragment(
                        TypeName("Node"),
                        SelectionSet(listOf(Field("id")))
                    )
                )
            ),
            selections
        )
    }

    @Test
    fun `toSelectionSet -- inline fragment`() {
        val selections =
            mk("Node", "... on Foo { int }")
                .toSelectionSet()

        assertEquals(
            SelectionSet(
                listOf(
                    InlineFragment(
                        TypeName("Foo"),
                        SelectionSet(listOf(Field("int")))
                    )
                )
            ),
            selections
        )
    }

    @Test
    fun `toSelectionSet -- fragment spread`() {
        val selections =
            mk(
                "Node",
                """
                fragment Frag on Foo { int }
                fragment Main on Node { ...Frag }
                """.trimIndent()
            )
                .toSelectionSet()

        assertEquals(
            SelectionSet(
                listOf(
                    InlineFragment(
                        TypeName("Foo"),
                        SelectionSet(listOf(Field("int")))
                    )
                )
            ),
            selections
        )
    }

    @Test
    fun `toFragmentDefinition -- empty`() {
        val frag =
            mk("Node", "__typename @skip(if:true)")
                .toFragmentDefinition()

        assertEquals(
            FragmentDefinition.newFragmentDefinition()
                .name("Main")
                .typeCondition(TypeName("Node"))
                .selectionSet(SelectionSet(listOf()))
                .build(),
            frag
        )
    }

    @Test
    fun `toFragmentDefinition -- field`() {
        val raw = mk("Node", "id")
        val frag = raw.toFragmentDefinition()

        assertEquals(
            FragmentDefinition.newFragmentDefinition()
                .name("Main")
                .typeCondition(TypeName("Node"))
                .selectionSet(raw.toSelectionSet())
                .build(),
            frag
        )
    }

    @Test
    fun `toFragmentDefinition -- inline fragment`() {
        val raw = mk("Node", "... on Foo { int }")
        val frag = raw.toFragmentDefinition()

        assertEquals(
            FragmentDefinition.newFragmentDefinition()
                .name("Main")
                .typeCondition(TypeName("Node"))
                .selectionSet(raw.toSelectionSet())
                .build(),
            frag
        )
    }

    @Test
    fun `toFragmentDefinition -- fragment spread`() {
        val raw =
            mk(
                "Node",
                """
                fragment Frag on Foo { int }
                fragment Main on Node { ...Frag }
                """.trimIndent()
            )
        val frag = raw.toFragmentDefinition()

        assertEquals(
            FragmentDefinition.newFragmentDefinition()
                .name("Main")
                .typeCondition(TypeName("Node"))
                .selectionSet(raw.toSelectionSet())
                .build(),
            frag
        )
    }

    @Test
    fun `toDocument -- empty`() {
        assertTrue(
            mk("Node", "__typename @skip(if:true)")
                .toDocument()
                .definitions
                .isEmpty()
        )
    }

    @Test
    fun `toDocument -- field`() {
        assertEquals(
            Parser.parse("fragment Main on Node { ... on Node { id } }"),
            mk("Node", "id").toDocument()
        )
    }

    @Test
    fun `toDocument -- inline fragment`() {
        assertEquals(
            Parser.parse("fragment Main on Node { ... on Foo { int } }"),
            mk("Node", "... on Foo { int }").toDocument()
        )
    }

    @Test
    fun `toDocument -- fragment spread`() {
        assertEquals(
            Parser.parse("fragment Main on Node { ... on Foo { int } }"),
            mk(
                "Node",
                """
                    fragment Frag on Foo { int }
                    fragment Main on Node { ... Frag }
                """
            ).toDocument()
        )
    }

    private fun testPrintAsFieldSet(
        type: String,
        selections: String,
        expected: String,
        vars: Map<String, Any?> = emptyMap()
    ) {
        val result = mk(type, selections, vars)

        assertEquals(expected, result.printAsFieldSet())
        // round-trip test, printAsFieldSet can be parsed again
        assertEquals(
            result.toSelectionSet(),
            mk(type, result.printAsFieldSet()).toSelectionSet()
        )
    }

    @Test
    fun `printAsFieldSet -- fragment spread`() {
        testPrintAsFieldSet(
            "Node",
            """
            fragment Frag on Foo { int }
            fragment Main on Node { ...Frag }
            """.trimIndent(),
            "...on Foo{int}"
        )
    }

    @Test
    fun `printAsFieldSet -- aliased fields`() {
        testPrintAsFieldSet(
            "Foo",
            """
            anotherId: id
            """.trimIndent(),
            "...on Foo{anotherId:id}"
        )
    }

    @Test
    fun `printAsFieldSet -- field with args`() {
        testPrintAsFieldSet(
            "Query",
            """
            node(id: ${'$'}nodeId) { id }
            """.trimIndent(),
            "...on Query{node(id:${'$'}nodeId){id}}",
            mapOf(
                "nodeId" to "foo"
            )
        )
    }

    @Test
    fun `printAsFieldSet -- field with subselections`() {
        testPrintAsFieldSet(
            "Foo",
            """
            node { id }
            """.trimIndent(),
            "...on Foo{node{id}}"
        )
    }

    @Test
    fun hash() {
        // generate an array of selection sets, each with a different alias
        val sss = (0 until 500).map { x ->
            mk("Foo", "_$x: __typename")
        }
        val hashes = sss.map { it.hash() }.toSet()
        assertEquals(sss.size, hashes.size)
    }

    private fun mkFragment(
        type: String,
        vars: Map<String, Any?> = emptyMap()
    ): EngineSelectionSetImpl {
        val schema = mkSchema(
            """
            type $type {
                id: ID
            }
            type Query {
                node: $type
            }
            """.trimIndent()
        )

        val typeDef = schema.getObjectType(type)
        val context = EngineSelectionSetContext(
            variables = vars,
            fragmentDefinitions = emptyMap(),
            schema = ViaductSchema(schema),
            GraphQLContext.getDefault(),
            Locale.getDefault()
        )

        return EngineSelectionSetImpl(
            def = typeDef,
            selections = emptyList(),
            requestedTypes = emptySet(),
            constraints = Constraints.Unconstrained.narrowToImpls(typeDef, context.schema),
            ctx = context
        )
    }

    @Test
    fun `toFragment -- creates fragment with correct source and variables`() {
        val engineSelectionSet = mkFragment("Node", mapOf("var1" to "value1"))
        val fragment = engineSelectionSet.toFragment()
        val expectedFragment = Fragment(
            FragmentSource.create(engineSelectionSet.toDocument()),
            FragmentVariables.fromMap(mapOf("var1" to "value1"))
        )

        // Assert that the document is correct
        assertEquals(
            expectedFragment.document,
            fragment.document
        )

        // Assert that the variables are correct
        assertEquals(
            expectedFragment.variables.asMap(),
            fragment.variables.asMap()
        )
    }

    @Test
    fun `allCoords -- leaf fields on object type`() {
        assertEquals(
            setOf("Foo" to "id", "Foo" to "int"),
            mk("Foo", "id int").allCoords(ViaductSchema(schema))
        )
    }

    @Test
    fun `allCoords -- introspection fields are included`() {
        // __typename is an introspection meta-field; allCoords must include it
        // and must not throw when looking up its field definition.
        assertEquals(
            setOf("Foo" to "__typename"),
            mk("Foo", "__typename").allCoords(ViaductSchema(schema))
        )
    }

    @Test
    fun `allCoords -- interface type condition expands to all concrete types`() {
        assertEquals(
            setOf("Foo" to "id", "Bar" to "id"),
            mk("Node", "id").allCoords(ViaductSchema(schema))
        )
    }

    @Test
    fun `allCoords -- traversable field recurses into nested selections`() {
        assertEquals(
            setOf(
                "Foo" to "node",
                "Foo" to "id",
                "Bar" to "id",
            ),
            mk("Foo", "node { id }").allCoords(ViaductSchema(schema))
        )
    }

    @Test
    fun `allCoords -- empty selection returns empty set`() {
        // All selections skipped via directive → no coords
        assertEquals(
            emptySet<Pair<String, String>>(),
            mk("Foo", "__typename @skip(if: true)").allCoords(ViaductSchema(schema))
        )
    }

    @Test
    fun `allCoords -- abstract field recursion includes all concrete branches`() {
        val polymorphicSchema = mkSchema(
            """
            type Query { placeholder: Int }
            interface I { child:I }

            type A implements I { child:A, leaf:Int }
            type B implements I { child:B, leaf:Int }
            """.trimIndent()
        )

        assertEquals(
            setOf(
                "A" to "child",
                "B" to "child",
                "A" to "leaf",
                "B" to "leaf",
            ),
            mk(
                "I",
                """
                child {
                  ... on A { leaf }
                  ... on B { leaf }
                }
                """.trimIndent(),
                schema = polymorphicSchema,
            ).allCoords(ViaductSchema(polymorphicSchema))
        )
    }

    @Test
    fun `allCoords -- concrete parent scope ignores sibling object from interface fragment`() {
        val schema = mkSchema(
            """
            type Query { placeholder: Int }
            interface HasChild { child: HasChild }
            type Foo implements HasChild { child: HasChild }
            type Bar implements HasChild { child: HasChild }
            """.trimIndent()
        )

        assertEquals(
            setOf("Foo" to "child", "Foo" to "__typename", "Bar" to "__typename"),
            mk(
                "Foo",
                "... on HasChild { child { __typename } }",
                schema = schema,
            ).allCoords(ViaductSchema(schema))
        )
    }

    @Test
    fun `reachableObjects -- leaf fields return empty set`() {
        assertEquals(
            emptySet<String>(),
            mk("Foo", "id int").reachableObjects(ViaductSchema(schema))
        )
    }

    @Test
    fun `reachableObjects -- traversable field includes nested concrete types`() {
        assertEquals(
            setOf("Foo", "Bar"),
            mk("Foo", "node { id }").reachableObjects(ViaductSchema(schema))
        )
    }

    @Test
    fun `reachableObjects -- empty selection returns empty set`() {
        assertEquals(
            emptySet<String>(),
            mk("Foo", "__typename @skip(if: true)").reachableObjects(ViaductSchema(schema))
        )
    }

    @Test
    fun `reachableObjects -- abstract field recursion includes all concrete branches`() {
        val polymorphicSchema = mkSchema(
            """
            type Query { placeholder: Int }
            interface I { child:I }

            type A implements I { child:A, leaf:Int }
            type B implements I { child:B, leaf:Int }
            """.trimIndent()
        )

        assertEquals(
            setOf("A", "B"),
            mk(
                "I",
                """
                child {
                  ... on A { leaf }
                  ... on B { leaf }
                }
                """.trimIndent(),
                schema = polymorphicSchema,
            ).reachableObjects(ViaductSchema(polymorphicSchema))
        )
    }

    @Test
    fun `reachableObjects -- concrete parent scope ignores sibling object from interface fragment`() {
        val schema = mkSchema(
            """
            type Query { placeholder: Int }
            interface HasChild { child: HasChild }
            type Foo implements HasChild { child: HasChild }
            type Bar implements HasChild { child: HasChild }
            """.trimIndent()
        )

        assertEquals(
            setOf("Foo", "Bar"),
            mk(
                "Foo",
                "... on HasChild { child { __typename } }",
                schema = schema,
            ).reachableObjects(ViaductSchema(schema))
        )
    }

    // ─── coord ────────────────────────────────────────────────────────────────

    @Test
    fun `coord -- returns typeCondition to fieldName`() {
        val sel = EngineSelection(typeCondition = "Foo", fieldName = "id", selectionName = "id")
        assertEquals("Foo" to "id", sel.coord)
    }

    @Test
    fun `coord -- uses fieldName not selectionName for aliases`() {
        val sel = EngineSelection(typeCondition = "Foo", fieldName = "id", selectionName = "myAlias")
        assertEquals("Foo" to "id", sel.coord)
    }

    // ─── coordinates ──────────────────────────────────────────────────────────

    @Test
    fun `coordinates -- leaf fields`() {
        assertEquals(
            setOf("Foo" to "id", "Foo" to "int"),
            mk("Foo", "id int").coordinates
        )
    }

    @Test
    fun `coordinates -- aliased field uses fieldName`() {
        assertEquals(
            setOf("Foo" to "id"),
            mk("Foo", "myId: id").coordinates
        )
    }

    @Test
    fun `coordinates -- inline fragment preserves type condition`() {
        assertEquals(
            setOf("Foo" to "int"),
            mk("Node", "... on Foo { int }").coordinates
        )
    }

    @Test
    fun `coordinates -- empty selection set`() {
        assertEquals(
            emptySet<Pair<String, String>>(),
            mk("Foo", "__typename @skip(if: true)").coordinates
        )
    }

    // ─── relation ─────────────────────────────────────────────────────────────

    @Test
    fun `relation -- same type returns Same`() {
        val ess = mk("Foo", "id")
        val sel = EngineSelection(typeCondition = "Foo", fieldName = "id", selectionName = "id")
        assertEquals(GraphQLTypeRelation.Same, ess.relation(ViaductSchema(schema), sel))
    }

    @Test
    fun `relation -- interface condition on concrete ESS returns NarrowerThan`() {
        // ESS is Foo (concrete), selection typeCondition is Node (interface Foo implements).
        // relation(Foo, Node): Foo is narrower than Node → NarrowerThan.
        val ess = mk("Foo", "id")
        val sel = EngineSelection(typeCondition = "Node", fieldName = "id", selectionName = "id")
        assertEquals(GraphQLTypeRelation.NarrowerThan, ess.relation(ViaductSchema(schema), sel))
    }

    @Test
    fun `relation -- concrete condition on interface ESS returns WiderThan`() {
        // ESS is Node (interface), selection typeCondition is Foo (concrete type).
        // relation(Node, Foo): Node is wider than Foo → WiderThan.
        val ess = mk("Node", "id")
        val sel = EngineSelection(typeCondition = "Foo", fieldName = "id", selectionName = "id")
        assertEquals(GraphQLTypeRelation.WiderThan, ess.relation(ViaductSchema(schema), sel))
    }

    fun mkSchema(sdl: String): GraphQLSchema {
        val tdr = SchemaParser().parse(sdl)
        return SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
    }
}
