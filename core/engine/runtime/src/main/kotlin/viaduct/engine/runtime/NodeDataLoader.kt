package viaduct.engine.runtime

import graphql.language.Argument
import graphql.language.SelectionSet
import viaduct.apiannotations.ExcludeFromJacocoGeneratedReport
import viaduct.dataloader.BatchLoaderEnvironment
import viaduct.dataloader.CacheKeyFn
import viaduct.dataloader.CacheKeyMatchFn
import viaduct.dataloader.DataLoader
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.spi.NodeResolverExecutor

/**
 * If the resolver is a batch resolver, then the data loader handles batching together calls to
 * the `resolve` function of a node resolver, and caching the results.
 * If the resolver is a non-batch resolver, then the data loader only caches the results and no
 * batching is done.
 * There should be exactly one instance of this data loader per node type per request.
 */
class NodeDataLoader(
    private val resolver: NodeResolverExecutor
) : DataLoader<NodeResolverExecutor.Selector, Result<EngineObjectData>, NodeResolverExecutor.Selector>() {
    suspend fun loadByKey(
        key: NodeResolverExecutor.Selector,
        context: EngineExecutionContext
    ): Result<EngineObjectData> {
        return internalDataLoader.load(key, context) ?: throw IllegalStateException("Received null NodeDataLoader value for node ID: ${key.id}")
    }

    override suspend fun internalLoad(
        keys: Set<NodeResolverExecutor.Selector>,
        environment: BatchLoaderEnvironment<NodeResolverExecutor.Selector>
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>?> {
        return resolver.resolve(keys.toList(), executionContextForBatchLoadFromKeys(keys, environment))
    }

    override fun shouldUseImmediateDispatch(): Boolean = !resolver.isBatching

    override val cacheKeyFn: CacheKeyFn<NodeResolverExecutor.Selector, NodeResolverExecutor.Selector> get() =
        if (resolver.isSelective) {
            { selector -> selector }
        } else {
            { selector -> NodeResolverExecutor.Selector(selector.id, NonSelectiveCacheMarker) }
        }

    override val cacheKeyMatchFn: CacheKeyMatchFn<NodeResolverExecutor.Selector>? get() =
        if (resolver.isSelective) {
            { newKey, cachedKey -> cachedKey.covers(newKey) }
        } else {
            null
        }
}

/**
 * Returns true if the receiver's selection set covers [other]'s, such that its value can be used
 * instead of executing the resolver with the [other] selector.
 */
internal fun NodeResolverExecutor.Selector.covers(other: NodeResolverExecutor.Selector): Boolean {
    if (other.id != this.id) return false
    val selectedFields = this.selections.selections().mapTo(mutableSetOf("id")) { it.fieldName }
    return other.selections.selections().all { it.fieldName in selectedFields }
}

/**
 * Sentinel used as the selections component of cache keys for non-selective resolvers, so that all
 * loads for the same ID produce an equal Selector and computeIfAbsent deduplicates them atomically.
 * No methods on this object are ever called — it exists only for Selector data-class equality.
 */
@ExcludeFromJacocoGeneratedReport
private object NonSelectiveCacheMarker : EngineSelectionSet {
    override val type: String get() = throw UnsupportedOperationException()
    override val schema: ViaductSchema get() = throw UnsupportedOperationException()

    override fun selections(): List<EngineSelection> = throw UnsupportedOperationException()

    override fun traversableSelections(): List<EngineSelection> = throw UnsupportedOperationException()

    override fun toSelectionSet(): SelectionSet = throw UnsupportedOperationException()

    override fun addVariables(variables: Map<String, Any?>): EngineSelectionSet = throw UnsupportedOperationException()

    override fun toFragment(): Fragment = throw UnsupportedOperationException()

    override fun toNodelikeSelectionSet(
        nodeFieldName: String,
        arguments: List<Argument>
    ): EngineSelectionSet = throw UnsupportedOperationException()

    override fun printAsFieldSet(): String = throw UnsupportedOperationException()

    override fun containsField(
        type: String,
        field: String
    ): Boolean = throw UnsupportedOperationException()

    override fun containsSelection(
        type: String,
        selectionName: String
    ): Boolean = throw UnsupportedOperationException()

    override fun resolveSelection(
        type: String,
        selectionName: String
    ): EngineSelection = throw UnsupportedOperationException()

    override fun requestsType(type: String): Boolean = throw UnsupportedOperationException()

    override fun selectionSetForField(
        type: String,
        field: String
    ): EngineSelectionSet = throw UnsupportedOperationException()

    override fun selectionSetForSelection(
        type: String,
        selectionName: String
    ): EngineSelectionSet = throw UnsupportedOperationException()

    override fun selectionSetForType(type: String): EngineSelectionSet = throw UnsupportedOperationException()

    override fun isEmpty(): Boolean = throw UnsupportedOperationException()

    override fun isTransitivelyEmpty(): Boolean = throw UnsupportedOperationException()

    override fun argumentsOfSelection(
        type: String,
        selectionName: String
    ): Map<String, Any?>? = throw UnsupportedOperationException()
}
