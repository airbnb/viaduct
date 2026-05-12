package com.example.viadapp

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import viaduct.service.wiring.graphiql.GraphiQLHtmlConfig
import viaduct.service.wiring.graphiql.graphiQLHtml

private val jettyStarterGraphiQLConfig = GraphiQLHtmlConfig(
    title = "GraphiQL - Viaduct Jetty Starter",
    defaultQuery = """
        query HelloWorld {
          greeting
          author
        }

        # Try querying the throwException field to see error handling:
        # query TestError {
        #   throwException
        # }
    """.trimIndent(),
    storageKey = "jetty-starter",
)

/**
 * Simple servlet that serves the GraphiQL interface.
 * GraphiQL provides an interactive web UI for testing GraphQL queries.
 */
class GraphiQLServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse
    ) {
        resp.contentType = "text/html; charset=UTF-8"
        resp.writer.write(graphiQLHtml(jettyStarterGraphiQLConfig))
    }
}

class GraphiQLStaticResourceServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse
    ) {
        val filename = req.pathInfo?.removePrefix("/")
        if (filename.isNullOrBlank() || filename.contains('/') || filename.contains('\\')) {
            resp.status = HttpServletResponse.SC_NOT_FOUND
            return
        }

        val content = this::class.java.classLoader
            .getResourceAsStream("graphiql/js/$filename")
            ?.bufferedReader()
            ?.use { it.readText() }

        if (content == null) {
            resp.status = HttpServletResponse.SC_NOT_FOUND
            return
        }

        resp.contentType = when {
            filename.endsWith(".js") || filename.endsWith(".jsx") -> "application/javascript; charset=UTF-8"
            else -> "application/octet-stream"
        }
        resp.writer.write(content)
    }
}
