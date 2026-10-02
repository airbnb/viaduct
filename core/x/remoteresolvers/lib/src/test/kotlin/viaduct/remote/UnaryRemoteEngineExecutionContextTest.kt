@file:Suppress("ForbiddenImport")

package viaduct.remote

import io.grpc.inprocess.InProcessChannelBuilder
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.FullSchema
import viaduct.engine.api.mocks.MockSchema
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class UnaryRemoteEngineExecutionContextTest {
    private fun channel() = InProcessChannelBuilder.forName("rrp-${System.nanoTime()}").build()

    @Test
    fun `null delegate falls back to localSchema and the default GlobalID codec`() {
        val schema = MockSchema.mk("extend type Query { ping: String }")
        val ctx = UnaryRemoteEngineExecutionContext(
            delegate = null,
            callbackChannel = channel(),
            contextHandle = "h",
            localSchema = schema,
        )
        assertInstanceOf(FullSchema::class.java, ctx.fullSchema)
        assertSame(schema.schema, ctx.fullSchema.schema)
        assertSame(schema.rels, ctx.fullSchema.rels)
        assertSame(GlobalIDCodecDefault, ctx.globalIDCodec)
    }

    @Test
    fun `null delegate without localSchema throws on schema access`() {
        val ctx = UnaryRemoteEngineExecutionContext(
            delegate = null,
            callbackChannel = channel(),
            contextHandle = "h",
            localSchema = null,
        )
        assertThrows<UnsupportedOperationException> { ctx.fullSchema }
        assertThrows<UnsupportedOperationException> { ctx.engine }
    }
}
