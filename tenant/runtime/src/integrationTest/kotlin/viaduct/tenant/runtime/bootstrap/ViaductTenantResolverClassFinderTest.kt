package viaduct.tenant.runtime.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ViaductTenantResolverClassFinderTest {
    private lateinit var tenantResolverClassFinder: ViaductTenantResolverClassFinder

    companion object {
        private const val PACKAGE_NAME = "viaduct.api.bootstrap.test"
    }

    @BeforeEach
    fun setUp() {
        tenantResolverClassFinder = ViaductTenantResolverClassFinder(
            tenantPackage = PACKAGE_NAME,
            grtPackagePrefix = "$PACKAGE_NAME.grts"
        )
    }

    @Test
    fun `set of resolver classes is as expected`() {
        assertEquals(
            setOf(
                "viaduct.api.bootstrap.test.TestTypeModernResolvers\$AField",
                "viaduct.api.bootstrap.test.TestTypeModernResolvers\$BIntField",
                "viaduct.api.bootstrap.test.TestTypeModernResolvers\$DField",
                "viaduct.api.bootstrap.test.TestTypeModernResolvers\$ParameterizedField",
                "viaduct.api.bootstrap.test.TestTypeModernResolvers\$WhenMappingsTest",
            ),
            tenantResolverClassFinder.resolverClassesInPackage().map { it.name }.toSet()
        )
    }

    @Test
    fun `set of node resolver classes is as expected`() {
        assertEquals(
            setOf(
                "viaduct.api.bootstrap.test.TestBatchNodeResolverBase",
                "viaduct.api.bootstrap.test.TestMissingResolverBase",
                "viaduct.api.bootstrap.test.TestNodeResolverBase",
            ),
            tenantResolverClassFinder.nodeResolverForClassesInPackage().map { it.name }.toSet()
        )
    }

    @Test
    fun `set of subtypes of resolver base is as expected`() {
        assertEquals(
            setOf(
                "viaduct.api.bootstrap.test.TestNodeResolver"
            ),
            tenantResolverClassFinder.getSubTypesOf(
                Class.forName("viaduct.api.bootstrap.test.TestNodeResolverBase")
            ).map { it.name }.toSet()
        )
        assertEquals(
            setOf(
                "viaduct.api.bootstrap.test.TestBatchNodeResolver"
            ),
            tenantResolverClassFinder.getSubTypesOf(
                Class.forName("viaduct.api.bootstrap.test.TestBatchNodeResolverBase")
            ).map { it.name }.toSet()
        )
        assertEquals(
            setOf(
                "viaduct.api.bootstrap.test.AFieldResolver"
            ),
            tenantResolverClassFinder.getSubTypesOf(
                Class.forName("viaduct.api.bootstrap.test.TestTypeModernResolvers\$AField")
            ).map { it.name }.toSet()
        )
    }

    @Test
    fun `grt class can be determined`() {
        assertEquals("viaduct.api.bootstrap.test.grts.TestType", tenantResolverClassFinder.grtClassForName("TestType").qualifiedName)
    }

    @Test
    fun `argument class can be determined`() {
        assertEquals("viaduct.api.bootstrap.test.grts.TestType", tenantResolverClassFinder.argumentClassForName("TestType").qualifiedName)
    }
}
