@file:Suppress("ForbiddenImport")

package viaduct.remote

import io.grpc.inprocess.InProcessChannelBuilder
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class RemoteEngineExecutionContextTest {
    private fun channel() = InProcessChannelBuilder.forName("rrp-${System.nanoTime()}").build()

    @Test
    fun `null delegate falls back to localSchema and the default GlobalID codec`() {
        val schema = MockSchema.mk("extend type Query { ping: String }")
        val ctx = RemoteEngineExecutionContext(
            delegate = null,
            callbackChannel = channel(),
            contextHandle = "h",
            localSchema = schema,
        )
        assertSame(schema, ctx.fullSchema)
        assertSame(GlobalIDCodecDefault, ctx.globalIDCodec)
    }

    @Test
    fun `null delegate without localSchema throws on schema access`() {
        val ctx = RemoteEngineExecutionContext(
            delegate = null,
            callbackChannel = channel(),
            contextHandle = "h",
            localSchema = null,
        )
        assertFailsWith<UnsupportedOperationException> { ctx.fullSchema }
        assertFailsWith<UnsupportedOperationException> { ctx.engine }
    }
}
