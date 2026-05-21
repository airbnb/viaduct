package viaduct.service.wiring.graphiql

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

internal class GraphiQLHtmlTest {
    @Test
    fun `graphiQLHtml returns HTML containing graphiql`() {
        val html = graphiQLHtml()
        assertTrue(html.isNotBlank())
        assertTrue(html.contains("graphiql", ignoreCase = true))
    }

    @Test
    fun `graphiQLHtml returns generic defaults when no config is provided`() {
        val html = graphiQLHtml()

        assertTrue(html.contains("<title>GraphiQL - Viaduct</title>"))
        assertTrue(html.contains("Welcome to Viaduct GraphiQL"))
        assertTrue(html.contains("storageKey: window.location.origin"))
    }

    @Test
    fun `graphiQLHtml applies caller provided title query and storage key`() {
        val html = graphiQLHtml(
            GraphiQLHtmlConfig(
                title = "GraphiQL - ktor-starter",
                defaultQuery = """
                    query HelloWorld {
                      greeting
                      author
                    }
                """.trimIndent(),
                storageKey = "ktor-starter",
            )
        )

        assertTrue(html.contains("<title>GraphiQL - ktor-starter</title>"))
        assertTrue(html.contains("query HelloWorld"))
        assertTrue(html.contains("greeting"))
        assertTrue(html.contains("author"))
        assertTrue(html.contains("storageKey: \"ktor-starter\""))
        assertFalse(html.contains("storageKey: window.location.origin"))
    }

    @Test
    fun `graphiQLHtml escapes caller provided values`() {
        val html = graphiQLHtml(
            GraphiQLHtmlConfig(
                title = """GraphiQL <"quoted"> & test""",
                defaultQuery = "query Escaped { field }\n</script>",
                storageKey = """key"with\escapes""",
            )
        )

        assertTrue(html.contains("<title>GraphiQL &lt;&quot;quoted&quot;&gt; &amp; test</title>"))
        assertTrue(html.contains("defaultQuery: \"query Escaped { field }\\n"))
        assertTrue(html.contains("\\" + "u003C/script\\" + "u003E"))
        assertTrue(html.contains("storageKey: \"key\\\"with\\\\escapes\""))
    }
}
