package viaduct.tenant.runtime.internal

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.ViaductFrameworkException
import viaduct.api.context.ExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.internal
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.reflect.Type
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockSchema
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class InternalContextImplTest {
    private val schema = MockSchema.minimal

    @Test
    fun simple() {
        val ctx = InternalContextImpl(schema, GlobalIDCodecDefault, MockReflectionLoader())
        assertSame(schema, ctx.schema)
    }

    @Test
    fun executionContextInternal() {
        val ec = TestCompositeContext()
        assertSame(ec, ec.internal)
    }

    @Test
    fun `ExecutionContext_internal -- not an InternalContext`() {
        val ec = TestExecutionContext()
        assertThrows<ViaductFrameworkException> {
            ec.internal
        }
    }
}

private open class TestExecutionContext : ExecutionContext {
    override val requestContext: Any? get() = TODO()

    override fun <T : NodeObject> globalIDFor(
        type: Type<T>,
        internalID: String
    ): GlobalID<T> = TODO()
}

private open class TestCompositeContext : TestExecutionContext(), InternalContext {
    override val schema: ViaductSchema get() = TODO()
    override val globalIDCodec: GlobalIDCodec get() = TODO()
    override val reflectionLoader: ReflectionLoader get() = TODO()

    override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> = TODO()
}
