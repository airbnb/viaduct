package viaduct.graphql.utils

import graphql.language.AstPrinter
import graphql.language.FragmentDefinition
import graphql.language.SelectionSet
import graphql.language.TypeName
import graphql.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SelectionsParserUtilsTest {
    private val parse: (String) -> graphql.language.Document = { Parser().parseDocument(it) }

    private fun normalized(
        selections: String,
        typeName: String
    ): String = AstPrinter.printAstCompact(SelectionsParserUtils.normalizeToFragmentDocument(selections, typeName, parse))

    @Test
    fun `normalizeToFragmentDocument wraps shorthand`() {
        assertEquals(
            "fragment Main on User {id name}",
            normalized("id name", "User"),
        )
    }

    @Test
    fun `normalizeToFragmentDocument passes fragment documents through`() {
        assertEquals(
            "fragment Main on User {id name}",
            normalized("fragment Main on User { id name }", "User"),
        )
    }

    @Test
    fun `normalizeToFragmentDocument reduces an anonymous operation`() {
        assertEquals(
            "fragment Main on Query {user{id}}",
            normalized("{ user { id } }", "Query"),
        )
    }

    @Test
    fun `normalizeToFragmentDocument drops variable definitions and keeps local fragments`() {
        assertEquals(
            "fragment Main on Query {user(id:\$id){...F}} fragment F on User {id}",
            normalized("query Q(\$id: ID!) { user(id: \$id) { ...F } } fragment F on User { id }", "Query"),
        )
    }

    @Test
    fun `normalizeToFragmentDocument rejects multiple operations`() {
        val ex = assertThrows<IllegalArgumentException> {
            normalized("query A { x } query B { y }", "Query")
        }
        assertTrue(ex.message!!.contains("exactly one operation"))
    }

    private fun knownFragments(vararg defs: String): Map<String, FragmentDefinition> = defs.flatMap { parse(it).getDefinitionsOfType(FragmentDefinition::class.java) }.associateBy { it.name }

    private fun inlined(
        document: String,
        known: Map<String, FragmentDefinition>
    ): String = AstPrinter.printAstCompact(SelectionsParserUtils.inlineReachableFragments(parse(document), known))

    @Test
    fun `inlineReachableFragments appends a reachable named fragment`() {
        assertEquals(
            "fragment Main on Query {user{...UserFields}} fragment UserFields on User {id name}",
            inlined(
                "fragment Main on Query { user { ...UserFields } }",
                knownFragments("fragment UserFields on User { id name }"),
            ),
        )
    }

    @Test
    fun `inlineReachableFragments resolves transitive spreads`() {
        assertEquals(
            "fragment Main on Query {user{...A}} fragment A on User {...B} fragment B on User {id}",
            inlined(
                "fragment Main on Query { user { ...A } }",
                knownFragments("fragment A on User { ...B }", "fragment B on User { id }"),
            ),
        )
    }

    @Test
    fun `inlineReachableFragments leaves a self-contained document unchanged`() {
        assertEquals(
            "fragment Main on Query {user{...Local}} fragment Local on User {id}",
            inlined(
                "fragment Main on Query { user { ...Local } } fragment Local on User { id }",
                knownFragments("fragment Unused on User { name }"),
            ),
        )
    }

    @Test
    fun `inlineReachableFragments lets a local fragment shadow a same-named known fragment`() {
        // The local `F` is kept; the same-named known fragment is not appended.
        assertEquals(
            "fragment Main on Query {user{...F}} fragment F on User {id}",
            inlined(
                "fragment Main on Query { user { ...F } } fragment F on User { id }",
                knownFragments("fragment F on User { name }"),
            ),
        )
    }

    @Test
    fun `isShorthandForm returns true for field sets`() {
        assertTrue(SelectionsParserUtils.isShorthandForm("id\nname\nemail"))
        assertTrue(SelectionsParserUtils.isShorthandForm("obj { field }"))
        assertTrue(SelectionsParserUtils.isShorthandForm("... on Fragment { x }"))
    }

    @Test
    fun `isShorthandForm returns false for fragment definitions`() {
        assertFalse(SelectionsParserUtils.isShorthandForm("fragment _ on User { id }"))
        assertFalse(
            SelectionsParserUtils.isShorthandForm(
                """
            # comment
            fragment _ on User { id }
            # comment
                """.trimIndent()
            )
        )
        assertFalse(SelectionsParserUtils.isShorthandForm("\n fragment Main on User { id }"))
    }

    @Test
    fun `classify detects shorthand field sets`() {
        assertEquals(SelectionsParserUtils.SelectionsForm.SHORTHAND, SelectionsParserUtils.classify("id name"))
        assertEquals(SelectionsParserUtils.SelectionsForm.SHORTHAND, SelectionsParserUtils.classify("obj { field }"))
        assertEquals(SelectionsParserUtils.SelectionsForm.SHORTHAND, SelectionsParserUtils.classify("... on Fragment { x }"))
    }

    @Test
    fun `classify detects fragment documents`() {
        assertEquals(SelectionsParserUtils.SelectionsForm.FRAGMENTS, SelectionsParserUtils.classify("fragment Main on User { id }"))
        assertEquals(
            SelectionsParserUtils.SelectionsForm.FRAGMENTS,
            SelectionsParserUtils.classify("# comment\nfragment Main on User { id }"),
        )
    }

    @Test
    fun `classify detects operation documents`() {
        assertEquals(SelectionsParserUtils.SelectionsForm.OPERATION, SelectionsParserUtils.classify("{ user { id } }"))
        assertEquals(SelectionsParserUtils.SelectionsForm.OPERATION, SelectionsParserUtils.classify("query Q(\$id: ID!) { user(id: \$id) { id } }"))
        assertEquals(SelectionsParserUtils.SelectionsForm.OPERATION, SelectionsParserUtils.classify("mutation { send { ok } }"))
        // an operation document that also defines a local fragment is still an OPERATION
        assertEquals(
            SelectionsParserUtils.SelectionsForm.OPERATION,
            SelectionsParserUtils.classify("# lead\nquery { ...F }\nfragment F on Query { x }"),
        )
    }

    @Test
    fun `wrapShorthandAsFragment creates valid fragment definition`() {
        val result = SelectionsParserUtils.wrapShorthandAsFragment("id name", "User")
        assertEquals(
            """
            fragment Main on User {
                id name
            }
            """.trimIndent(),
            result
        )
    }

    @Test
    fun `findEntryPointFragment - single fragment`() {
        val fragment = FragmentDefinition.newFragmentDefinition()
            .name("MyFragment")
            .typeCondition(TypeName("User"))
            .selectionSet(SelectionSet.newSelectionSet().build())
            .build()

        val result = SelectionsParserUtils.findEntryPointFragment(listOf(fragment))
        assertEquals("MyFragment", result.name)
    }

    @Test
    fun `findEntryPointFragment - multiple fragments`() {
        val fragment1 = FragmentDefinition.newFragmentDefinition()
            .name("A")
            .typeCondition(TypeName("User"))
            .selectionSet(SelectionSet.newSelectionSet().build())
            .build()
        val mainFragment = FragmentDefinition.newFragmentDefinition()
            .name("Main")
            .typeCondition(TypeName("User"))
            .selectionSet(SelectionSet.newSelectionSet().build())
            .build()
        val fragment2 = FragmentDefinition.newFragmentDefinition()
            .name("B")
            .typeCondition(TypeName("User"))
            .selectionSet(SelectionSet.newSelectionSet().build())
            .build()

        val result = SelectionsParserUtils.findEntryPointFragment(listOf(fragment1, mainFragment, fragment2))
        assertEquals("Main", result.name)
    }

    @Test
    fun `findEntryPointFragment throws when multiple fragments exist but no Main`() {
        val fragment1 = FragmentDefinition.newFragmentDefinition()
            .name("A")
            .typeCondition(TypeName("User"))
            .selectionSet(SelectionSet.newSelectionSet().build())
            .build()
        val fragment2 = FragmentDefinition.newFragmentDefinition()
            .name("B")
            .typeCondition(TypeName("User"))
            .selectionSet(SelectionSet.newSelectionSet().build())
            .build()

        val exception = assertThrows<IllegalArgumentException> {
            SelectionsParserUtils.findEntryPointFragment(listOf(fragment1, fragment2))
        }
        assertTrue(exception.message!!.contains("Main"))
    }
}
