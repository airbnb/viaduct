package viaduct.tenant.runtime.bootstrap

import graphql.language.FragmentDefinition
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import viaduct.api.ResolverBase
import viaduct.api.internal.ReflectionLoader
import viaduct.api.resolver.Resolver
import viaduct.api.resolver.Variable
import viaduct.api.resolver.Variables
import viaduct.api.resolver.VariablesProvider
import viaduct.api.types.Arguments
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.checkDisjoint
import viaduct.engine.api.select.SelectionsParser
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.collectVariableReferences
import viaduct.service.api.spi.CodeInjector
import viaduct.tenant.runtime.context.factory.VariablesProviderContextFactory
import viaduct.tenant.runtime.execution.VariablesProviderExecutor
import viaduct.tenant.runtime.internal.VariablesProviderInfo

/** methods for constructing a [RequiredSelectionSet] for a resolver */
class RequiredSelectionSetFactory(
    private val reflectionLoader: ReflectionLoader,
) {
    /**
     * Create a [RequiredSelectionSet] for the provided parameters.
     * Classes and objects provided to this method are expected to be well-formed objects
     * that match what the viaduct code generator produces.
     */
    fun createRequiredSelectionSets(
        schema: ViaductSchema,
        injector: CodeInjector,
        resolverCls: KClass<out ResolverBase<*>>,
        variablesProviderContextFactory: VariablesProviderContextFactory,
        annotation: Resolver,
        resolverForType: String,
        // Classic (reflection-based) bootstrapper only: fragment definitions discovered via
        // classpath scanning of @GraphQLFragment objects. Empty for the KSP/codegen path,
        // where named fragments are inlined into selections strings at assembly time.
        namedFragments: Map<String, FragmentDefinition> = emptyMap(),
    ): Pair<RequiredSelectionSet?, RequiredSelectionSet?> {
        val objectValueFragment = annotation.objectValueFragment
        val queryValueFragment = annotation.queryValueFragment

        // Parse selections
        val objectSelections = if (!objectValueFragment.isBlank()) {
            SelectionsParser.parse(resolverForType, objectValueFragment, namedFragments)
        } else {
            null
        }

        val querySelections = if (!queryValueFragment.isBlank()) {
            SelectionsParser.parse(schema.schema.queryType.name, queryValueFragment, namedFragments)
        } else {
            null
        }

        return createRequiredSelectionSets(
            variablesProvider = resolverCls.variablesProvider(injector),
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = annotation.selectionSetVariables,
            attribution = ExecutionAttribution.fromResolver(resolverCls.qualifiedName!!)
        )
    }

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

/** parse a [Resolver]'s variables into a list of [SelectionSetVariable] */
private val Resolver.selectionSetVariables: List<SelectionSetVariable>
    get() {
        if (variables.isNotEmpty()) {
            check(objectValueFragment != "" || queryValueFragment != "") {
                "@Resolver: cannot use a variable without an `objectValueFragment` or `queryValueFragment`"
            }
        }
        return variables.map {
            val objectFieldIsSet = it.fromObjectField != Variable.UNSET_STRING_VALUE
            val queryFieldIsSet = it.fromQueryField != Variable.UNSET_STRING_VALUE
            val argIsSet = it.fromArgument != Variable.UNSET_STRING_VALUE

            val setFields = listOf(objectFieldIsSet, queryFieldIsSet, argIsSet)
            val setCount = setFields.count { inner -> inner }

            check(setCount == 1) {
                "Variable named `${it.name}` must set exactly one of `fromObjectField`, `fromQueryField`, or `fromArgument`. " +
                    "It set fromObjectField=${it.fromObjectField}, fromQueryField=${it.fromQueryField}, fromArgument=${it.fromArgument}"
            }

            when {
                objectFieldIsSet -> FromObjectFieldVariable(it.name, it.fromObjectField)
                queryFieldIsSet -> FromQueryFieldVariable(it.name, it.fromQueryField)
                argIsSet -> FromArgumentVariable(it.name, it.fromArgument)
                else -> error("Unreachable: exactly one field should be set")
            }
        }
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
internal fun Variables.asTypeMap(): Map<String, String> =
    types
        .filter { it.isNotBlank() }
        .associate {
            val parts = it.trim().split(":")
            require(parts.size == 2) {
                "Invalid @Variables entry '${it.trim()}' — expected format 'name: Type'"
            }
            val first = parts[0].trim().also { name ->
                require(name.isNotEmpty()) {
                    "Invalid @Variables entry '${it.trim()}' — variable name is empty"
                }
            }
            val second = parts[1].trim().also { type ->
                require(type.isNotEmpty()) {
                    "Invalid @Variables entry '${it.trim()}' — variable type is empty"
                }
            }
            first to second
        }
