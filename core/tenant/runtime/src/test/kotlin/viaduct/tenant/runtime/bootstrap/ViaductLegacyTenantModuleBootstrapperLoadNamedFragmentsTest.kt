package viaduct.tenant.runtime.bootstrap

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.documents.GraphQLFragment
import viaduct.engine.api.TenantModuleMetadata
import viaduct.service.api.spi.CodeInjector

class ViaductLegacyTenantModuleBootstrapperLoadNamedFragmentsTest {
    @GraphQLFragment("fragment FooFields on Foo { id name }")
    object FooFieldsFragment

    @GraphQLFragment("fragment BarFields on Bar { id title }")
    object BarFieldsFragment

    @GraphQLFragment("fragment DupeFragment on Foo { id }")
    object DupeFragment1

    @GraphQLFragment("fragment DupeFragment on Foo { name }")
    object DupeFragment2

    private fun bootstrapper(fragmentClasses: Set<Class<*>>): ViaductLegacyTenantModuleBootstrapper {
        val classFinder = mockk<TenantResolverClassFinder> {
            every { namedFragmentClassesInPackage() } returns fragmentClasses
            every { tenantModuleMetadata() } returns TenantModuleMetadata.EMPTY
            every { resolverClassesInPackage() } returns emptySet()
            every { nodeResolverForClassesInPackage() } returns emptySet()
        }
        return ViaductLegacyTenantModuleBootstrapper(
            codeInjector = CodeInjector.Naive,
            tenantResolverClassFinder = classFinder,
        )
    }

    @Test
    fun `returns empty map when no fragment classes exist`() {
        val result = bootstrapper(emptySet()).loadNamedFragments()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns fragment definitions keyed by name`() {
        val result = bootstrapper(setOf(FooFieldsFragment::class.java, BarFieldsFragment::class.java))
            .loadNamedFragments()

        assertEquals(setOf("FooFields", "BarFields"), result.keys)
        assertEquals("Foo", result["FooFields"]!!.typeCondition.name)
        assertEquals("Bar", result["BarFields"]!!.typeCondition.name)
    }

    @Test
    fun `throws when duplicate fragment names are detected`() {
        val exception = assertThrows<IllegalStateException> {
            bootstrapper(setOf(DupeFragment1::class.java, DupeFragment2::class.java))
                .loadNamedFragments()
        }
        assertTrue(exception.message!!.contains("Duplicate @GraphQLFragment names detected"))
        assertTrue(exception.message!!.contains("DupeFragment"))
    }

    @Test
    fun `skips classes without @GraphQLFragment annotation`() {
        val result = bootstrapper(setOf(String::class.java, FooFieldsFragment::class.java))
            .loadNamedFragments()

        assertEquals(setOf("FooFields"), result.keys)
    }
}
