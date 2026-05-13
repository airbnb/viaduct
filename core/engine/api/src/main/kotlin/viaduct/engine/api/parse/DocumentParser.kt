@file:Suppress("ktlint:standard:filename")

package viaduct.engine.api.parse

import graphql.language.Document
import graphql.parser.Parser
import graphql.parser.ParserEnvironment
import graphql.parser.ParserOptions
import java.io.Reader

/**
 * Stateless singleton that parses GraphQL document strings into [Document] ASTs.
 *
 * Configured with relaxed parser limits (effectively unlimited tokens, whitespace, and characters)
 * so that large auto-generated operations do not trigger the default GraphQL-Java size guards.
 * Prefer [CachedDocumentParser] for strings that are expected to be parsed repeatedly; use this
 * object directly only when caching is not desired or when working with a [java.io.Reader].
 */
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
