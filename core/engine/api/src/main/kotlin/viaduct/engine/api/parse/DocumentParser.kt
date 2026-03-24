@file:Suppress("ktlint:standard:filename")

package viaduct.engine.api.parse

import graphql.language.Document
import graphql.parser.Parser
import graphql.parser.ParserEnvironment
import graphql.parser.ParserOptions
import java.io.Reader

object DocumentParser {
    val defaultParserOptions =
        ParserOptions.getDefaultParserOptions()
            .transform { b ->
                b.maxTokens(Int.MAX_VALUE)
                b.maxWhitespaceTokens(Int.MAX_VALUE)
                b.maxCharacters(Int.MAX_VALUE)
            }

    private val inst = Parser()

    /**
     * Parse the provided graphql text into a [graphql.language.Document]
     */
    fun parse(input: String): Document =
        inst.parseDocument(
            ParserEnvironment.newParserEnvironment()
                .parserOptions(defaultParserOptions)
                .document(input)
                .build()
        )

    fun parse(reader: Reader): Document =
        inst.parseDocument(
            ParserEnvironment.newParserEnvironment()
                .parserOptions(defaultParserOptions)
                .document(reader)
                .build()
        )
}
