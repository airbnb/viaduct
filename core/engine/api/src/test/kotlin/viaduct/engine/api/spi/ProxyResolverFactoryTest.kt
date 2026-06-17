package viaduct.engine.api.spi

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProxyResolverFactoryTest {
    @Test
    fun `NO_OP proxyField returns null`() {
        val executor = mockk<FieldResolverExecutor>()
        assertNull(ProxyResolverFactory.NO_OP.proxyField(executor))
    }

    @Test
    fun `NO_OP proxyNode returns null`() {
        val executor = mockk<NodeResolverExecutor>()
        assertNull(ProxyResolverFactory.NO_OP.proxyNode(executor))
    }
}
