package viaduct.java.runtime.bridge

import javax.inject.Provider
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSets
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.checkDisjoint
import viaduct.engine.api.select.SelectionsParser
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.collectVariableReferences
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.Variable
import viaduct.java.api.annotations.Variables
import viaduct.java.api.types.Arguments
import viaduct.java.api.variables.VariablesProvider
import viaduct.service.api.spi.CodeInjector

/**
 * Factory for creating [RequiredSelectionSets] from Java [Resolver] annotations.
 *
 * This is the Java equivalent of the Kotlin [viaduct.tenant.runtime.bootstrap.RequiredSelectionSetFactory].
 * It parses the [Resolver.objectValueFragment] and [Resolver.queryValueFragment] properties,
 * converts [Variable] annotations to [SelectionSetVariable] instances, and discovers nested
 * [VariablesProvider] classes (annotated with [Variables]) for dynamic variable provisioning.
 */
class JavaRequiredSelectionSetFactory {
    /**
     * Create a [RequiredSelectionSets] from the provided [Resolver] annotation.
     *
     * @param schema The Viaduct schema containing type definitions
     * @param annotation The @Resolver annotation from the resolver class
     * @param resolverForType The GraphQL type name this resolver is for (e.g., "Person")
     * @param resolverClass The resolver implementation class. Used both for attribution and for
     *        discovering nested [VariablesProvider] classes.
     * @param injector The injector used to obtain instances of the discovered VariablesProvider.
     * @param argumentsClass The Arguments class for the field this resolver targets, or null if
     *        the field has no arguments. Forwarded to the VariablesProvider executor so the
     *        provider receives a typed Arguments instance.
     * @return A [RequiredSelectionSets] containing the parsed object and query selections
     */
    fun mkRequiredSelectionSets(
        schema: ViaductSchema,
        annotation: Resolver,
        resolverForType: String,
        resolverClass: Class<*>,
        injector: CodeInjector,
        argumentsClass: Class<out Arguments>? = null,
    ): RequiredSelectionSets {
        val objectValueFragment = annotation.objectValueFragment
        val queryValueFragment = annotation.queryValueFragment

        val objectSelections = if (objectValueFragment.isNotBlank()) {
            SelectionsParser.parse(resolverForType, objectValueFragment)
        } else {
            null
        }

        val querySelections = if (queryValueFragment.isNotBlank()) {
            SelectionsParser.parse(schema.schema.queryType.name, queryValueFragment)
        } else {
            null
        }

        if (objectSelections == null && querySelections == null) {
            return RequiredSelectionSets.empty()
        }

        val variables = annotation.variables.map { v -> v.toSelectionSetVariable() }
        val variablesProviderExecutor = mkVariablesProviderExecutor(resolverClass, injector, argumentsClass)

        val variableConsumers = buildSet<String> {
            objectSelections?.selections?.collectVariableReferences()?.let(::addAll)
            querySelections?.selections?.collectVariableReferences()?.let(::addAll)
        }
        val variableProducers = buildSet {
            variables.forEach { add(it.name) }
            variablesProviderExecutor?.variableNames?.let(::addAll)
        }
        val unusedVariables = variableProducers - variableConsumers
        require(unusedVariables.isEmpty()) {
            "Cannot build RequiredSelectionSets: found declarations for unused variables: ${unusedVariables.joinToString(", ")}"
        }

        val attribution = ExecutionAttribution.fromResolver(resolverClass.name)
        val variableResolvers = listOfNotNull(variablesProviderExecutor) +
            mkFromAnnotationVariablesResolvers(
                objectSelections,
                querySelections,
                variables,
                attribution
            )
        variableResolvers.checkDisjoint()
        val validatedResolvers = variableResolvers.map { it.validated() }

        return RequiredSelectionSets(
            objectSelections = objectSelections?.let {
                RequiredSelectionSet(
                    it,
                    validatedResolvers,
                    forChecker = false,
                    attribution
                )
            },
            querySelections = querySelections?.let {
                RequiredSelectionSet(
                    it,
                    validatedResolvers,
                    forChecker = false,
                    attribution
                )
            }
        )
    }

    private fun mkFromAnnotationVariablesResolvers(
        objectSelections: ParsedSelections?,
        querySelections: ParsedSelections?,
        variables: List<SelectionSetVariable>,
        attribution: ExecutionAttribution?
    ): List<VariablesResolver> =
        VariablesResolver.fromSelectionSetVariables(
            objectSelections,
            querySelections,
            variables,
            forChecker = false,
            attribution
        )

    /**
     * Discover a nested [VariablesProvider] class on [resolverClass] (annotated with [Variables])
     * and return a [JavaVariablesProviderExecutor] that adapts it to the engine SPI. Returns null
     * if no nested provider is found.
     */
    private fun mkVariablesProviderExecutor(
        resolverClass: Class<*>,
        injector: CodeInjector,
        argumentsClass: Class<out Arguments>?,
    ): JavaVariablesProviderExecutor? {
        val candidate = resolverClass.declaredClasses.firstOrNull { it.isAnnotationPresent(Variables::class.java) }
            ?: return null
        require(VariablesProvider::class.java.isAssignableFrom(candidate)) {
            "Class $candidate is annotated with @Variables but does not implement VariablesProvider"
        }
        val variableNames = candidate.getAnnotation(Variables::class.java).asNameSet()
        @Suppress("UNCHECKED_CAST")
        val provider: Provider<out VariablesProvider<*>> = injector.getProvider(candidate) as Provider<out VariablesProvider<*>>
        return JavaVariablesProviderExecutor(
            variableNames = variableNames,
            provider = provider,
            argumentsClass = argumentsClass,
        )
    }
}

/**
 * Convert a Java [Variable] annotation to a [SelectionSetVariable].
 *
 * Exactly one of fromArgument, fromObjectField, or fromQueryField must be set.
 */
private fun Variable.toSelectionSetVariable(): SelectionSetVariable {
    val objectFieldIsSet = fromObjectField.isNotEmpty()
    val queryFieldIsSet = fromQueryField.isNotEmpty()
    val argIsSet = fromArgument.isNotEmpty()

    val setCount = listOf(objectFieldIsSet, queryFieldIsSet, argIsSet).count { it }

    check(setCount == 1) {
        "Variable named `$name` must set exactly one of `fromObjectField`, `fromQueryField`, or `fromArgument`. " +
            "It set fromObjectField=$fromObjectField, fromQueryField=$fromQueryField, fromArgument=$fromArgument"
    }

    return when {
        objectFieldIsSet -> FromObjectFieldVariable(name, fromObjectField)
        queryFieldIsSet -> FromQueryFieldVariable(name, fromQueryField)
        argIsSet -> FromArgumentVariable(name, fromArgument)
        else -> error("Unreachable: exactly one field should be set")
    }
}

/**
 * Parse a [Variables] annotation into the set of declared variable names.
 * Each entry has the form "name: Type"; whitespace is ignored.
 */
private fun Variables.asNameSet(): Set<String> =
    types
        .filter { it.isNotBlank() }
        .map { entry ->
            val parts = entry.trim().split(":")
            require(parts.size == 2) {
                "Invalid @Variables entry '${entry.trim()}' — expected format 'name: Type'"
            }
            val name = parts[0].trim()
            require(name.isNotEmpty()) {
                "Invalid @Variables entry '${entry.trim()}' — variable name is empty"
            }
            require(parts[1].trim().isNotEmpty()) {
                "Invalid @Variables entry '${entry.trim()}' — variable type is empty"
            }
            name
        }.toSet()
