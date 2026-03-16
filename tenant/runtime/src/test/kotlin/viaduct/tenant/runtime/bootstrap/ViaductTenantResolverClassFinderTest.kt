package viaduct.tenant.runtime.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Named
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ViaductTenantResolverClassFinderTest {
    companion object {
        private const val PACKAGE_NAME = "viaduct.api.bootstrap.test"

        @JvmStatic
        fun classFinderModes() =
            listOf(
                Named.of(
                    "default mode (withNewScanner=false)",
                    ViaductTenantResolverClassFinder(PACKAGE_NAME, "$PACKAGE_NAME.grts", withNewScanner = false)
                ),
                Named.of(
                    "new scanner mode (withNewScanner=true)",
                    ViaductTenantResolverClassFinder(PACKAGE_NAME, "$PACKAGE_NAME.grts", withNewScanner = true)
                ),
            )
    }

    @ParameterizedTest
    @MethodSource("classFinderModes")
    fun `set of resolver classes is as expected`(finder: ViaductTenantResolverClassFinder) {
        assertEquals(
            setOf(
                "viaduct.api.bootstrap.test.TestTypeModernResolvers\$AField",
                "viaduct.api.bootstrap.test.TestTypeModernResolvers\$BIntField",
                "viaduct.api.bootstrap.test.TestTypeModernResolvers\$DField",
                "viaduct.api.bootstrap.test.TestTypeModernResolvers\$ParameterizedField",
                "viaduct.api.bootstrap.test.TestTypeModernResolvers\$WhenMappingsTest",
            ),
            finder.resolverClassesInPackage().map { it.name }.toSet()
        )
    }

    @ParameterizedTest
    @MethodSource("classFinderModes")
    fun `set of node resolver classes is as expected`(finder: ViaductTenantResolverClassFinder) {
        assertEquals(
            setOf(
                "viaduct.api.bootstrap.test.TestBatchNodeResolverBase",
                "viaduct.api.bootstrap.test.TestMissingResolverBase",
                "viaduct.api.bootstrap.test.TestNodeResolverBase",
            ),
            finder.nodeResolverForClassesInPackage().map { it.name }.toSet()
        )
    }

    @ParameterizedTest
    @MethodSource("classFinderModes")
    fun `set of subtypes of resolver base is as expected`(finder: ViaductTenantResolverClassFinder) {
        assertEquals(
            setOf("viaduct.api.bootstrap.test.TestNodeResolver"),
            finder.getSubTypesOf(Class.forName("viaduct.api.bootstrap.test.TestNodeResolverBase")).map { it.name }.toSet()
        )
        assertEquals(
            setOf("viaduct.api.bootstrap.test.TestBatchNodeResolver"),
            finder.getSubTypesOf(Class.forName("viaduct.api.bootstrap.test.TestBatchNodeResolverBase")).map { it.name }.toSet()
        )
        assertEquals(
            setOf("viaduct.api.bootstrap.test.AFieldResolver"),
            finder.getSubTypesOf(Class.forName("viaduct.api.bootstrap.test.TestTypeModernResolvers\$AField")).map { it.name }.toSet()
        )
    }

    @ParameterizedTest
    @MethodSource("classFinderModes")
    fun `grt class can be determined`(finder: ViaductTenantResolverClassFinder) {
        assertEquals("viaduct.api.bootstrap.test.grts.TestType", finder.grtClassForName("TestType").qualifiedName)
    }

    @ParameterizedTest
    @MethodSource("classFinderModes")
    fun `argument class can be determined`(finder: ViaductTenantResolverClassFinder) {
        assertEquals("viaduct.api.bootstrap.test.grts.TestType", finder.argumentClassForName("TestType").qualifiedName)
    }
}
