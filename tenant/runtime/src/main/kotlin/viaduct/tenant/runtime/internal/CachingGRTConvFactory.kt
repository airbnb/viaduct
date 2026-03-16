@file:OptIn(InternalApi::class)

package viaduct.tenant.runtime.internal

import com.github.benmanes.caffeine.cache.Caffeine
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLType
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.internal.GRTConvFactory
import viaduct.api.internal.InternalContext
import viaduct.api.internal.KeyMapping
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineSelectionSet
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.IR

/**
 * A [GRTConvFactory] backed by LRU caches.
 *
 * @param createCacheSize the maximum size of the LRU cache that supports [create]
 * @param createForInputFieldCacheSize the maximum size of the LRU cache that supports [createForInputField]
 */
class CachingGRTConvFactory(
    private val underlying: GRTConvFactory = DefaultGRTConvFactory,
    createCacheSize: Long = 1000,
    createForInputFieldCacheSize: Long = 2000,
) : GRTConvFactory {
    private data class TypeCacheKey(val typeName: String, val keyMapping: KeyMapping?)

    private val typeCache = Caffeine.newBuilder()
        .maximumSize(createCacheSize)
        .build<TypeCacheKey, Conv<Any?, IR.Value>>()

    private val fieldCache = Caffeine.newBuilder()
        .maximumSize(createForInputFieldCacheSize)
        .build<GraphQLInputObjectField, Conv<Any?, IR.Value>>()

    override fun create(
        internalCtx: InternalContext,
        type: GraphQLType,
        selectionSet: EngineSelectionSet?,
        keyMapping: KeyMapping?,
    ): Conv<Any?, IR.Value> {
        if (selectionSet == null) {
            val typeName = (type as? GraphQLNamedType)?.name
            if (typeName != null) {
                return typeCache.get(TypeCacheKey(typeName, keyMapping)) {
                    underlying.create(internalCtx, type, selectionSet, keyMapping)
                }!!
            }
        }
        return underlying.create(internalCtx, type, selectionSet, keyMapping)
    }

    override fun createForInputField(
        internalCtx: InternalContext,
        field: GraphQLInputObjectField,
    ): Conv<Any?, IR.Value> =
        fieldCache.get(field) {
            underlying.createForInputField(internalCtx, field)
        }!!
}
