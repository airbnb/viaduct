package viaduct.service.wiring.graphiql

import viaduct.apiannotations.StableApi

private const val DEFAULT_GRAPHIQL_TITLE = "GraphiQL - Viaduct"
private const val DEFAULT_GRAPHIQL_QUERY = """# Welcome to Viaduct GraphiQL!
#
# Start typing your GraphQL query here.
# Press Ctrl+Space for autocomplete.
# Click the Docs button to explore the schema.
# Use the Global ID Utils plugin (key icon) to encode/decode Viaduct Global IDs.

query {
  # Your query here
}
"""

private val graphiQLConfigRegex = Regex(
    """const graphiQLConfig = /\* VIADUCT_GRAPHIQL_CONFIG_START \*/[\s\S]*?/\* VIADUCT_GRAPHIQL_CONFIG_END \*/;"""
)

/** Configuration for the HTML returned by [graphiQLHtml]. */
@StableApi
class GraphiQLHtmlConfig(
    val title: String = DEFAULT_GRAPHIQL_TITLE,
    val defaultQuery: String = DEFAULT_GRAPHIQL_QUERY,
    val storageKey: String? = null,
)

/**
 * Returns the HTML for the GraphiQL IDE.
 *
 * @return The GraphiQL HTML content
 * @throws IllegalStateException if the GraphiQL HTML cannot be found in resources
 */
@StableApi
fun graphiQLHtml(): String = graphiQLHtml(GraphiQLHtmlConfig())

@StableApi
fun graphiQLHtml(config: GraphiQLHtmlConfig): String {
    val resourcePath = "/graphiql/index.html"

    val html = object {}.javaClass.getResourceAsStream(resourcePath)?.use { stream ->
        stream.bufferedReader().readText()
    } ?: throw IllegalStateException(
        "GraphiQL HTML not found at $resourcePath. " +
            "Ensure the downloadGraphiQL Gradle task has been run during build."
    )

    val configuredHtml = graphiQLConfigRegex.replace(html) { renderConfig(config) }
    if (configuredHtml == html) {
        throw IllegalStateException("GraphiQL HTML config marker not found in $resourcePath")
    }

    return configuredHtml.replace(
        Regex("""<title>.*?</title>"""),
        "<title>${config.title.toHtmlEscaped()}</title>"
    )
}

private fun renderConfig(config: GraphiQLHtmlConfig): String =
    """
      const graphiQLConfig = /* VIADUCT_GRAPHIQL_CONFIG_START */ {
        title: ${config.title.toJavaScriptStringLiteral()},
        defaultQuery: ${config.defaultQuery.toJavaScriptStringLiteral()},
        storageKey: ${config.storageKey?.toJavaScriptStringLiteral() ?: "window.location.origin"},
      } /* VIADUCT_GRAPHIQL_CONFIG_END */;
    """.trimIndent()

private fun String.toHtmlEscaped(): String =
    buildString {
        for (character in this@toHtmlEscaped) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

private fun String.toJavaScriptStringLiteral(): String =
    buildString {
        append('"')
        for (character in this@toJavaScriptStringLiteral) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003C")
                '>' -> append("\\u003E")
                '&' -> append("\\u0026")
                else -> {
                    if (character.isISOControl()) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }
