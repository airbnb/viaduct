package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TenantModuleMetadataTest {
    @Test
    fun `fromMap extracts name from map`() {
        val metadata = TenantModuleMetadata.fromMap(mapOf("name" to "viaduct-data-wishlist"))

        assertEquals("viaduct-data-wishlist", metadata.name)
    }

    @Test
    fun `fromMap returns null name when key is absent`() {
        val metadata = TenantModuleMetadata.fromMap(emptyMap())

        assertNull(metadata.name)
    }

    @Test
    fun `fromMap returns null name when map has other keys`() {
        val metadata = TenantModuleMetadata.fromMap(mapOf("other" to "value"))

        assertNull(metadata.name)
    }

    @Test
    fun `EMPTY singleton has null name`() {
        assertNull(TenantModuleMetadata.EMPTY.name)
    }
}
