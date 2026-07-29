package viaduct.tenant.codegen.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TenantModuleNameTest {
    @Test
    fun `strips the default viaduct prefix and converts dots to slashes`() {
        assertEquals("data/example", tenantModuleNameFromPackage("com.airbnb.viaduct.data.example", "com.airbnb.viaduct"))
    }

    @Test
    fun `preserves hyphens in tenant segments`() {
        // Regression: schema_module_fully_qualified_name maps `-` to `_`, which is not reversible.
        // The canonical name must keep the hyphen so classic and modern configs agree on `data/demo-todo`.
        assertEquals("data/demo-todo", tenantModuleNameFromPackage("com.airbnb.viaduct.data.demo-todo", "com.airbnb.viaduct"))
    }

    @Test
    fun `handles multi-segment hyphenated tenants`() {
        assertEquals("data/stays-listing", tenantModuleNameFromPackage("com.airbnb.viaduct.data.stays-listing", "com.airbnb.viaduct"))
    }

    @Test
    fun `honors a package override whose prefix differs from the default`() {
        // Package overrides (e.g. service blocks like computron) generate resolver classes under a
        // non-default package; the tenant name strips that override's own prefix.
        assertEquals("foo", tenantModuleNameFromPackage("com.airbnb.computron.foo", "com.airbnb.computron"))
    }

    @Test
    fun `does not strip a prefix the package does not start with`() {
        assertEquals("com/airbnb/other/tenant", tenantModuleNameFromPackage("com.airbnb.other.tenant", "com.airbnb.viaduct"))
    }

    @Test
    fun `treats a blank prefix as no prefix`() {
        assertEquals("com/airbnb/viaduct/data/example", tenantModuleNameFromPackage("com.airbnb.viaduct.data.example", ""))
        assertEquals("com/airbnb/viaduct/data/example", tenantModuleNameFromPackage("com.airbnb.viaduct.data.example", null))
    }

    @Test
    fun `throws when the package equals the prefix, leaving an empty name`() {
        assertThrows(IllegalArgumentException::class.java) {
            tenantModuleNameFromPackage("com.airbnb.viaduct", "com.airbnb.viaduct")
        }
    }
}
