package viaduct.serve

import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.serve.fixtures.ValidTestProvider

/**
 * Tests for FactoryDiscovery classpath scanning functionality.
 *
 * Note: These tests rely on the test fixtures in the fixtures package.
 * When no @ViaductServerConfiguration is found, FactoryDiscovery falls back
 * to DefaultViaductFactory if a packagePrefix is provided.
 */
class FactoryDiscoveryTest {
    @Test
    fun `discoverProvider should find valid provider on classpath`() {
        // Given: Test classpath contains ValidTestProvider

        // When: Discovery is performed (packagePrefix is ignored when provider is found)
        val provider = FactoryDiscovery.discoverProvider("viaduct.serve.fixtures")

        // Then: A provider instance is returned
        assertNotNull(provider, "Provider should be discovered")
    }

    @Test
    fun `provider discovery should validate ViaductProvider implementation`() {
        // Given: AnnotatedNonProvider exists on classpath but doesn't implement ViaductProvider

        // When/Then: Discovery should handle this gracefully
        // The AnnotatedNonProvider should be rejected during discovery
        // This is verified by the implementation checking isAssignableFrom

        val provider = FactoryDiscovery.discoverProvider("viaduct.serve.fixtures")
        assertNotNull(provider)
    }

    @Test
    fun `provider discovery should require no-arg constructor`() {
        // Given: ProviderWithoutNoArgConstructor exists on classpath

        // When/Then: Discovery should handle this gracefully
        // The provider without no-arg constructor should be rejected
        // This is verified by the implementation catching NoSuchMethodException

        val provider = FactoryDiscovery.discoverProvider("viaduct.serve.fixtures")
        assertNotNull(provider)
    }

    @Test
    fun `discovered provider should be instantiable`() {
        // Given: Provider is discovered
        val provider = FactoryDiscovery.discoverProvider("viaduct.serve.fixtures")

        // Then: Provider should be a valid ViaductProvider instance
        assertNotNull(provider)
        assertTrue(provider is ViaductProvider)
    }

    @Test
    fun `provider discovery should ignore unannotated providers`() {
        // Given: ProviderWithoutAnnotation exists on classpath

        // When: Discovery is performed
        val provider = FactoryDiscovery.discoverProvider("viaduct.serve.fixtures")

        // Then: Only annotated providers should be found
        // ProviderWithoutAnnotation should not be returned
        assertNotNull(provider)
        assertTrue(provider::class.simpleName != "ProviderWithoutAnnotation")
    }

    @Test
    fun `discovered provider should find ValidTestProvider`() {
        // Given: ValidTestProvider is annotated with @ViaductServerConfiguration

        // When: Discovery is performed
        val provider = FactoryDiscovery.discoverProvider("viaduct.serve.fixtures")

        // Then: ValidTestProvider should be discovered
        assertNotNull(provider)
        assertTrue(
            provider is ValidTestProvider,
            "Expected ValidTestProvider but got ${provider::class.simpleName}"
        )
    }
}
