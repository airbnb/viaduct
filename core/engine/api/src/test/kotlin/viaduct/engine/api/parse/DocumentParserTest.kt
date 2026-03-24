package viaduct.engine.api.parse

import graphql.language.Document
import graphql.language.OperationDefinition
import graphql.parser.Parser
import graphql.parser.ParserOptions
import java.io.StringReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// This test suite runs in both bazel and gradle builds.
// When run by bazel, the DocumentParser being tested is implemented in BazelDocumentParser.kt
// When in gradle, the implementation is in GradleDocumentParser.kt
class DocumentParserTest {
    // a large document with a shape like:
    // {
    //   t1: __typename
    //   t2: __typename
    //   ...
    //   t1000: __typename
    // }
    private val NUM_SELECTIONS: Int = 10000

    val largeDoc: String by lazy {
        val longLabel = "x".repeat(1000)
        val b = StringBuilder()
        b.append("{")
        (1..NUM_SELECTIONS).forEach { i ->
            b.append(longLabel)
                .append(i)
                .append(": __typename\n")
        }
        b.append("}")
        b.toString()
    }

    private fun assertLargeDocParsed(doc: Document) {
        val selections =
            doc.getDefinitionsOfType(OperationDefinition::class.java)
                .first()
                .selectionSet
                .selections

        assert(selections.size == NUM_SELECTIONS)
    }

    @Test
    fun `sentinel -- default parser throws on large documents`() {
        // DocumentParser's primary use case is in parsing documents that are too
        // large for the default GJ Parser. If the defaults change, DocumentParser
        // can be considered for removal
        ParserOptions.setDefaultParserOptions(
            ParserOptions.getDefaultParserOptions()
                .transform { b ->
                    b.maxTokens(ParserOptions.MAX_QUERY_TOKENS)
                }
        )

        // This test runs in multiple scenarios (!?) with different configurations:
        //   1. Gradle, Java-8, using graphql-java v18.1
        //   2. Bazel, Java-15, using graphql-java v21.3
        // In the gradle scenario, the exception thrown is a graphql.parser.ParseCancelledException
        // In the bazel scenario, the exception thrown is a graphql.parser.exceptions.ParseCancelledTooManyCharsException
        val err =
            assertThrows<Exception> {
                Parser.parse(largeDoc)
            }
        assert(err.javaClass.name.startsWith("graphql.parser"))
    }

    @Test
    fun `parse string -- parses large documents`() {
        assertLargeDocParsed(
            DocumentParser.parse(largeDoc)
        )
    }

    @Test
    fun `parse reader -- parses large documents`() {
        assertLargeDocParsed(
            DocumentParser.parse(StringReader(largeDoc))
        )
    }

    @Test
    fun `defaultParserOptions are same as GJ default parser options, modulo max tokens`() {
        val vopts = DocumentParser.defaultParserOptions
        val gjopts = ParserOptions.getDefaultParserOptions()

        assertEquals(gjopts.isCaptureIgnoredChars, vopts.isCaptureIgnoredChars)
        assertEquals(gjopts.isCaptureSourceLocation, vopts.isCaptureSourceLocation)
        assertEquals(gjopts.isCaptureLineComments, vopts.isCaptureLineComments)
    }
}
