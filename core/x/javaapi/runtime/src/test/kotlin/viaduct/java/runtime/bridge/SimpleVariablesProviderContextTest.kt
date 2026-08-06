package viaduct.java.runtime.bridge

import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductSchema
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.reflect.Type
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class SimpleVariablesProviderContextTest {
    private fun mockEngineContext(): EngineExecutionContext =
        mockk {
            every { globalIDCodec } returns GlobalIDCodecDefault
        }

    private fun newContext(
        requestContext: Any? = null,
        arguments: Arguments? = null,
        engineCtx: EngineExecutionContext? = mockEngineContext(),
    ) = SimpleVariablesProviderContext(
        requestContext = requestContext,
        arguments = arguments,
        engineExecutionContext = engineCtx,
    )

    private fun nodeType(name: String = "NodeObj"): Type<NodeObject> =
        object : Type<NodeObject> {
            override fun getName(): String = name

            override fun getJavaClass(): Class<out NodeObject> = NodeObject::class.java
        }

    @Test
    fun `getArguments returns NoArguments when none provided`() {
        assertSame(Arguments.None, newContext().getArguments())
    }

    @Test
    fun `getArguments returns provided arguments`() {
        val args = Arguments.None
        assertSame(args, newContext(arguments = args).getArguments())
    }

    @Test
    fun `getRequestContext returns provided value`() {
        assertEquals("ctx-value", newContext(requestContext = "ctx-value").getRequestContext())
    }

    @Test
    fun `getRequestContext returns null when none provided`() {
        assertNull(newContext().getRequestContext())
    }

    @Test
    fun `globalIDFor creates a typed GlobalID from type name and internal id`() {
        val gid: GlobalID<NodeObject> = newContext().globalIDFor(nodeType(), "abc")
        assertEquals("abc", gid.getInternalID())
        gid.shouldBeInstanceOf<GlobalIDImpl<*>>()
    }

    @Test
    fun `globalIDFor throws FrameworkException when engineExecutionContext is null`() {
        val ex = assertThrows<FrameworkException> { newContext(engineCtx = null).globalIDFor(nodeType(), "abc") }
        assertTrue(ex.message!!.contains("globalIDFor requires engineExecutionContext"))
    }

    @Test
    fun `serialize returns serialized form for a GlobalID`() {
        val ctx = newContext()
        val gid = ctx.globalIDFor(nodeType(), "xyz")
        assertEquals(GlobalIDCodecDefault.serialize("NodeObj", "xyz"), ctx.serialize(gid))
    }

    @Test
    fun `serialize throws FrameworkException when engineExecutionContext is null`() {
        val gid = GlobalIDImpl(nodeType(), "xyz")
        val ex = assertThrows<FrameworkException> { newContext(engineCtx = null).serialize(gid) }
        assertTrue(ex.message!!.contains("serialize requires engineExecutionContext"))
    }

    @Test
    fun `deserializeGlobalID deserializes a serialized id into a typed GlobalID`() {
        val gid: GlobalID<NodeObject> =
            newContext().deserializeGlobalID(GlobalIDCodecDefault.serialize("NodeObj", "tenant1"))
        gid.shouldBeInstanceOf<GlobalIDImpl<*>>()
        assertEquals("tenant1", gid.getInternalID())
        assertEquals("NodeObj", gid.getType().name)
    }

    @Test
    fun `deserializeGlobalID throws FrameworkException when engineExecutionContext is null`() {
        val ex = assertThrows<FrameworkException> {
            newContext(engineCtx = null).deserializeGlobalID<NodeObject>(GlobalIDCodecDefault.serialize("NodeObj", "tenant1"))
        }
        assertTrue(ex.message!!.contains("deserializeGlobalID requires engineExecutionContext"))
    }

    @Test
    fun `deserializeGlobalID wraps codec IllegalArgumentException in TenantUsageException`() {
        val ex = assertThrows<TenantUsageException> {
            newContext().deserializeGlobalID<NodeObject>("not-valid-base64!!!")
        }
        assertTrue(ex.message!!.contains("Invalid GlobalID"))
        ex.cause.shouldBeInstanceOf<IllegalArgumentException>()
    }

    // ── InternalContext tests ──

    @Test
    fun `getSchema returns schema from engineExecutionContext`() {
        val schema = mockk<ViaductSchema>()
        val engineCtx = mockk<EngineExecutionContext> {
            every { fullSchema } returns schema
        }
        assertSame(schema, newContext(engineCtx = engineCtx).getSchema())
    }

    @Test
    fun `getSchema throws when engineExecutionContext is null`() {
        val ex = assertThrows<FrameworkException> { newContext(engineCtx = null).getSchema() }
        assertTrue(ex.message!!.contains("engineExecutionContext"))
    }

    @Test
    fun `getGlobalIDCodec returns codec from engineExecutionContext`() {
        val codec = mockk<GlobalIDCodec>()
        val engineCtx = mockk<EngineExecutionContext> {
            every { globalIDCodec } returns codec
        }
        assertSame(codec, newContext(engineCtx = engineCtx).getGlobalIDCodec())
    }

    @Test
    fun `getGlobalIDCodec throws when engineExecutionContext is null`() {
        val ex = assertThrows<FrameworkException> { newContext(engineCtx = null).getGlobalIDCodec() }
        assertTrue(ex.message!!.contains("engineExecutionContext"))
    }

    @Test
    fun `getClassFinder returns provided classFinder`() {
        val finder = mockk<ResolverClassFinder>()
        val ctx = SimpleVariablesProviderContext(
            requestContext = null,
            engineExecutionContext = mockEngineContext(),
            classFinder = finder,
        )
        assertSame(finder, ctx.getClassFinder())
    }

    @Test
    fun `getClassFinder throws when classFinder is null`() {
        val ex = assertThrows<FrameworkException> { newContext().getClassFinder() }
        assertTrue(ex.message!!.contains("classFinder"))
    }
}
