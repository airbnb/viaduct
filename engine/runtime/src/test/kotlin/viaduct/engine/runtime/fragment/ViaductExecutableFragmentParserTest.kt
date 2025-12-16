package viaduct.engine.runtime.fragment

import graphql.language.AstPrinter
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.parse.CachedDocumentParser

class ViaductExecutableFragmentParserTest {
    @Test
    fun `test parse method`() {
        val fragment = mockk<Fragment>()
        val expectedDocument = """
            fragment TestFragment on TestType {
                field1
                field2
            }
        """.trimIndent()

        every { fragment.document } returns expectedDocument

        val parser = ViaductExecutableFragmentParser()
        val result = parser.parse(fragment)

        // Convert both documents to their string representations for comparison
        val resultString = AstPrinter.printAst(result)
        val expectedDocumentParsed = CachedDocumentParser.parseDocument(expectedDocument)
        val expectedString = AstPrinter.printAst(expectedDocumentParsed)

        assertEquals(expectedString, resultString)
    }
}
