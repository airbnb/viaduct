package semantics.resolvers.resolver01

import model.ObjectEngineResult
import model.requireField
import semantics.shared.argumentsContainErrorValue
import semantics.shared.applicableGroundSelections
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import semantics.shared.objectFragmentAt

/** Computes sibling dependencies and demand-first ordering for one object occurrence. */
internal class SiblingDependencyLogic(
    private val operation: SharedOperationContext<*>,
    private val oerOccurrence: OEROccurrence,
    private val includeQueryFragments: Boolean = false,
) {
    /** Returns a topological ordering using Kahn's algorithm, with accumulation local to this call. */
    fun order(keys: Set<ObjectEngineResult.GroundKey>): List<ObjectEngineResult.GroundKey> =
        order(keys, emptyList())

    private fun order(
        keys: Set<ObjectEngineResult.GroundKey>,
        ordered: List<ObjectEngineResult.GroundKey>,
    ): List<ObjectEngineResult.GroundKey> {
        if (keys.isEmpty()) return ordered

        val ready = keys.filter { key -> dependenciesOf(key, keys).isEmpty() }.toSet()
        require(ready.isNotEmpty()) {
            "Resolver dependencies on ${keys.first().field.containingDef.name} contain a cycle"
        }
        return order(keys - ready, ordered + ready)
    }

    /** Returns the unresolved sibling keys demanded by the consumer's resolver. */
    private fun dependenciesOf(
        consumer: ObjectEngineResult.GroundKey,
        unresolved: Set<ObjectEngineResult.GroundKey>,
    ): Set<ObjectEngineResult.GroundKey> {
        if (consumer.arguments.argumentsContainErrorValue()) return emptySet()
        require(consumer.field in operation.world.resolverRegistry) {
            "Demanded field ${consumer.field.containingDef.name}/${consumer.field.name} is absent from its source " +
                "and has no registered resolver"
        }
        return unresolved.filter { sibling ->
            sibling != consumer && demandsFromSibling(consumer, sibling)
        }.toSet()
    }

    /** Whether the consumer directly demands this sibling from the fragments rooted in this OER. */
    fun demandsFromSibling(
        consumer: ObjectEngineResult.GroundKey,
        sibling: ObjectEngineResult.GroundKey,
    ): Boolean {
        val field = consumer.field
        val objectType = field.containingDef
        require(sibling.field.containingDef == objectType) {
            "Sibling demand is defined only for fields on the same concrete object type"
        }
        require(operation.world.schema.requireField(objectType.name, sibling.field.name) == sibling.field) {
            "${objectType.name}/${sibling.field.name} is not canonical in this world"
        }
        val resolver = operation.world.resolverRegistry.resolver(field)
        val dependencies =
            if (includeQueryFragments) {
                val fragments =
                    resolver.instantiateFragmentsAt(
                        oerOccurrence.root,
                        oerOccurrence.coordinate(consumer),
                    )
                (fragments.objectFragment.constructionSelections +
                    fragments.queryFragment.constructionSelections)
                    .applicableGroundSelections(operation, objectType)
            } else {
                resolver.objectFragmentAt(
                    operation,
                    oerOccurrence.root,
                    oerOccurrence.coordinate(consumer),
                )
            }
        return sibling in dependencies.groundKeys()
    }
}
