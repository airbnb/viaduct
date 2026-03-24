package viaduct.engine.runtime.rewriter

import graphql.language.Field
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.DocumentRewriterHelper

class DocumentRewriteHelperTest {
    @Test
    fun `test rewriteFieldName without alias`() {
        val field = mockk<Field>()
        every { field.name } returns "testField"
        every { field.alias } returns null

        val result = DocumentRewriterHelper.rewriteFieldName("prefix", field)
        assertEquals("prefix_testField", result)
    }

    @Test
    fun `test rewriteFieldName with alias`() {
        val field = mockk<Field>()
        every { field.name } returns "testField"
        every { field.alias } returns "aliasField"

        val result = DocumentRewriterHelper.rewriteFieldName("prefix", field)
        assertEquals("prefix_testField_zzz_aliasField", result)
    }

    @Test
    fun `test nameWithAlias without alias`() {
        val field = mockk<Field>()
        every { field.name } returns "testField"
        every { field.alias } returns null

        val result = field.nameWithAlias()
        assertEquals("testField", result)
    }

    @Test
    fun `test nameWithAlias with alias`() {
        val field = mockk<Field>()
        every { field.name } returns "testField"
        every { field.alias } returns "aliasField"

        val result = field.nameWithAlias()
        assertEquals("testField_zzz_aliasField", result)
    }

    private fun Field.nameWithAlias(): String {
        return if (alias != null && alias != "") {
            "$name${DocumentRewriterHelper.ALPHA_RENAME_SEPARATOR}$alias"
        } else {
            name
        }
    }
}
