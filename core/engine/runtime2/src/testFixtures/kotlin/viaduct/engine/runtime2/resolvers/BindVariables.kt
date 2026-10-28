@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolvers

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.registry.ResolverFragments
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

fun Iterable<ObjectEngineResult.GroundKey>.bindResolverVariables(
    operation: SharedOperationContext<*>,
    root: ObjectEngineResult,
    path: List<PathComponent>,
    providerContext: CoroutineContext? = null,
) {
    bindFromArguments(operation, root, path)
    forEach { key ->
        val arguments = key.arguments as? Arguments.Resolved ?: return@forEach
        val resolver = operation.world.resolverRegistry.resolver(key.field)
        val provider = resolver.variablesProvider ?: return@forEach
        val definitions =
            resolver.instantiatedVariableDefinitions(ResolverOccurrenceId.at(root, path + key))
                .filter { it.definition == VariableDefinition.FromProvider }
        definitions.forEach { operation.variableBindings.declareBinding(requireNotNull(it.variable.instanceId)) }
        val expectedNames = definitions.mapTo(linkedSetOf()) { it.variable.variableName }
        val context = providerContext ?: (operation as? GroundedVariablesProviderContext)?.variablesProviderContext ?: EmptyCoroutineContext
        val values =
            try {
                runBlocking(context.minusKey(ContinuationInterceptor)) {
                    provider(arguments).also { values ->
                        require(values.keys == expectedNames) { "VariablesProvider returned invalid variables" }
                    }
                }
            } catch (exception: Exception) {
                definitions.forEach {
                    operation.variableBindings.completeBinding(requireNotNull(it.variable.instanceId), VariableBinding.Error)
                }
                context.ensureActive()
                return@forEach
            }
        definitions.forEach {
            check(operation.variableBindings.completeBinding(requireNotNull(it.variable.instanceId), values.getValue(it.variable.variableName))) {
                "Resolver variable binding was completed twice"
            }
        }
    }
}

internal fun ResolverFragments.variablesProviderFailed(operation: SharedOperationContext<*>): Boolean =
    (objectFragment.variableDefinitions + queryFragment.variableDefinitions).any {
        it.definition == VariableDefinition.FromProvider &&
            operation.variableBindings.getBinding(requireNotNull(it.variable.instanceId)) == VariableBinding.Error
    }

/**
 * Declares and immediately completes every argument-defined variable belonging to these resolver
 * occurrences.
 *
 * The exact resolver key completes the containing-object [path], so argument-distinct occurrences
 * of one resolver field define distinct variable instances. Every occurrence must be declared and
 * completed exactly once.
 */
fun Iterable<ObjectEngineResult.GroundKey>.bindFromArguments(
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
fun ResolverFragments.bindFromArguments(
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
