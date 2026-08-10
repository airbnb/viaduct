@file:Suppress("ForbiddenImport")

package viaduct.remote

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema
import viaduct.remote.fixtures.SimpleNodeResolverExecutor
import viaduct.remote.grpc.BatchResolveFieldRequest
import viaduct.remote.grpc.BatchResolveNodeRequest
import viaduct.remote.grpc.RemoteResolverStreamServiceGrpcKt
import viaduct.remote.grpc.Selector
import viaduct.remote.grpc.ViaductServiceFieldMessage
import viaduct.remote.grpc.ViaductServiceMessage
import viaduct.remote.registry.NodeExecutorRegistry
import viaduct.remote.registry.SchemaRegistry

/**
 * In-process gRPC tests for [RemoteResolverStreamServiceImpl]. `resolveNodeBatch` resolves
 * against a registered [NodeExecutorRegistry] executor and schema (via [SchemaRegistry]),
 * reusing [resolveNodeExecutorBatch] shared with the unary transport -- these tests exercise that
 * through the real proto message types and a real gRPC stream. The re-entrant-callback path
 * ([CallbackDispatcher]'s wiring) is covered separately by [WithStreamedCallbacksTest] against
 * fake message types; a real-stream callback round-trip is exercised once a client-side driver
 * exists to answer it. `resolveFieldBatch` doesn't do real resolution yet, so it's covered by a
 * lighter round-trip check.
 */
class RemoteResolverStreamServiceImplTest {
    private val testSchema = MockSchema.mk(
        """
        extend type Query { test: String }
        type User {
            id: ID!
            name: String!
            email: String!
        }
        type Post {
            id: ID!
            title: String!
            content: String!
            authorId: ID!
            author: User
        }
        """.trimIndent()
    )

    @BeforeEach
    fun setUp() {
        SchemaRegistry.register(testSchema)
    }

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
        NodeExecutorRegistry.clear()
    }

    @Test
    fun `resolveNodeBatch resolves a batch against a registered executor`() =
        runTest {
            val executorId = NodeExecutorRegistry.register(SimpleNodeResolverExecutor.createUserResolver())
            withServer { stub ->
                val request = ViaductServiceMessage.newBuilder()
                    .setResolveRequest(
                        BatchResolveNodeRequest.newBuilder()
                            .setExecutorId(executorId)
                            .addSelectors(Selector.newBuilder().setId("user:1").build())
                            .build()
                    )
                    .build()

                val response = stub.resolveNodeBatch(flowOf(request)).toList()
                assertEquals(1, response.size)
                val result = response[0].resolveResponse.resultsList.single()
                val userData = EngineObjectDataSerializer.deserialize(result.dataJson.toByteArray(), testSchema.schema, "User")
                assertEquals("Alice", userData.fetch("name"))
            }
        }

    @Test
    fun `resolveNodeBatch reports a missing executor as NOT_FOUND`() =
        runTest {
            withServer { stub ->
                val request = ViaductServiceMessage.newBuilder()
                    .setResolveRequest(BatchResolveNodeRequest.newBuilder().setExecutorId("never-registered").build())
                    .build()

                val error = runCatching { stub.resolveNodeBatch(flowOf(request)).toList() }.exceptionOrNull()
                assertTrue(error is io.grpc.StatusException || error is io.grpc.StatusRuntimeException, "expected a gRPC status error, got $error")
            }
        }

    @Test
    fun `resolveFieldBatch round-trips a resolve_request to an empty resolve_response`() =
        runTest {
            withServer { stub ->
                val request = ViaductServiceFieldMessage.newBuilder()
                    .setResolveRequest(BatchResolveFieldRequest.newBuilder().setExecutorId("Character.isAdult").build())
                    .build()

                val responses = stub.resolveFieldBatch(flowOf(request)).toList()
                assertEquals(1, responses.size)
            }
        }

    private suspend fun withServer(block: suspend (RemoteResolverStreamServiceGrpcKt.RemoteResolverStreamServiceCoroutineStub) -> Unit) {
        val serverName = "rrs-stream-${System.nanoTime()}"
        val server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(RemoteResolverStreamServiceImpl())
            .build()
            .start()
        try {
            val stub = RemoteResolverStreamServiceGrpcKt.RemoteResolverStreamServiceCoroutineStub(
                InProcessChannelBuilder.forName(serverName).directExecutor().build()
            )
            block(stub)
        } finally {
            server.shutdownNow()
        }
    }
}
