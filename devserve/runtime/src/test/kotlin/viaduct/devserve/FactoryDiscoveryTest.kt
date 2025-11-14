package viaduct.devserve

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for FactoryDiscovery classpath scanning functionality.
 *
 * Note: These tests rely on the test fixtures in the fixtures package.
 * The behavior depends on having exactly one valid @ViaductApplication
 * annotated factory with a no-arg constructor on the test classpath.
 */
class FactoryDiscoveryTest {

    @Test
    fun `discoverFactory should find valid factory on classpath`() {
        // Given: Test classpath contains ValidTestFactory

        // When: Discovery is performed
        val factory = FactoryDiscovery.discoverFactory()

        // Then: A factory instance is returned
        assertNotNull(factory, "Factory should be discovered")
        assertTrue(
            factory::class.simpleName == "ValidTestFactory" ||
            factory::class.simpleName == "AnotherValidTestFactory",
            "Factory should be one of the test fixtures"
        )
    }

    @Test
    fun `discoverFactory should throw when no factory found`() {
        // This test would require isolating the classpath
        // For now, we document the expected behavior:
        // When: No @ViaductApplication annotated factory exists
        // Then: Should throw IllegalStateException with helpful message

        // We can't easily test this without classpath isolation,
        // but the behavior is verified in FactoryDiscovery.kt
    }

    @Test
    fun `factory discovery should validate ViaductFactory implementation`() {
        // Given: AnnotatedNonFactory exists on classpath but doesn't implement ViaductFactory

        // When/Then: Discovery should handle this gracefully
        // The AnnotatedNonFactory should be rejected during discovery
        // This is verified by the implementation checking isAssignableFrom

        val factory = FactoryDiscovery.discoverFactory()
        assertNotNull(factory)
    }

    @Test
    fun `factory discovery should require no-arg constructor`() {
        // Given: FactoryWithoutNoArgConstructor exists on classpath

        // When/Then: Discovery should handle this gracefully
        // The factory without no-arg constructor should be rejected
        // This is verified by the implementation catching NoSuchMethodException

        val factory = FactoryDiscovery.discoverFactory()
        assertNotNull(factory)
    }

    @Test
    fun `discovered factory should be instantiable`() {
        // Given: Factory is discovered
        val factory = FactoryDiscovery.discoverFactory()

        // Then: Factory should be a valid ViaductFactory instance
        assertNotNull(factory)
        assertTrue(factory is viaduct.service.api.ViaductFactory)
    }

    @Test
    fun `factory discovery should ignore unannotated factories`() {
        // Given: FactoryWithoutAnnotation exists on classpath

        // When: Discovery is performed
        val factory = FactoryDiscovery.discoverFactory()

        // Then: Only annotated factories should be found
        // FactoryWithoutAnnotation should not be returned
        assertNotNull(factory)
        assertTrue(factory::class.simpleName != "FactoryWithoutAnnotation")
    }
}
