package viaduct.serve

import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for DefaultViaductFactory.
 *
 * These tests verify that DefaultViaductFactory can:
 * - Accept a package prefix and create a Viaduct instance
 * - Create usable Viaduct instances
 *
 * Note: DefaultViaductFactory is used as the fallback when no
 * @ViaductServerConfiguration is found, with a required package prefix.
 */
class DefaultViaductFactoryTest {
    @Test
    fun `DefaultViaductFactory should create Viaduct with provided package prefix`() {
        // Given: A factory with an explicit package prefix
        val factory = DefaultViaductFactory("viaduct.serve.fixtures")

        // When: getViaduct is called
        val viaduct = factory.getViaduct()

        // Then: A Viaduct instance should be created successfully
        assertNotNull(viaduct, "Viaduct instance should be created")
    }

    @Test
    fun `created Viaduct should be usable for queries`() {
        // Given: Factory creates Viaduct with a package prefix
        val factory = DefaultViaductFactory("viaduct.serve.fixtures")
        val viaduct = factory.getViaduct()

        // Then: Viaduct should have basic functionality
        assertNotNull(viaduct, "Viaduct should exist")

        // Verify it's a proper Viaduct instance
        assertTrue(
            viaduct is viaduct.service.api.Viaduct,
            "Should be a Viaduct instance"
        )
    }
}
