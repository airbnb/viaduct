package viaduct.engine.api

/**
 * Typed metadata for a Viaduct tenant module.
 *
 * @property name The name of the tenant project (e.g., "viaduct-data-wishlist")
 */
data class TenantModuleMetadata(
    val name: String? = null,
) {
    companion object {
        val EMPTY = TenantModuleMetadata()

        fun fromMap(map: Map<String, String>): TenantModuleMetadata =
            TenantModuleMetadata(
                name = map["name"],
            )
    }
}
