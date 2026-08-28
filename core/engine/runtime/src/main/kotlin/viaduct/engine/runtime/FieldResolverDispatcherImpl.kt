package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.VariableFromArgumentDefinitions
import viaduct.engine.runtime.EngineExecutionContextExtensions.asImpl

/**
 * Initialized via DispathcerRegistry and resolves a single node for a node type whose
 * tenant-written node resolver implements resolve or batch resolve function.
 *
 * If tenant implements the resolve function, it delegates to a dataloader that only
 * does caching and no batching, which dispatches immediately;
 *
 * If tenant implements the batch resolve function, it delegates to a dataloader that
 * handles batching and caching.
 */
class FieldResolverDispatcherImpl(
    private val resolver: FieldResolverExecutor
) : FieldResolverDispatcher {
    override val objectSelectionSet: RequiredSelectionSet? = resolver.objectSelectionSet?.withArgumentVariables(
        resolver.argumentVariables,
    )

    override val querySelectionSet: RequiredSelectionSet? = resolver.querySelectionSet?.withArgumentVariables(
        resolver.argumentVariables,
    )

    override val isSelective: Boolean = resolver.isSelective

    override val hasRequiredSelectionSets: Boolean = resolver.hasRequiredSelectionSets()

    override val resolverMetadata: ResolverMetadata = resolver.metadata

    override suspend fun resolve(
        arguments: Map<String, Any?>,
        objectValueFactory: EngineObjectDataFactory,
        queryValueFactory: EngineObjectDataFactory,
        selections: EngineSelectionSet?,
        context: EngineExecutionContext,
    ): Any? {
        val impl = context.asImpl()
        val loader = impl.fieldDataLoader(resolver)

        val syncObjectValueGetter: suspend () -> EngineObjectData.Sync = {
            objectValueFactory.create(null)
        }
        val syncQueryValueGetter: suspend () -> EngineObjectData.Sync = {
            queryValueFactory.create(null)
        }

        val selector = FieldResolverExecutor.Selector(
            arguments = arguments,
            selections = selections,
            syncObjectValueGetter = syncObjectValueGetter,
            syncQueryValueGetter = syncQueryValueGetter,
        )

        return loader.loadByKey(selector, context).getOrThrow()
    }

    private fun RequiredSelectionSet.withArgumentVariables(argumentVariables: VariableFromArgumentDefinitions): RequiredSelectionSet {
        if (argumentVariables.variables.isEmpty()) return this

        val argumentVariableNames = argumentVariables.variableNames
        val legacyVariablesResolvers = variablesResolvers.filterNot { variablesResolver ->
            variablesResolver.variableNames.any(argumentVariableNames::contains)
        }
        val argumentVariablesResolvers = VariablesResolver.fromSelectionSetVariables(
            selections,
            selections,
            argumentVariables.variables.map { (name, path) ->
                FromArgumentVariable(name, path)
            },
            forChecker = false,
            attribution,
        )
        return RequiredSelectionSet(
            selections = selections,
            variablesResolvers = legacyVariablesResolvers + argumentVariablesResolvers,
            forChecker = forChecker,
            attribution = attribution,
            executionCondition = executionCondition,
        )
    }
}
