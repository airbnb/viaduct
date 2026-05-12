package viaduct.service.wiring.graphiql

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

internal class GraphiQLHtmlTest {
    @Test
    fun `graphiQLHtml returns non-empty HTML content`() {
        val html = graphiQLHtml()
        assertTrue(html.isNotBlank())
        assertTrue(html.contains("graphiql", ignoreCase = true))
    }
}
