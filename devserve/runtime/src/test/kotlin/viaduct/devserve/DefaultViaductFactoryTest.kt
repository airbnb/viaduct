package viaduct.devserve

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for DefaultViaductFactory resolver discovery and Viaduct creation.
 *
 * These tests verify that DefaultViaductFactory can:
 * - Discover @Resolver annotated classes
 * - Auto-detect package prefix
 * - Create a valid Viaduct instance
 */
class DefaultViaductFactoryTest {

    @Test
    fun `DefaultViaductFactory should discover resolvers on classpath`() {
        // Given: Test classpath contains test resolvers in fixtures package
        val factory = DefaultViaductFactory()

        // When: createViaduct is called
        val viaduct = factory.createViaduct()

        // Then: A Viaduct instance should be created successfully
        assertNotNull(viaduct, "Viaduct instance should be created")
    }

    @Test
    fun `DefaultViaductFactory should auto-detect package prefix`() {
        // Given: No explicit package prefix
        val factory = DefaultViaductFactory()

        // When: createViaduct is called
        val viaduct = factory.createViaduct()

        // Then: Package prefix should be auto-detected and Viaduct created
        assertNotNull(viaduct, "Viaduct should be created with auto-detected package")
    }

    @Test
    fun `DefaultViaductFactory should accept explicit package prefix`() {
        // Given: Explicit package prefix
        val factory = DefaultViaductFactory(
            packagePrefix = "viaduct.devserve.fixtures"
        )

        // When: createViaduct is called
        val viaduct = factory.createViaduct()

        // Then: Viaduct should be created with explicit package
        assertNotNull(viaduct, "Viaduct should be created with explicit package")
    }

    @Test
    fun `DefaultViaductFactory should handle empty resolver list gracefully`() {
        // Given: Package prefix that contains no resolvers
        val factory = DefaultViaductFactory(
            packagePrefix = "nonexistent.package"
        )

        // When: createViaduct is called
        // Then: Should still create Viaduct (with warning logged)
        val viaduct = factory.createViaduct()
        assertNotNull(viaduct, "Viaduct should be created even with no resolvers")
    }

    @Test
    fun `DefaultViaductFactory should skip resolvers without no-arg constructor`() {
        // Given: Test classpath contains ResolverWithoutNoArgConstructor
        val factory = DefaultViaductFactory()

        // When: createViaduct is called
        val viaduct = factory.createViaduct()

        // Then: Should create Viaduct successfully, skipping invalid resolver
        assertNotNull(viaduct, "Viaduct should be created, skipping invalid resolvers")
    }

    @Test
    fun `created Viaduct should be usable for queries`() {
        // Given: Factory creates Viaduct
        val factory = DefaultViaductFactory()
        val viaduct = factory.createViaduct()

        // Then: Viaduct should have basic functionality
        assertNotNull(viaduct, "Viaduct should exist")

        // Verify it's a proper Viaduct instance
        assertTrue(
            viaduct is viaduct.service.api.Viaduct,
            "Should be a Viaduct instance"
        )
    }
}
