@file:Suppress("ForbiddenImport")

package viaduct.engine

import graphql.ExecutionInput
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.parser.Parser
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class IntrospectionRestrictingPreparsedDocumentProviderTest {
    private val parser = Parser()

    private val noOpProvider = object : PreparsedDocumentProvider {
        override fun getDocumentAsync(
            executionInput: ExecutionInput,
            parseAndValidateFunction: java.util.function.Function<ExecutionInput, PreparsedDocumentEntry>
        ): CompletableFuture<PreparsedDocumentEntry?> {
            return CompletableFuture.completedFuture(parseAndValidateFunction.apply(executionInput))
        }
    }

    private val subject = IntrospectionRestrictingPreparsedDocumentProvider(noOpProvider)

    private fun parseQuery(
        query: String,
        operationName: String? = null
    ): PreparsedDocumentEntry {
        val executionInput = ExecutionInput.newExecutionInput()
            .query(query)
            .apply { if (operationName != null) operationName(operationName) }
            .build()

        return runBlocking {
            subject.getDocumentAsync(executionInput) { input ->
                val document = parser.parseDocument(input.query)
                PreparsedDocumentEntry(document, emptyList())
            }.await()!!
        }
    }

    @Test
    fun `pure introspection query is allowed`() {
        val query = """
            query IntrospectionQuery {
              __schema {
                queryType {
                  name
                }
                mutationType {
                  name
                }
                types {
                  name
                  kind
                }
              }
            }
        """.trimIndent()

        val result = parseQuery(query)

        assertTrue(result.errors.isEmpty(), "Pure introspection queries should be allowed")
    }

    @Test
    fun `mixed introspection and data query is rejected`() {
        val query = """
            query MixedQuery {
              helloWorld
              __schema {
                queryType {
                  name
                }
              }
            }
        """.trimIndent()

        val result = parseQuery(query)

        assertFalse(result.errors.isEmpty(), "Mixed queries should be rejected")
        assertEquals(
            "Introspective queries cannot select non-introspective fields.",
            result.errors.first().message
        )
    }

    @Test
    fun `mixed introspection in fragment and query is rejected`() {
        val query = """
            query MixedQuery {
              helloWorld
              __schema {
                ...SchemaInfo
              }
            }

            fragment SchemaInfo on __Schema {
              queryType {
                name
              }
            }
        """.trimIndent()

        val result = parseQuery(query)

        assertFalse(result.errors.isEmpty(), "Mixed queries with fragments should be rejected")
        assertEquals(
            "Introspective queries cannot select non-introspective fields.",
            result.errors.first().message
        )
    }

    @Test
    fun `introspection in mutation is rejected`() {
        val query = """
            mutation IntrospectionMutation {
              __schema {
                queryType {
                  name
                }
              }
            }
        """.trimIndent()

        val result = parseQuery(query)

        assertFalse(result.errors.isEmpty(), "Introspection in mutations should be rejected")
        assertEquals(
            "MUTATION operations cannot introspect the schema.",
            result.errors.first().message
        )
    }

    @Test
    fun `introspection in subscription is rejected`() {
        val query = """
            subscription IntrospectionSubscription {
              __schema {
                queryType {
                  name
                }
              }
            }
        """.trimIndent()

        val result = parseQuery(query)

        assertFalse(result.errors.isEmpty(), "Introspection in subscriptions should be rejected")
        assertEquals(
            "SUBSCRIPTION operations cannot introspect the schema.",
            result.errors.first().message
        )
    }

    @Test
    fun `non-introspection query passes through unchanged`() {
        val query = """
            query NormalQuery {
              helloWorld
              user {
                id
                name
              }
            }
        """.trimIndent()

        val result = parseQuery(query)

        assertTrue(result.errors.isEmpty(), "Non-introspection queries should pass through")
    }

    @Test
    fun `introspection with __type is detected as introspection`() {
        val query = """
            query TypeIntrospection {
              __type(name: "User") {
                name
                fields {
                  name
                }
              }
            }
        """.trimIndent()

        val result = parseQuery(query)

        assertTrue(result.errors.isEmpty(), "Pure __type introspection should be allowed")
    }

    @Test
    fun `mixed __type introspection and data query is rejected`() {
        val query = """
            query MixedTypeQuery {
              helloWorld
              __type(name: "User") {
                name
              }
            }
        """.trimIndent()

        val result = parseQuery(query)

        assertFalse(result.errors.isEmpty(), "Mixed __type queries should be rejected")
        assertEquals(
            "Introspective queries cannot select non-introspective fields.",
            result.errors.first().message
        )
    }

    @Test
    fun `multiple operations - introspection query by name is allowed`() {
        val query = """
            query IntrospectionQuery {
              __schema {
                queryType {
                  name
                }
              }
            }

            query DataQuery {
              helloWorld
            }
        """.trimIndent()

        val result = parseQuery(query, operationName = "IntrospectionQuery")

        assertTrue(result.errors.isEmpty(), "Named pure introspection query should be allowed")
    }

    @Test
    fun `multiple operations - mixed query by name is rejected`() {
        val query = """
            query IntrospectionQuery {
              __schema {
                queryType {
                  name
                }
              }
            }

            query MixedQuery {
              helloWorld
              __schema {
                queryType {
                  name
                }
              }
            }
        """.trimIndent()

        val result = parseQuery(query, operationName = "MixedQuery")

        assertFalse(result.errors.isEmpty(), "Named mixed query should be rejected")
        assertEquals(
            "Introspective queries cannot select non-introspective fields.",
            result.errors.first().message
        )
    }
}
