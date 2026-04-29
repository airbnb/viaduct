package viaduct.serve

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ViaductServerTestHelper : AutoCloseable {
    private val server = ViaductServer(port = 0, host = "127.0.0.1")
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val mapper = jacksonObjectMapper()

    val port: Int

    init {
        Thread {
            try {
                server.start()
            } catch (_: Exception) {
            }
        }.start()
        port = waitFor(30_000) { if (server.actualPort != 0) server.actualPort else null }
        waitFor(30_000) {
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/health"))
                    .GET().timeout(Duration.ofMillis(100)).build()
                val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) true else null
            } catch (_: Exception) {
                null
            }
        }
    }

    fun executeGraphQL(
        query: String,
        operationName: String? = null
    ): GraphQLResponse {
        val body = mutableMapOf<String, Any>("query" to query)
        if (operationName != null) body["operationName"] = operationName

        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/graphql"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val parsed = mapper.readValue<Map<String, Any?>>(response.body())

        @Suppress("UNCHECKED_CAST")
        return GraphQLResponse(
            statusCode = response.statusCode(),
            data = parsed["data"] as? Map<String, Any?>,
            errors = parsed["errors"] as? List<Map<String, Any?>> ?: emptyList(),
        )
    }

    fun httpGet(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port$path"))
            .GET().timeout(Duration.ofSeconds(5)).build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    override fun close() {
        try {
            server.stop()
        } catch (_: Exception) {
        }
    }

    data class GraphQLResponse(
        val statusCode: Int,
        val data: Map<String, Any?>?,
        val errors: List<Map<String, Any?>>,
    )

    private fun <T> waitFor(
        timeoutMs: Long,
        poll: () -> T?
    ): T {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            poll()?.let { return it }
            Thread.yield()
        }
        throw IllegalStateException("Timed out after ${timeoutMs}ms")
    }
}
