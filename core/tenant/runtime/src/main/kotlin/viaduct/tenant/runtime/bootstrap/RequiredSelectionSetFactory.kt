package viaduct.tenant.runtime.bootstrap

import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import viaduct.api.ResolverBase
import viaduct.api.resolver.Variables
import viaduct.api.resolver.VariablesProvider
import viaduct.api.types.Arguments
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.bootstrap.executionregistry.RequiredSelectionSetSupport
import viaduct.engine.api.checkDisjoint
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.collectVariableReferences
import viaduct.service.api.spi.CodeInjector
import viaduct.tenant.runtime.context.factory.VariablesProviderContextFactory
import viaduct.tenant.runtime.execution.VariablesProviderExecutor
import viaduct.tenant.runtime.internal.VariablesProviderInfo

/** methods for constructing a [RequiredSelectionSet] for a resolver */
object RequiredSelectionSetFactory {
    /**
     * Create a [Pair] of [RequiredSelectionSet]s for the provided parameters with cross-selection-set validation.
     * This method performs validation that ensures VariablesProvider variables are used across both
     * object and query selection sets.
     */
    fun createRequiredSelectionSets(
        variablesProvider: VariablesProviderInfo?,
        objectSelections: ParsedSelections?,
        querySelections: ParsedSelections?,
        variablesProviderContextFactory: VariablesProviderContextFactory,
        variables: List<SelectionSetVariable>,
        attribution: ExecutionAttribution? = null,
    ): Pair<RequiredSelectionSet?, RequiredSelectionSet?> {
        if (objectSelections == null && querySelections == null) {
            return Pair(null, null)
        }

        // Perform cross-selection-set validation for all variables
        val variableConsumers = buildSet {
            objectSelections?.selections?.collectVariableReferences()?.let(::addAll)
            querySelections?.selections?.collectVariableReferences()?.let(::addAll)
        }
        val variableProducers = buildSet {
            variables.forEach { add(it.name) }
            variablesProvider?.variables?.let(::addAll)
        }
        val unusedVariables = variableProducers - variableConsumers
        require(unusedVariables.isEmpty()) {
            "Cannot build required selection sets: found declarations for unused variables: ${unusedVariables.joinToString(", ")}"
        }

        val allVariableResolvers = listOf(
            mkVariablesProviderVariablesResolvers(variablesProvider, variablesProviderContextFactory),
            mkFromAnnotationVariablesResolvers(
                objectSelections,
                querySelections,
                variables,
                attribution = attribution
            ),
        ).flatten()
            .also { it.checkDisjoint() }
            .map { it.validated() }

        return Pair(
            objectSelections?.let {
                RequiredSelectionSet(
                    it,
                    allVariableResolvers,
                    forChecker = false,
                    attribution,
                )
            },
            querySelections?.let {
                RequiredSelectionSet(
                    it,
                    allVariableResolvers,
                    forChecker = false,
                    attribution
                )
            }
        )
    }

    private fun mkVariablesProviderVariablesResolvers(
        variablesProvider: VariablesProviderInfo?,
        variablesProviderContextFactory: VariablesProviderContextFactory,
    ): List<VariablesResolver> =
        listOfNotNull(
            variablesProvider
                ?.let {
                    VariablesProviderExecutor(it, variablesProviderContextFactory)
                }
        )

    private fun mkFromAnnotationVariablesResolvers(
        resolverSelections: ParsedSelections?,
        querySelections: ParsedSelections?,
        vars: List<SelectionSetVariable>,
        attribution: ExecutionAttribution?
    ): List<VariablesResolver> =
        VariablesResolver.fromSelectionSetVariables(
            resolverSelections,
            querySelections,
            vars,
            forChecker = false,
            attribution
        )
}

/**
 * Return a [VariablesProviderInfo] that describes a nested
 * [VariablesProvider] class within the provided [ResolverBase] kclass.
 */
@Suppress("UNCHECKED_CAST")
internal fun KClass<out ResolverBase<*>>.variablesProvider(injector: CodeInjector): VariablesProviderInfo? =
    nestedClasses
        .firstOrNull { it.hasAnnotation<Variables>() }
        ?.let {
            val vars = it.findAnnotations(Variables::class).first()
            val typeMap = vars.asTypeMap()
            require(it.isSubclassOf(VariablesProvider::class)) {
                "Found Variable class $it with @VariableTypes does not implement VariablesProvider"
            }
            it as KClass<VariablesProvider<Arguments>>
            VariablesProviderInfo(typeMap.keys, injector.getProvider(it.java))
        }

/**
 * Parse a [Variables] into a map of types.
 * For example, `@Variables("a:A", "b:B")` will be parsed as `mapOf("a" to "A", "b" to "B")`
 */
internal fun Variables.asTypeMap(): Map<String, String> = RequiredSelectionSetSupport.parseVariableTypeEntries(types.toList())
