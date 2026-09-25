package semantics.resolvers

import model.Arguments

import model.ObjectEngineResult

import model.PathComponent
import model.ResolverOccurrenceId
import model.registry.VariableDefinition
import model.registry.ResolverFragments
import semantics.shared.SharedOperationContext

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
