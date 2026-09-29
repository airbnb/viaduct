package execution

import graphql.Directives
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.incremental.DeferPayload
import graphql.incremental.DelayedIncrementalPartialResult
import graphql.incremental.IncrementalExecutionResult
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeRuntimeWiring
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

class GraphQLJavaDeferTest {
    @Test
    fun `graphql-java 26 emits deferred async fields incrementally`() {
        val slow = CompletableFuture<String>()
        val wiring =
            RuntimeWiring
                .newRuntimeWiring()
                .type(
                    TypeRuntimeWiring
                        .newTypeWiring("Query")
                        .dataFetcher("fast") { "initial" }
                        .dataFetcher("slow") { slow },
                ).build()
        val schema =
            SchemaGenerator()
                .makeExecutableSchema(SchemaParser().parse(SCHEMA), wiring)
                .transform { builder -> builder.additionalDirective(Directives.DeferDirective) }
        val graphQL = GraphQL.newGraphQL(schema).build()
        val input =
            ExecutionInput
                .newExecutionInput()
                .query(
                    """
                    query {
                      fast
                      ... @defer(label: "slow-part") {
                        slow
                      }
                    }
                    """.trimIndent(),
                )
        GraphQL
            .unusualConfiguration(input)
            .incrementalSupport()
            .enableIncrementalSupport(true)
            .enableEarlyIncrementalFieldExecution(true)

        val initial = graphQL.executeAsync(input.build()).get(5, TimeUnit.SECONDS)

        val incremental = assertIs<IncrementalExecutionResult>(initial)
        assertEquals(mapOf("fast" to "initial"), incremental.getData())
        assertTrue(incremental.hasNext())

        val next = incremental.incrementalItemPublisher.nextResult()
        assertFalse(next.isDone)
        slow.complete("deferred")

        val payload =
            assertIs<DeferPayload>(
                next.get(5, TimeUnit.SECONDS).incremental.single(),
            )
        assertEquals(emptyList(), payload.path)
        assertEquals("slow-part", payload.label)
        assertEquals(mapOf("slow" to "deferred"), payload.getData())
    }

    private companion object {
        val SCHEMA =
            """
            type Query {
              fast: String!
              slow: String!
            }
            """.trimIndent()
    }
}

private fun org.reactivestreams.Publisher<DelayedIncrementalPartialResult>.nextResult(): CompletableFuture<DelayedIncrementalPartialResult> =
    CompletableFuture<DelayedIncrementalPartialResult>().also { result ->
        subscribe(
            object : Subscriber<DelayedIncrementalPartialResult> {
                override fun onSubscribe(subscription: Subscription) {
                    subscription.request(1)
                }

                override fun onNext(item: DelayedIncrementalPartialResult) {
                    result.complete(item)
                }

                override fun onError(throwable: Throwable) {
                    result.completeExceptionally(throwable)
                }

                override fun onComplete() {
                    if (!result.isDone) {
                        result.completeExceptionally(
                            IllegalStateException("Incremental publisher completed without a result"),
                        )
                    }
                }
            },
        )
    }
