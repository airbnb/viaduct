package viaduct.engine.api.spi

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProxyResolverFactoryTest {
    @Test
    fun `NO_OP proxyField returns null`() {
        val executor = mockk<FieldResolverExecutor>()
        assertThat(ProxyResolverFactory.NO_OP.proxyField(executor)).isNull()
    }

    @Test
    fun `NO_OP proxyNode returns null`() {
        val executor = mockk<NodeResolverExecutor>()
        assertThat(ProxyResolverFactory.NO_OP.proxyNode(executor)).isNull()
    }
}
