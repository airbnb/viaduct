package viaduct.engine.api

import viaduct.apiannotations.VisibleForTest

enum class ResolverType {
    NODE,
    FIELD,
    MOCK,
}

/**
 * Metadata for a resolver.
 * @property flavor The type of the resolver, e.g. "modern" for modern resolvers.
 * @property name The name of the resolver
 * @property resolverType The kind of resolver: NODE or FIELD.
 * @property tenantMetadata Metadata from the tenant module this resolver belongs to, if available
 */
data class ResolverMetadata(
    val flavor: String,
    val name: String,
    val resolverType: ResolverType,
    val tenantMetadata: TenantModuleMetadata? = null,
) {
    fun toTagString(): String = flavor + ":" + name

    companion object {
        fun forModern(
            name: String,
            resolverType: ResolverType = ResolverType.FIELD,
            tenantMetadata: TenantModuleMetadata? = null,
        ): ResolverMetadata = ResolverMetadata("modern", name, resolverType, tenantMetadata)

        @VisibleForTest
        fun forMock(name: String): ResolverMetadata = ResolverMetadata("mock", name, ResolverType.MOCK)
    }
}
