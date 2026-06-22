package viaduct.java.runtime.bridge

import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ViaductSchema
import viaduct.errors.TenantUsageException
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class InternalContextImplTest {
    private val schema = mockk<ViaductSchema>()
    private val classFinder = mockk<ResolverClassFinder>()

    private fun newContext() =
        InternalContextImpl(
            schema = schema,
            globalIDCodec = GlobalIDCodecDefault,
            classFinder = classFinder,
        )

    @Test
    fun `getSchema returns the provided schema`() {
        assertSame(schema, newContext().getSchema())
    }

    @Test
    fun `getGlobalIDCodec returns the provided codec`() {
        assertSame(GlobalIDCodecDefault, newContext().getGlobalIDCodec())
    }

    @Test
    fun `getClassFinder returns the provided classFinder`() {
        assertSame(classFinder, newContext().getClassFinder())
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
    fun `deserializeGlobalID wraps codec IllegalArgumentException in TenantUsageException`() {
        val ex = assertThrows<TenantUsageException> {
            newContext().deserializeGlobalID<NodeObject>("not-valid-base64!!!")
        }
        assertTrue(ex.message!!.contains("Invalid GlobalID"))
        ex.cause.shouldBeInstanceOf<IllegalArgumentException>()
    }
}
