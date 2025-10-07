package viaduct.engine.runtime

import javax.inject.Singleton
import viaduct.engine.api.CheckerDispatcher
import viaduct.engine.api.Coordinate
import viaduct.engine.api.FieldCheckerDispatcherRegistry
import viaduct.engine.api.FieldResolverDispatcher
import viaduct.engine.api.FieldResolverDispatcherRegistry
import viaduct.engine.api.NodeResolverDispatcher
import viaduct.engine.api.NodeResolverDispatcherRegistry
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.TypeCheckerDispatcherRegistry

@Singleton
class DispatcherRegistry(
    internal val fieldResolverDispatchers: Map<Coordinate, FieldResolverDispatcher>,
    internal val nodeResolverDispatchers: Map<String, NodeResolverDispatcher>,
    internal val fieldCheckerDispatchers: Map<Coordinate, CheckerDispatcher>,
    internal val typeCheckerDispatchers: Map<String, CheckerDispatcher>
) : RequiredSelectionSetRegistry, NodeResolverDispatcherRegistry, TypeCheckerDispatcherRegistry, FieldResolverDispatcherRegistry, FieldCheckerDispatcherRegistry {
    override fun getFieldResolverDispatcher(
        typeName: String,
        fieldName: String
    ) = fieldResolverDispatchers[Pair(typeName, fieldName)]

    override fun getFieldCheckerRequiredSelectionSets(
        typeName: String,
        fieldName: String,
        executeAccessChecksInModstrat: Boolean
    ): List<RequiredSelectionSet> {
        val fieldResolverExecutor = getFieldResolverDispatcher(typeName, fieldName)
        if (!executeAccessChecksInModstrat && fieldResolverExecutor == null) {
            return emptyList()
        }
        val checkerRss = getFieldCheckerDispatcher(typeName, fieldName)?.requiredSelectionSets?.values?.filterNotNull()
        if (checkerRss.isNullOrEmpty()) return emptyList()
        return checkerRss
    }

    override fun getFieldResolverRequiredSelectionSets(
        typeName: String,
        fieldName: String
    ): List<RequiredSelectionSet> {
        val executor = getFieldResolverDispatcher(typeName, fieldName)
            ?: return emptyList()
        if (executor.objectSelectionSet == null && executor.querySelectionSet == null) {
            return emptyList()
        }
        return buildList {
            executor.objectSelectionSet?.let { add(it) }
            executor.querySelectionSet?.let { add(it) }
        }
    }

    override fun getTypeCheckerRequiredSelectionSets(
        typeName: String,
        executeAccessChecksInModstrat: Boolean
    ): List<RequiredSelectionSet> =
        buildList {
            if (executeAccessChecksInModstrat) {
                getTypeCheckerDispatcher(typeName)?.requiredSelectionSets?.values?.filterNotNull()?.let { addAll(it) }
            }
        }

    override fun getFieldCheckerDispatcher(
        typeName: String,
        fieldName: String
    ) = fieldCheckerDispatchers[Pair(typeName, fieldName)]

    override fun getNodeResolverDispatcher(typeName: String): NodeResolverDispatcher? = nodeResolverDispatchers[typeName]

    override fun getTypeCheckerDispatcher(typeName: String): CheckerDispatcher? = typeCheckerDispatchers[typeName]

    // Visible for testing
    fun get() = fieldResolverDispatchers

    companion object {
        /** An [DispatcherRegistry] that returns null for every request. */
        val Empty = DispatcherRegistry(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }

    fun isEmpty() = fieldResolverDispatchers.isEmpty() && nodeResolverDispatchers.isEmpty() && fieldCheckerDispatchers.isEmpty() && typeCheckerDispatchers.isEmpty()
}
