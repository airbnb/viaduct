package viaduct.tenant.runtime.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.context.ExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.internal
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.mocks.MockType
import viaduct.api.mocks.testGlobalId
import viaduct.api.reflect.Type
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockSchema
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class InternalContextImplTest {
    private val schema = MockSchema.minimal

    @Test
    fun simple() {
        val ctx = InternalContextImpl(schema, GlobalIDCodecDefault, MockReflectionLoader(), DefaultGRTConvFactory)
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
        assertThrows<FrameworkException> {
            ec.internal
        }
    }

    @Test
    fun `deserializeGlobalID reconstructs GlobalID with proper type`() {
        val type = MockType.mkNodeObject("TestType")
        val ctx = InternalContextImpl(schema, GlobalIDCodecDefault, MockReflectionLoader(type), DefaultGRTConvFactory)

        val serialized = type.testGlobalId("internal-123")
        val deserialized = ctx.deserializeGlobalID<NodeObject>(serialized)

        assertEquals("TestType", deserialized.type.name)
        assertEquals("internal-123", deserialized.internalID)
    }

    @Test
    fun `deserializeGlobalID with different internal IDs`() {
        val type = MockType.mkNodeObject("Product")
        val ctx = InternalContextImpl(schema, GlobalIDCodecDefault, MockReflectionLoader(type), DefaultGRTConvFactory)

        val id1 = ctx.deserializeGlobalID<NodeObject>(type.testGlobalId("abc"))
        val id2 = ctx.deserializeGlobalID<NodeObject>(type.testGlobalId("xyz"))

        assertEquals("abc", id1.internalID)
        assertEquals("xyz", id2.internalID)
        assertEquals(id1.type.name, id2.type.name)
    }

    @Test
    fun `deserializeGlobalID throws for non-NodeObject type`() {
        val nonNodeType = MockType("NonNodeType", Object::class)
        val ctx = InternalContextImpl(schema, GlobalIDCodecDefault, MockReflectionLoader(nonNodeType), DefaultGRTConvFactory)

        val serialized = GlobalIDCodecDefault.serialize("NonNodeType", "id-123")
        val exception = assertThrows<IllegalArgumentException> {
            ctx.deserializeGlobalID<NodeObject>(serialized)
        }
        assertEquals("type `NonNodeType` from GlobalID '$serialized' is not a NodeObject", exception.message)
    }

    @Test
    fun `deserializeGlobalID throws TenantUsageException for malformed GlobalID`() {
        val type = MockType.mkNodeObject("TestType")
        val ctx = InternalContextImpl(schema, GlobalIDCodecDefault, MockReflectionLoader(type), DefaultGRTConvFactory)

        val exception = assertThrows<TenantUsageException> {
            ctx.deserializeGlobalID<NodeObject>("invalid-id-1")
        }
        assertTrue(exception.message?.contains("Invalid GlobalID") ?: false)
        assertTrue(exception.cause is IllegalArgumentException)
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
    override val grtConvFactory get() = TODO()

    override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> = TODO()
}
