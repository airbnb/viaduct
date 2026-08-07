package viaduct.graphql.schema.validation.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema

/**
 * Source names reach the validation rules from two producers: URLs, which always use '/', and
 * java.io.File paths, which use the platform separator. These tests pin the matching behaviour for
 * both shapes so the rules behave identically regardless of which producer supplied the path and
 * which platform the build runs on.
 *
 * The backslash cases are written as literals rather than derived from the running platform, so they
 * exercise Windows-shaped input on every OS.
 */
class ModulePartitionPathsTest {
    private val prefix = "partition/"

    private fun location(sourceName: String) = ViaductSchema.SourceLocation(sourceName)

    @Test
    fun `isUnderModulePartition detects a partition in a backslash-separated path`() {
        val windowsPath = "central-schema\\partition\\mymodule\\graphql\\schema.graphqls"

        assertTrue(isUnderModulePartition(location(windowsPath), prefix))
    }

    @Test
    fun `isUnderModulePartition detects a partition in a slash-separated path`() {
        val posixPath = "central-schema/partition/mymodule/graphql/schema.graphqls"

        assertTrue(isUnderModulePartition(location(posixPath), prefix))
    }

    @Test
    fun `isUnderModulePartition detects a partition when separators are mixed`() {
        val mixedPath = "central-schema/partition\\mymodule/graphql\\schema.graphqls"

        assertTrue(isUnderModulePartition(location(mixedPath), prefix))
    }

    @Test
    fun `isUnderModulePartition returns false for an application-level path`() {
        val applicationPath = "central-schema\\application\\graphql\\schema.graphqls"

        assertFalse(isUnderModulePartition(location(applicationPath), prefix))
    }

    @Test
    fun `isUnderModulePartition returns false for a null location`() {
        assertFalse(isUnderModulePartition(null, prefix))
    }

    @Test
    fun `tenantFromLocation extracts the module from a backslash-separated path`() {
        val windowsPath = "central-schema\\partition\\mymodule\\graphql\\schema.graphqls"

        assertEquals("mymodule", tenantFromLocation(location(windowsPath), prefix))
    }

    @Test
    fun `tenantFromLocation extracts the module from a slash-separated path`() {
        val posixPath = "central-schema/partition/mymodule/graphql/schema.graphqls"

        assertEquals("mymodule", tenantFromLocation(location(posixPath), prefix))
    }

    /**
     * Distinct from the case above: even when the prefix matches, the trailing-segment split has its
     * own separator assumption. Without normalization this returns the whole remainder of the path
     * rather than just the module name.
     */
    @Test
    fun `tenantFromLocation stops at the module segment in a backslash-separated path`() {
        val windowsPath = "central-schema\\partition\\mymodule\\graphql\\nested\\schema.graphqls"

        val tenant = tenantFromLocation(location(windowsPath), prefix)

        assertEquals("mymodule", tenant)
        assertFalse(tenant!!.contains('\\'), "module name should not carry trailing path segments")
    }

    @Test
    fun `tenantFromLocation returns null for an application-level path`() {
        val applicationPath = "central-schema\\application\\graphql\\schema.graphqls"

        assertNull(tenantFromLocation(location(applicationPath), prefix))
    }

    @Test
    fun `tenantFromLocation returns null for a null location`() {
        assertNull(tenantFromLocation(null, prefix))
    }
}
