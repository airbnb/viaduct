package viaduct.engine.runtime2.model.registry

import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.graphql.schema.ViaductSchema

/** A prepared registry owns only immutable, typed empty inputs, with no operation state. */
internal interface EmptyResolverInputCacheOwner {
    val emptyResolverInputCache: EmptyResolverInputCache
}

internal class EmptyResolverInputCache {
    private val inputs = ConcurrentHashMap<ViaductSchema.Object, EngineObjectData.Sync>()

    fun get(type: ViaductSchema.Object): EngineObjectData.Sync = inputs.computeIfAbsent(type) { engineObjectDataOf(it) }
}

/** Decorated or external registries use the ordinary empty-object factory. */
internal fun ResolverRegistry.emptyResolverInput(type: ViaductSchema.Object): EngineObjectData.Sync =
    (this as? EmptyResolverInputCacheOwner)?.emptyResolverInputCache?.get(type) ?: engineObjectDataOf(type)
