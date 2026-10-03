package viaduct.engine.runtime2.resolvers

import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.registry.ResolverFragments
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/**
 * Declares and immediately completes every argument-defined variable belonging to these resolver
 * occurrences.
 *
 * The exact resolver key completes the containing-object [path], so argument-distinct occurrences
 * of one resolver field define distinct variable instances. Every occurrence must be declared and
 * completed exactly once.
 */
internal fun Iterable<ObjectEngineResult.GroundKey>.bindFromArguments(
    operation: SharedOperationContext<*>,
    root: ObjectEngineResult,
    path: List<PathComponent>,
) {
    forEach { key ->
        if (key.field !in operation.world.resolverRegistry) return@forEach
        val arguments = key.arguments as? Arguments.Resolved ?: return@forEach

        operation.world.resolverRegistry
            .resolver(key.field)
            .variables
            .forEach { (variable, definition) ->
                if (definition is VariableDefinition.FromArgument) {
                    val instantiated =
                        variable.instantiate(
                            ResolverOccurrenceId.at(root, path + key),
                        )
                    val variableId = requireNotNull(instantiated.instanceId)
                    val value = definition.read(arguments)
                    operation.variableBindings.declareBinding(variableId)
                    check(operation.variableBindings.completeBinding(variableId, value)) {
                        "Resolver variable binding was completed twice"
                    }
                }
            }
    }
}

/** Declares and completes argument-defined variables used by one instantiated fragment pair. */
internal fun ResolverFragments.bindFromArguments(
    operation: SharedOperationContext<*>,
    arguments: Arguments.Resolved,
) {
    (objectFragment.variableDefinitions + queryFragment.variableDefinitions)
        .distinctBy { definition -> definition.variable }
        .forEach { variableDefinition ->
            val definition = variableDefinition.definition
            if (definition is VariableDefinition.FromArgument) {
                val variableId = requireNotNull(variableDefinition.variable.instanceId)
                operation.variableBindings.declareBinding(variableId)
                check(operation.variableBindings.completeBinding(variableId, definition.read(arguments))) {
                    "Fragment variable binding was completed twice"
                }
            }
        }
}
