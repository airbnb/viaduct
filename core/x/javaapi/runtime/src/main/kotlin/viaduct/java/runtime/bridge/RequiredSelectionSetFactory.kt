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
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.ProviderVariablesAPIData
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlockConfig
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
 * Factory for creating [RequiredSelectionSets] for Java resolvers.
 *
 * This is the Java equivalent of the Kotlin [viaduct.tenant.runtime.bootstrap.RequiredSelectionSetFactory].
 * There are two entry points:
 *  - [mkRequiredSelectionSets] from a build-time [FieldEntryConfig] registry descriptor — the
 *    preferred path used by [viaduct.java.runtime.bootstrap.ViaductJavaExecutorFactory], keeping the
 *    JSON registry as the single source of truth for bootstrap data.
 *  - [mkRequiredSelectionSets] from a runtime [Resolver] annotation — retained for the legacy
 *    classpath-scanning bootstrap (`ModuleBootstrapper`).
 *
 * Both parse the object/query selection fragments, convert their variable declarations to
 * [SelectionSetVariable] instances, and discover nested [VariablesProvider] classes (annotated with
 * [Variables]) for dynamic variable provisioning.
 */
class RequiredSelectionSetFactory {
    /**
     * Create a [RequiredSelectionSets] from a build-time [FieldEntryConfig] registry descriptor.
     *
     * The selection fragments and [Variable] declarations are read from the registry JSON (the same
     * data the APT extractor emitted from the [Resolver] annotation), rather than re-reading the
     * runtime annotation. The nested [VariablesProvider] class, however, is still discovered
     * reflectively from [resolverClass] since it is not represented in the registry.
     *
     * @param schema The Viaduct schema containing type definitions
     * @param entry The field registry descriptor carrying the selection fragments and variables
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
        entry: FieldEntryConfig,
        resolverClass: Class<*>,
        injector: CodeInjector,
        argumentsClass: Class<out Arguments>? = null,
    ): RequiredSelectionSets {
        val objectSelections = entry.objectSelections?.selections
            ?.takeIf { it.isNotBlank() }
            ?.let { SelectionsParser.parse(entry.typeName, it) }
        val querySelections = entry.querySelections?.selections
            ?.takeIf { it.isNotBlank() }
            ?.let { SelectionsParser.parse(schema.schema.queryType.name, it) }

        if (objectSelections == null && querySelections == null) {
            return RequiredSelectionSets.empty()
        }

        val variables = buildVariables(entry.objectSelections, entry.querySelections)
        return build(objectSelections, querySelections, variables, resolverClass, injector, argumentsClass)
    }

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
        return build(objectSelections, querySelections, variables, resolverClass, injector, argumentsClass)
    }

    /**
     * Shared tail for both entry points: discover the nested [VariablesProvider], validate that
     * every declared variable is consumed, build the variable resolvers, and assemble the
     * [RequiredSelectionSets].
     */
    private fun build(
        objectSelections: ParsedSelections?,
        querySelections: ParsedSelections?,
        variables: List<SelectionSetVariable>,
        resolverClass: Class<*>,
        injector: CodeInjector,
        argumentsClass: Class<out Arguments>?,
    ): RequiredSelectionSets {
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

    /**
     * Convert the registry [SelectionsBlockConfig] variable declarations into [SelectionSetVariable]s,
     * mirroring [viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory]'s handling.
     */
    private fun buildVariables(
        objectSelections: SelectionsBlockConfig?,
        querySelections: SelectionsBlockConfig?,
    ): List<SelectionSetVariable> =
        (
            (objectSelections?.variablesProviders ?: emptyList()) +
                (querySelections?.variablesProviders ?: emptyList())
        ).flatMap { providerEntry ->
            providerEntry.providedVariables.keys.map { varName ->
                providerEntry.providerVariablesAPIData.toSelectionSetVariable(varName)
            }
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
     * and return a [VariablesProviderExecutorImpl] that adapts it to the engine SPI. Returns null
     * if no nested provider is found.
     */
    private fun mkVariablesProviderExecutor(
        resolverClass: Class<*>,
        injector: CodeInjector,
        argumentsClass: Class<out Arguments>?,
    ): VariablesProviderExecutorImpl? {
        val candidate = resolverClass.declaredClasses.firstOrNull { it.isAnnotationPresent(Variables::class.java) }
            ?: return null
        require(VariablesProvider::class.java.isAssignableFrom(candidate)) {
            "Class $candidate is annotated with @Variables but does not implement VariablesProvider"
        }
        val variableNames = candidate.getAnnotation(Variables::class.java).asNameSet()
        @Suppress("UNCHECKED_CAST")
        val provider: Provider<out VariablesProvider<*>> = injector.getProvider(candidate) as Provider<out VariablesProvider<*>>
        return VariablesProviderExecutorImpl(
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
 * Convert a registry [ProviderVariablesAPIData] entry to a [SelectionSetVariable].
 *
 * Mirrors the same conversion in [viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory].
 */
private fun ProviderVariablesAPIData.toSelectionSetVariable(varName: String): SelectionSetVariable =
    when (type) {
        "fromArgument" -> FromArgumentVariable(varName, path)
        "fromObjectField" -> FromObjectFieldVariable(varName, path)
        "fromQueryField" -> FromQueryFieldVariable(varName, path)
        else -> error("Unknown variable provider type '$type' for variable '$varName'")
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
