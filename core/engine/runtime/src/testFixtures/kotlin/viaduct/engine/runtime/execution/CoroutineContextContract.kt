@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.preparsed.PreparsedDocumentProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct

/**
 * Execution engines must preserve caller-provided coroutine context elements in tenant work.
 * A thread-local context element models framework request scope without framework dependencies.
 * Implementations supply service construction; all scenarios and assertions are shared.
 */
abstract class CoroutineContextContract {
    protected abstract fun createViaduct(
        schemaSDL: String,
        resolveField: suspend () -> String,
        documentProvider: PreparsedDocumentProvider,
    ): Viaduct

    @Test
    fun `queries preserve caller request scope in resolvers across suspension`() {
        assertRequestContextPropagation("query")
    }

    @Test
    fun `mutations preserve caller request scope in resolvers across suspension`() {
        assertRequestContextPropagation("mutation")
    }

    private fun assertRequestContextPropagation(operation: String) {
        runBlocking {
            val ambientRequest = ThreadLocal<String>()
            val requestScopedDependency = RequestScopedDependency(ambientRequest)
            val requestDuringParsing = AtomicReference<String?>()
            val resolverCalls = AtomicInteger()
            val resolveField: suspend () -> String = {
                resolverCalls.incrementAndGet()
                val before = requestScopedDependency.currentRequest()
                val suspended =
                    withContext(Dispatchers.IO) {
                        yield()
                        requestScopedDependency.currentRequest()
                    }
                val after = requestScopedDependency.currentRequest()
                "$before/$suspended/$after"
            }
            val documentProvider =
                PreparsedDocumentProvider { input, parseAndValidate ->
                    requestDuringParsing.set(ambientRequest.get())
                    CompletableFuture.completedFuture(parseAndValidate.apply(input))
                }
            val viaduct = createViaduct(SCHEMA, resolveField, documentProvider)

            // Reuse the same service without carrying the ambient state in ExecutionInput.
            // The coroutine context alone must propagate it, just as it does for HTTP frameworks.
            for ((index, requestId) in listOf("request-a", "request-b").withIndex()) {
                withTimeout(5_000) {
                    withContext(ambientRequest.asContextElement(requestId)) {
                        assertEquals(requestId, ambientRequest.get())
                        val result =
                            viaduct.execute(
                                ExecutionInput.create(operationText = "$operation { requestIdentity }"),
                                SchemaId.Base,
                            )

                        assertEquals(requestId, requestDuringParsing.get(), "Caller context must reach GraphQL parsing")
                        assertEquals(index + 1, resolverCalls.get(), "The production dispatcher must invoke the resolver")
                        assertEquals(emptyList<GraphQLError>(), result.errors, "Request scope must survive the engine boundary")
                        assertEquals(mapOf("requestIdentity" to "$requestId/$requestId/$requestId"), result.getData())
                        assertEquals(requestId, ambientRequest.get(), "Resolver execution must not corrupt the caller's context")
                    }
                }
                assertNull(ambientRequest.get(), "Request context must be restored after each invocation")
            }
        }
    }

    private class RequestScopedDependency(
        private val ambientRequest: ThreadLocal<String>,
    ) {
        fun currentRequest(): String = checkNotNull(ambientRequest.get()) { "No request present" }
    }

    private companion object {
        val SCHEMA =
            """
            extend type Query {
                requestIdentity: String @resolver
            }
            extend type Mutation {
                requestIdentity: String @resolver
            }
            """.trimIndent()
    }
}
