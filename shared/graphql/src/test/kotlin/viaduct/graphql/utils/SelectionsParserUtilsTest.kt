package viaduct.graphql.utils

import graphql.language.FragmentDefinition
import graphql.language.SelectionSet
import graphql.language.TypeName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SelectionsParserUtilsTest {
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
