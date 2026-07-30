@file:Suppress("ForbiddenImport")

package viaduct.remote

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.remote.grpc.BatchResolveFieldRequest
import viaduct.remote.grpc.BatchResolveNodeRequest
import viaduct.remote.grpc.RemoteResolverStreamServiceGrpcKt
import viaduct.remote.grpc.ViaductServiceFieldMessage
import viaduct.remote.grpc.ViaductServiceMessage

/**
 * Lightweight in-process gRPC test proving [RemoteResolverStreamServiceImpl] wires the real proto
 * message types into [withStreamedCallbacks] correctly for both RPCs. No registered executor
 * lookup yet, so each RPC always answers with an empty resolve_response.
 */
class RemoteResolverStreamServiceImplTest {
    @Test
    fun `resolveNodeBatch round-trips a resolve_request to an empty resolve_response`() =
        runTest {
            val serverName = "rrs-stream-node-${System.nanoTime()}"
            val server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(RemoteResolverStreamServiceImpl())
                .build()
                .start()
            try {
                val stub = RemoteResolverStreamServiceGrpcKt.RemoteResolverStreamServiceCoroutineStub(
                    InProcessChannelBuilder.forName(serverName).directExecutor().build()
                )
                val request = ViaductServiceMessage.newBuilder()
                    .setResolveRequest(BatchResolveNodeRequest.newBuilder().setExecutorId("Character").build())
                    .build()

                val responses = stub.resolveNodeBatch(flowOf(request))
                val messages = mutableListOf<Any>()
                responses.collect { messages.add(it) }

                assertEquals(1, messages.size)
            } finally {
                server.shutdownNow()
            }
        }

    @Test
    fun `resolveFieldBatch round-trips a resolve_request to an empty resolve_response`() =
        runTest {
            val serverName = "rrs-stream-field-${System.nanoTime()}"
            val server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(RemoteResolverStreamServiceImpl())
                .build()
                .start()
            try {
                val stub = RemoteResolverStreamServiceGrpcKt.RemoteResolverStreamServiceCoroutineStub(
                    InProcessChannelBuilder.forName(serverName).directExecutor().build()
                )
                val request = ViaductServiceFieldMessage.newBuilder()
                    .setResolveRequest(BatchResolveFieldRequest.newBuilder().setExecutorId("Character.isAdult").build())
                    .build()

                val responses = stub.resolveFieldBatch(flowOf(request))
                val messages = mutableListOf<Any>()
                responses.collect { messages.add(it) }

                assertEquals(1, messages.size)
            } finally {
                server.shutdownNow()
            }
        }
}
