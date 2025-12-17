package viaduct.engine.api

import viaduct.apiannotations.TestingApi

/**
 * Metadata for a resolver.
 * @property flavor The type of the resolver, e.g. "modern" for modern resolvers.
 * @property name The name of the resolver
 */
data class ResolverMetadata(
    val flavor: String,
    val name: String
) {
    fun toTagString(): String = flavor + ":" + name

    companion object {
        fun forModern(name: String): ResolverMetadata = ResolverMetadata("modern", name)

        @TestingApi
        fun forMock(name: String): ResolverMetadata = ResolverMetadata("mock", name)
    }
}
