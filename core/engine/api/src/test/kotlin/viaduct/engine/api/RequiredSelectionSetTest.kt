package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.select.SelectionsParser

class RequiredSelectionSetTest {
    @Test
    fun `does not throw when created with no variables`() {
        assertDoesNotThrow {
            RequiredSelectionSet(
                SelectionsParser.parse("Query", "x"),
                emptyList(),
                forChecker = false
            )
        }
    }

    @Test
    fun `does not throw when all variables are bound`() {
        assertDoesNotThrow {
            RequiredSelectionSet(
                SelectionsParser.parse("Query", "x(y:\$var)"),
                listOf(
                    VariablesResolver.const(mapOf("var" to 1))
                ),
                forChecker = false
            )
        }
    }

    @Test
    fun `throws when created with unbound variables`() {
        assertThrows<UnboundVariablesException> {
            RequiredSelectionSet(
                SelectionsParser.parse("Query", "x(y:\$var)"),
                emptyList(),
                forChecker = false
            )
        }
    }

    @Test
    fun `distinct instances receive distinct ids`() {
        val rss1 = RequiredSelectionSet(
            SelectionsParser.parse("Query", "x"),
            emptyList(),
            forChecker = false
        )
        val rss2 = RequiredSelectionSet(
            SelectionsParser.parse("Query", "x"),
            emptyList(),
            forChecker = false
        )

        assertNotSame(rss1.id, rss2.id)
    }
}
