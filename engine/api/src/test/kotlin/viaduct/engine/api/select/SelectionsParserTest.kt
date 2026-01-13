package viaduct.engine.api.select

import graphql.execution.MergedField
import graphql.language.AstPrinter
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.TypeName
import graphql.parser.Parser
import graphql.schema.DataFetchingEnvironment
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductDataFetchingEnvironment
import viaduct.engine.api.fragment.Fragment

class SelectionsParserTest : Assertions() {
    private fun mkEnv(
        vararg mergeFields: Field,
        fragmentMap: Map<String, FragmentDefinition> = emptyMap()
    ): DataFetchingEnvironment {
        val fieldScope = mockk<EngineExecutionContext.FieldExecutionScope>()
        every { fieldScope.fragments } returns fragmentMap
        val engineExecutionContext: EngineExecutionContext = mockk()
        every { engineExecutionContext.fieldScope } returns fieldScope
        val env: ViaductDataFetchingEnvironment = mockk()
        val mergedField = MergedField.newMergedField(mergeFields.toList()).build()
        every { env.engineExecutionContext } returns engineExecutionContext
        every { env.field } returns mergeFields.first()
        every { env.mergedField } returns mergedField
        return env
    }

    @Test
    fun `throws on empty selections`() {
        assertThrows<IllegalArgumentException> {
            SelectionsParser.parse("Foo", "")
        }
    }

    @Test
    fun `throws on empty type name`() {
        assertThrows<IllegalArgumentException> {
            SelectionsParser.parse("", "id")
        }
    }

    @Test
    fun `parses fieldsets`() {
        val parsed =
            SelectionsParser.parse(
                "Foo",
                """
                id
                obj { field }
                ... on Fragment { x }
                """.trimIndent()
            )

        assertNodesEqual(
            GJSelectionSet(
                listOf(
                    Field("id"),
                    Field("obj", GJSelectionSet(listOf(Field("field")))),
                    InlineFragment(
                        TypeName("Fragment"),
                        GJSelectionSet(listOf(Field("x")))
                    )
                )
            ),
            parsed.selections
        )

        assertEquals(1, parsed.fragmentMap.size)
        assertTrue(parsed.fragmentMap.contains("Main"))
    }

    @Test
    fun `parses fieldsets with comments`() {
        val parsed = SelectionsParser.parse(
            "Foo",
            """
                # comment
                id
                # comment
            """.trimIndent()
        )
        assertNodesEqual(GJSelectionSet(listOf(Field("id"))), parsed.selections)
    }

    @Test
    fun `parses documents`() {
        val docString =
            """
            fragment Fragment on Bar { x }
            fragment Main on Foo {
                id
                obj { field }
                ... Fragment
            }
            """.trimIndent()

        val doc = Parser.parse(docString)
        val parsed = SelectionsParser.parse("Foo", docString)

        assertEquals("Foo", parsed.typeName)

        assertNodesEqual(
            doc.definitions
                .mapNotNull { it as? FragmentDefinition }
                .find { it.name == "Main" }!!
                .selectionSet,
            parsed.selections
        )

        assertEquals(setOf("Fragment", "Main"), parsed.fragmentMap.keys)
    }

    @Test
    fun `parses documents without a Main fragment but with an unambiguous fragment on the requested type`() {
        val docString = "fragment X on Foo { x }"
        val doc = Parser.parse(docString)
        val parsed = SelectionsParser.parse("Foo", docString)

        assertEquals("Foo", parsed.typeName)

        assertNodesEqual(
            doc.definitions
                .mapNotNull { it as? FragmentDefinition }
                .find { it.name == "X" }!!
                .selectionSet,
            parsed.selections
        )

        assertEquals(setOf("X"), parsed.fragmentMap.keys)
    }

