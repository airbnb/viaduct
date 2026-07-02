@file:Suppress("ForbiddenImport")

package viaduct.remote

import io.grpc.inprocess.InProcessChannelBuilder
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.remote.fixtures.SimpleFieldResolverExecutor
import viaduct.remote.registry.FieldExecutorRegistry

/**
 * Covers the opt-in field-proxying wiring in [RemoteProxyResolverFactory.proxyFields]: only the
 * listed field coordinates ("Type.field") are wrapped in a [RemoteFieldProxyExecutor]; every other
 * field is left to run locally (proxyField returns null).
 */
class RemoteFieldProxyFactoryTest {
    @Test
    fun `proxyFields factory wraps only the listed field coordinates`() {
        val rrsChannel = InProcessChannelBuilder.forName("test-rrs-proxyfields-${System.nanoTime()}").directExecutor().build()
        try {
            // Only "Character.isAdult" is listed; the predicate keys solely on resolverId, so the
            // same fixture with the unlisted "Character.summary" id is left unproxied (returns null).
            val factory = RemoteProxyResolverFactory.proxyFields(rrsChannel, "test-cb", "Character.isAdult")

            val listed = factory.proxyField(SimpleFieldResolverExecutor(resolverId = "Character.isAdult"))
            val unlisted = factory.proxyField(SimpleFieldResolverExecutor(resolverId = "Character.summary"))

            assertTrue(listed is RemoteFieldProxyExecutor, "Listed coordinate should be proxied as a RemoteFieldProxyExecutor")
            assertNull(unlisted, "Unlisted coordinate should not be proxied")
        } finally {
            rrsChannel.shutdownNow()
            FieldExecutorRegistry.clear()
        }
    }
}
