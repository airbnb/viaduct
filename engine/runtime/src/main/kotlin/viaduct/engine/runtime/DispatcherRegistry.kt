package viaduct.engine.runtime

import javax.inject.Singleton
import viaduct.engine.api.Coordinate
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSetRegistry

/**
 * Combined interface for all dispatcher registries.
 *
 * This interface aggregates all the individual registry interfaces that are needed
 * for GraphQL execution. Implementations provide lookup capabilities for:
 * - Field resolvers
 * - Node resolvers
 * - Field checkers
 * - Type checkers
 * - Required selection sets
 *
 * @see Impl for the standard implementation
 */
interface DispatcherRegistry : RequiredSelectionSetRegistry {
    companion object {
        /** A [DispatcherRegistry] that returns null/empty for every request. */
        val Empty: DispatcherRegistry = Impl(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }

    fun getFieldResolverDispatcher(
        typeName: String,
        fieldName: String
    ): FieldResolverDispatcher?

    fun getFieldCheckerDispatcher(
        typeName: String,
        fieldName: String
    ): CheckerDispatcher?

    fun getNodeResolverDispatcher(typeName: String): NodeResolverDispatcher?

    fun getTypeCheckerDispatcher(typeName: String): CheckerDispatcher?

    /**
     * Standard implementation of [DispatcherRegistry].
     *
     * This class holds all dispatcher maps and computes required selection sets from the
     * registered dispatchers.
     */
    @Singleton
    class Impl(
        internal val fieldResolverDispatchers: Map<Coordinate, FieldResolverDispatcher>,
        internal val nodeResolverDispatchers: Map<String, NodeResolverDispatcher>,
        internal val fieldCheckerDispatchers: Map<Coordinate, CheckerDispatcher>,
        internal val typeCheckerDispatchers: Map<String, CheckerDispatcher>
    ) : DispatcherRegistry {
        override fun getFieldResolverDispatcher(
            typeName: String,
            fieldName: String
        ) = fieldResolverDispatchers[Pair(typeName, fieldName)]

        override fun getFieldCheckerDispatcher(
            typeName: String,
            fieldName: String
        ) = fieldCheckerDispatchers[Pair(typeName, fieldName)]

        override fun getNodeResolverDispatcher(typeName: String): NodeResolverDispatcher? = nodeResolverDispatchers[typeName]

        override fun getTypeCheckerDispatcher(typeName: String): CheckerDispatcher? = typeCheckerDispatchers[typeName]

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

        internal fun isEmpty() = fieldResolverDispatchers.isEmpty() && nodeResolverDispatchers.isEmpty() && fieldCheckerDispatchers.isEmpty() && typeCheckerDispatchers.isEmpty()
    }
}
