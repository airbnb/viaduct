package com.example.viadapp

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

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

        // Read the GraphiQL HTML from resources
        val htmlContent = this::class.java.classLoader
            .getResourceAsStream("graphiql/index.html")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalStateException("GraphiQL index.html not found in resources")

        resp.writer.write(htmlContent)
    }
}
