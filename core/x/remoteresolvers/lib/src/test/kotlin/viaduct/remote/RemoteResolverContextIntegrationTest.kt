@file:Suppress("ForbiddenImport")

package viaduct.remote

import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.remote.api.EncodedRemoteResolverContext
import viaduct.remote.api.RemoteResolverContextCaptureInput
import viaduct.remote.api.spi.RemoteResolverContextApplier
import viaduct.remote.api.spi.RemoteResolverContextCapturer
import viaduct.remote.api.spi.RemoteResolverContextCapturerProvider
import viaduct.remote.fixtures.SimpleFieldResolverExecutor
import viaduct.remote.fixtures.SimpleNodeResolverExecutor
import viaduct.remote.grpc.BatchResolveFieldRequest
import viaduct.remote.grpc.BatchResolveFieldResponse
import viaduct.remote.grpc.BatchResolveNodeRequest
import viaduct.remote.grpc.BatchResolveNodeResponse
import viaduct.remote.grpc.EncodedRemoteContext
import viaduct.remote.grpc.ErrorInfo
import viaduct.remote.grpc.RemoteResolverServiceGrpcKt
import viaduct.remote.grpc.RemoteResolverServiceMessage
import viaduct.remote.grpc.RemoteResolverStreamServiceGrpcKt
import viaduct.remote.grpc.ResolvedField
import viaduct.remote.grpc.ResolvedNode
import viaduct.remote.grpc.ViaductServiceMessage

class RemoteResolverContextIntegrationTest {
    private val testSchema =
        MockSchema.mk(
            """
            extend type Query { test: String }
            type User {
                id: ID!
                name: String!
            }
            type Character {
                id: ID!
                age: Int!
                isAdult: Boolean
            }
            """.trimIndent(),
        )