    @Test
    fun `throws on documents with ambiguous entrypoint fragments`() {
        assertThrows<IllegalArgumentException> {
            SelectionsParser.parse(
                "Foo",
                """
                fragment X on Foo { x }
                fragment Y on Foo { x }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `throws on documents that are missing an entrypoint fragment on requested type`() {
        assertThrows<IllegalArgumentException> {
            SelectionsParser.parse("Foo", "fragment Main on Bar { x }")
        }
    }

    @Test
    fun `throws on documents with duplicate fragment definitions`() {
        assertThrows<IllegalArgumentException> {
            SelectionsParser.parse(
                "Foo",
                """
                fragment X on X { a }
                fragment X on X { b }
                fragment Main on Foo { c }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `throws on documents containing non-fragment definitions`() {
        fun test(docString: String) {
            assertThrows<IllegalArgumentException> {
                SelectionsParser.parse("Foo", docString)
            }
        }

        test(
            """
            fragment Query on Query { x }
            query Q { ... Query }
            """.trimIndent()
        )

        test(
            """
            fragment Query on Query { x }
            scalar MyScalar
            """.trimIndent()
        )
    }

    @Test
    fun `creates successfully from data fetching env -- single field`() {
        val env = mkEnv(
            Field(
                "field",
                SelectionSet(listOf(Field("x")))
            )
        )
        val parsed = SelectionsParser.fromDataFetchingEnvironment("Foo", env)
        assertEquals("Foo", parsed.typeName)
        assertEquals("{x}", AstPrinter.printAstCompact(parsed.selections))
    }

    @Test
    fun `creates successfully from data fetching env -- single field with subselections`() {
        val env = mkEnv(
            Field(
                "field",
                SelectionSet(
                    listOf(
                        Field("x", SelectionSet(listOf(Field("y"))))
                    )
                )
            )
        )
        val parsed = SelectionsParser.fromDataFetchingEnvironment("Foo", env)
        assertEquals("Foo", parsed.typeName)
        assertEquals("{x{y}}", AstPrinter.printAstCompact(parsed.selections))
    }

    @Test
    fun `creates successfully from data fetching env -- multiple fields in merge group`() {
        val env = mkEnv(
            Field("field", SelectionSet(listOf(Field("f1")))),
            Field("field", SelectionSet(listOf(Field("f2")))),
            Field("field", SelectionSet(listOf(Field("f1")))),
        )
        val parsed = SelectionsParser.fromDataFetchingEnvironment("Foo", env)
        assertEquals("Foo", parsed.typeName)
        assertEquals("{f1 f2 f1}", AstPrinter.printAstCompact(parsed.selections))
    }

    @Test
    fun `creates successfully from data fetching env -- multiple fields with subselections`() {
        val env = mkEnv(
            Field(
                "field",
                SelectionSet(
                    listOf(
                        Field("f1", SelectionSet(listOf(Field("sub1"))))
                    )
                )
            ),
            Field(
                "field",
                SelectionSet(
                    listOf(
                        Field("f2", SelectionSet(listOf(Field("sub2"))))
                    )
                )
            ),
            Field(
                "field",
                SelectionSet(
                    listOf(
                        Field("f1", SelectionSet(listOf(Field("sub3"))))
                    )
                )
            ),
        )
        val parsed = SelectionsParser.fromDataFetchingEnvironment("Foo", env)
        assertEquals("Foo", parsed.typeName)
        assertEquals(
            "{f1{sub1}f2{sub2}f1{sub3}}",
            AstPrinter.printAstCompact(parsed.selections)
        )
    }

    @Test
    fun `parses from fragment with single definition`() {
        val fragmentString = """
            fragment TestFragment on User {
                id
                name
                profile { bio }
            }
        """.trimIndent()

        val fragment = Fragment(fragmentString)
        val parsed = SelectionsParser.parse(fragment)

        assertEquals("User", parsed.typeName)
        assertEquals(
            "{id name profile{bio __typename}__typename}",
            AstPrinter.printAstCompact(parsed.selections)
        )
        assertEquals(setOf("TestFragment"), parsed.fragmentMap.keys)
    }

    @Test
    fun `parses from fragment and extracts correct type name and selections`() {
        val fragmentString = """
            fragment PostFragment on Post {
                title
                content
                author { name email }
            }
        """.trimIndent()

        val fragment = Fragment(fragmentString)
        val parsed = SelectionsParser.parse(fragment)

        assertEquals("Post", parsed.typeName)
        assertEquals(1, parsed.fragmentMap.size)
        assertEquals(setOf("PostFragment"), parsed.fragmentMap.keys)

        // Verify that the parser correctly uses fragment definition's type condition
        val fragmentDef = parsed.fragmentMap["PostFragment"]!!
        assertEquals("Post", fragmentDef.typeCondition.name)
    }
}
