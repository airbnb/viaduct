package viaduct.engine.runtime2.arbitrary

import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.runtime2.model.ResolverOutputData

/**
 * Output preparation for overhead benchmarks, which use constant field outputs.
 *
 * A discovery world records node IDs during an untimed resolution pass. [prepared] snapshots those
 * IDs for subsequent worlds, which eagerly materialize their own canonical node outputs at assembly
 * time. Their callbacks perform only a lookup and fail on an undiscovered ID; they never generate
 * an output on a measured cache miss. The discovery world's engine caches are not reused.
 */
class PrecomputedResolverOutputs private constructor(
    private val nodeIds: Map<String, Set<String>>?,
) {
    constructor() : this(null)

    private val discoveredNodeIds = ConcurrentHashMap<String, MutableSet<String>>()

    fun prepared(): PrecomputedResolverOutputs {
        check(nodeIds == null) { "Node IDs have already been prepared" }
        return PrecomputedResolverOutputs(discoveredNodeIds.mapValues { (_, ids) -> ids.toSet() })
    }

    fun nodeResolver(
        typeName: String,
        materialize: (String) -> ResolverOutputData?,
    ): (String) -> ResolverOutputData? {
        val preparedIds = nodeIds
        if (preparedIds == null) {
            val ids = discoveredNodeIds.computeIfAbsent(typeName) { ConcurrentHashMap.newKeySet() }
            return { id ->
                ids.add(id)
                materialize(id)
            }
        }
        val outputs = preparedIds[typeName].orEmpty().associateWith(materialize)
        return { id -> outputs.getValue(id) }
    }
}