    @Test
    fun `node and field proxies send captured context`() =
        runBlocking {
            val expected =
                EncodedRemoteResolverContext(
                    format = "test.context",
                    version = 3,
                    payload = byteArrayOf(1, 2, 3),
                )
            val nodeRequest = AtomicReference<BatchResolveNodeRequest>()
            val fieldRequest = AtomicReference<BatchResolveFieldRequest>()
            val service =
                object : RemoteResolverServiceGrpcKt.RemoteResolverServiceCoroutineImplBase() {
                    override suspend fun batchResolveNode(request: BatchResolveNodeRequest): BatchResolveNodeResponse {
                        nodeRequest.set(request)
                        return BatchResolveNodeResponse.newBuilder()
                            .addResults(
                                ResolvedNode.newBuilder()
                                    .setSelectorId(request.selectorsList.single().id)
                                    .setError(testError()),
                            )
                            .build()
                    }

                    override suspend fun batchResolveField(request: BatchResolveFieldRequest): BatchResolveFieldResponse {
                        fieldRequest.set(request)
                        return BatchResolveFieldResponse.newBuilder()
                            .addResults(
                                ResolvedField.newBuilder()
                                    .setSelectorKey(request.selectorsList.single().selectorKey)
                                    .setError(testError()),
                            )
                            .build()
                    }
                }
            val serverName = "remote-context-${System.nanoTime()}"
            val server =
                InProcessServerBuilder.forName(serverName)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start()
            val channel =
                InProcessChannelBuilder.forName(serverName)
                    .directExecutor()
                    .build()
            val capturer =
                object : RemoteResolverContextCapturer {
                    override fun capture(input: RemoteResolverContextCaptureInput): EncodedRemoteResolverContext {
                        return expected
                    }
                }
            val capturerProvider =
                object : RemoteResolverContextCapturerProvider {
                    override fun get(): RemoteResolverContextCapturer = capturer
                }

            try {
                val engineContext = ContextMocks(testSchema).engineExecutionContext
                val nodeExecutor = SimpleNodeResolverExecutor.createUserResolver()
                val nodeProxy =
                    UnaryRemoteNodeProxyExecutor(
                        originalExecutor = nodeExecutor,
                        executorId = "User",
                        rrsChannel = channel,
                        callbackEndpoint = "unused",
                        contextCapturerProvider = capturerProvider,
                    )
                val nodeSelector =
                    NodeResolverExecutor.Selector(
                        id = "user:1",
                        selections =
                            engineContext.engineSelectionSetFactory.engineSelectionSet(
                                "User",
                                "id name",
                                emptyMap(),
                            ),
                    )
                nodeProxy.resolve(listOf(nodeSelector), engineContext)

                val fieldExecutor = SimpleFieldResolverExecutor()
                val characterType = testSchema.schema.getObjectType("Character")
                val queryType = testSchema.schema.queryType
                val fieldProxy =
                    RemoteFieldProxyExecutor(
                        originalExecutor = fieldExecutor,
                        executorId = fieldExecutor.resolverId,
                        rrsChannel = channel,
                        callbackEndpoint = "unused",
                        contextCapturerProvider = capturerProvider,
                    )
                val fieldSelector =
                    FieldResolverExecutor.Selector(
                        arguments = emptyMap(),
                        selections = null,
                        syncObjectValueGetter = {
                            ResolvedEngineObjectData.Builder(characterType)
                                .put(SimpleFieldResolverExecutor.AGE_FIELD, 25)
                                .build()
                        },
                        syncQueryValueGetter = {
                            ResolvedEngineObjectData.Builder(queryType).build()
                        },
                    )
                fieldProxy.batchResolve(listOf(fieldSelector), engineContext)

                assertWireContext(expected, nodeRequest.get().remoteContext)
                assertWireContext(expected, fieldRequest.get().remoteContext)
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

    @Test
    fun `streaming node proxy sends captured context`() =
        runBlocking {
            val expected =
                EncodedRemoteResolverContext(
                    format = "test.context",
                    version = 3,
                    payload = byteArrayOf(7, 8, 9),
                )
            val nodeRequest = AtomicReference<BatchResolveNodeRequest>()
            val service =
                object : RemoteResolverStreamServiceGrpcKt.RemoteResolverStreamServiceCoroutineImplBase() {
                    override fun resolveNodeBatch(requests: Flow<ViaductServiceMessage>): Flow<RemoteResolverServiceMessage> =
                        flow {
                            val request = requests.first().resolveRequest
                            nodeRequest.set(request)
                            emit(
                                RemoteResolverServiceMessage.newBuilder()
                                    .setResolveResponse(
                                        BatchResolveNodeResponse.newBuilder()
                                            .addResults(
                                                ResolvedNode.newBuilder()
                                                    .setSelectorId(request.selectorsList.single().id)
                                                    .setError(testError()),
                                            ),
                                    )
                                    .build(),
                            )
                        }
                }
            val serverName = "stream-remote-context-${System.nanoTime()}"
            val server =
                InProcessServerBuilder.forName(serverName)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start()
            val channel =
                InProcessChannelBuilder.forName(serverName)
                    .directExecutor()
                    .build()
            val capturer =
                object : RemoteResolverContextCapturer {
                    override fun capture(input: RemoteResolverContextCaptureInput): EncodedRemoteResolverContext {
                        return expected
                    }
                }
            val capturerProvider =
                object : RemoteResolverContextCapturerProvider {
                    override fun get(): RemoteResolverContextCapturer = capturer
                }

            try {
                val engineContext = ContextMocks(testSchema).engineExecutionContext
                val nodeExecutor = SimpleNodeResolverExecutor.createUserResolver()
                val nodeProxy =
                    RemoteNodeStreamProxyExecutor(
                        originalExecutor = nodeExecutor,
                        executorId = "User",
                        rrsChannel = channel,
                        contextCapturerProvider = capturerProvider,
                    )
                val nodeSelector =
                    NodeResolverExecutor.Selector(
                        id = "user:1",
                        selections =
                            engineContext.engineSelectionSetFactory.engineSelectionSet(
                                "User",
                                "id name",
                                emptyMap(),
                            ),
                    )
                nodeProxy.resolve(listOf(nodeSelector), engineContext)

                assertWireContext(expected, nodeRequest.get().remoteContext)
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

    @Test
    fun `streaming node handler applies context before dispatch`() =
        runBlocking {
            val expected =
                EncodedRemoteResolverContext(
                    format = "test.context",
                    version = 1,
                    payload = byteArrayOf(10, 11, 12),
                )
            val received = mutableListOf<EncodedRemoteResolverContext?>()
            val applier =
                object : RemoteResolverContextApplier {
                    override suspend fun <T> apply(
                        context: EncodedRemoteResolverContext?,
                        block: suspend () -> T,
                    ): T {
                        received += context
                        return block()
                    }
                }
            val service = RemoteResolverStreamServiceImpl(applier)
            val request =
                ViaductServiceMessage.newBuilder()
                    .setResolveRequest(
                        BatchResolveNodeRequest.newBuilder()
                            .setExecutorId("missing-node")
                            .setRemoteContext(expected.toWire())
                            .build(),
                    )
                    .build()

            val failure =
                runCatching {
                    service.resolveNodeBatch(flowOf(request)).toList()
                }.exceptionOrNull()

            assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(failure).code)
            assertEquals(1, received.size)
            val actual = received.single()
            assertNotNull(actual)
            assertEquals(expected.format, actual!!.format)
            assertEquals(expected.version, actual.version)
            assertArrayEquals(expected.payload, actual.payload)
        }

    @Test
    fun `node and field handlers apply context before dispatch`() =
        runBlocking {
            val expected =
                EncodedRemoteResolverContext(
                    format = "test.context",
                    version = 1,
                    payload = byteArrayOf(4, 5, 6),
                )
            val received = mutableListOf<EncodedRemoteResolverContext?>()
            val applier =
                object : RemoteResolverContextApplier {
                    override suspend fun <T> apply(
                        context: EncodedRemoteResolverContext?,
                        block: suspend () -> T,
                    ): T {
                        received += context
                        return block()
                    }
                }
            val service = RemoteResolverServiceImpl(applier)

            val nodeFailure =
                runCatching {
                    service.batchResolveNode(
                        BatchResolveNodeRequest.newBuilder()
                            .setExecutorId("missing-node")
                            .setRemoteContext(expected.toWire())
                            .build(),
                    )
                }.exceptionOrNull()
            val fieldFailure =
                runCatching {
                    service.batchResolveField(
                        BatchResolveFieldRequest.newBuilder()
                            .setExecutorId("missing-field")
                            .setRemoteContext(expected.toWire())
                            .build(),
                    )
                }.exceptionOrNull()

            assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(nodeFailure).code)
            assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(fieldFailure).code)
            assertEquals(2, received.size)
            received.forEach { context ->
                assertNotNull(context)
                assertEquals(expected.format, context!!.format)
                assertEquals(expected.version, context.version)
                assertArrayEquals(expected.payload, context.payload)
            }
        }

    @Test
    fun `invalid wire metadata is rejected before the applier starts`() =
        runBlocking {
            var applierStarted = false
            val applier =
                object : RemoteResolverContextApplier {
                    override suspend fun <T> apply(
                        context: EncodedRemoteResolverContext?,
                        block: suspend () -> T,
                    ): T {
                        applierStarted = true
                        return block()
                    }
                }
            val service = RemoteResolverServiceImpl(applier)
            val invalidContext =
                EncodedRemoteContext.newBuilder()
                    .setFormat("")
                    .setVersion(1)
                    .build()

            val failure =
                runCatching {
                    service.batchResolveNode(
                        BatchResolveNodeRequest.newBuilder()
                            .setExecutorId("not-reached")
                            .setRemoteContext(invalidContext)
                            .build(),
                    )
                }.exceptionOrNull()

            assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(failure).code)
            assertFalse(applierStarted)
        }

    private fun assertWireContext(
        expected: EncodedRemoteResolverContext,
        actual: EncodedRemoteContext,
    ) {
        assertEquals(expected.format, actual.format)
        assertEquals(expected.version, actual.version)
        assertArrayEquals(expected.payload, actual.payload.toByteArray())
    }

    private fun testError(): ErrorInfo =
        ErrorInfo.newBuilder()
            .setMessage("expected test response")
            .setErrorType(IllegalStateException::class.java.name)
            .build()
}
