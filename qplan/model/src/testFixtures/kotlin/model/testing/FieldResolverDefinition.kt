package model.testing

import viaduct.graphql.schema.ViaductSchema

import model.Arguments

import model.Fragment
import model.MaterializeSelection
import model.MaterializeSelectionForest
import model.ResolverOutputData
import model.SelectionForest
import model.materializeSelectionForestOf
import model.objectKey
import model.registry.FieldResolver
import model.registry.NonselectiveFieldResolverFunction
import model.registry.ResolverFragmentTemplates
import model.registry.ResolutionExecutionContext
import model.registry.SelectiveFieldResolverFunction
import model.registry.VariableDefinition
import model.registry.VariablesProviderFunction
import model.merge
import model.selectionForestOf
import model.variableTemplates
import viaduct.engine.api.EngineObjectData

/**
 * A raw field-resolver definition accepted only by test-fixture composition.
 *
 * Definitions may be transformed while external coordinates are lowered. Registry assembly
 * consumes them and exposes only fully assembled canonical [FieldResolver] values.
 */
class FieldResolverDefinition private constructor(
    val objectFragment: Fragment,
    val queryFragment: Fragment?,
    private val function: SelectiveFieldResolverFunction,
    private val selective: Boolean,
    private val passesDemand: Boolean,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
    internal val variablesProviderNames: Set<String>,
    internal val variablesProvider: VariablesProviderFunction?,
) {
    fun mapOutput(
        transform: (ResolverOutputData?) -> ResolverOutputData?,
    ): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment,
            function = { input, queryValue, arguments, selections, executionContext ->
                transform(function(input, queryValue, arguments, selections, executionContext))
            },
            selective = selective,
            passesDemand = passesDemand,
            projectionDemand = projectionDemand,
            variablesProviderNames = variablesProviderNames,
            variablesProvider = variablesProvider,
        )

    fun mapDemand(
        transform: (SelectionForest) -> SelectionForest,
    ): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment,
            function = function,
            selective = selective,
            passesDemand = passesDemand,
            projectionDemand = { demand -> transform(projectionDemand(demand)) },
            variablesProviderNames = variablesProviderNames,
            variablesProvider = variablesProvider,
        )

    /** Routes internally encoded Query.node identities through qplan's built-in node dispatcher. */
    internal fun withNodeDispatch(
        dispatch: suspend (
            Arguments.Resolved,
            SelectionForest,
            ResolutionExecutionContext,
        ) -> ResolverOutputData?,
        isNodeDispatch: (Arguments.Resolved) -> Boolean,
    ): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment,
            function = { input, queryValue, arguments, selections, executionContext ->
                if (isNodeDispatch(arguments)) {
                    dispatch(arguments, selections, executionContext)
                } else {
                    function(input, queryValue, arguments, selections, executionContext)
                }
            },
            selective = true,
            passesDemand = true,
            projectionDemand = { it },
            variablesProviderNames = variablesProviderNames,
            variablesProvider = variablesProvider,
        )

    fun mapObjectFragment(transform: (Fragment) -> Fragment): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = transform(objectFragment),
            queryFragment = queryFragment,
            function = function,
            selective = selective,
            passesDemand = passesDemand,
            projectionDemand = projectionDemand,
            variablesProviderNames = variablesProviderNames,
            variablesProvider = variablesProvider,
        )

    fun mapQueryFragment(transform: (Fragment) -> Fragment): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment?.let(transform),
            function = function,
            selective = selective,
            passesDemand = passesDemand,
            projectionDemand = projectionDemand,
            variablesProviderNames = variablesProviderNames,
            variablesProvider = variablesProvider,
        )

    /** Attaches one occurrence-local provider for the named fragment variables. */
    fun withVariablesProvider(
        variableNames: Set<String>,
        provider: VariablesProviderFunction,
    ): FieldResolverDefinition {
        require(variableNames.isNotEmpty()) { "A variables provider must declare a variable" }
        require(variablesProvider == null) { "A variables provider is already attached" }
        return FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment,
            function = function,
            selective = selective,
            passesDemand = passesDemand,
            projectionDemand = projectionDemand,
            variablesProviderNames = variableNames.toSet(),
            variablesProvider = provider,
        )
    }

    internal fun assemble(
        field: ViaductSchema.ObjectField,
        queryType: ViaductSchema.Object,
        variables: Map<Arguments.Variable, VariableDefinition>,
        validateObjectFragment: (Fragment) -> Unit,
    ): FieldResolver {
        val objectType = field.containingDef

        fun normalize(
            fragment: Fragment,
            expectedType: ViaductSchema.Object,
            role: String,
        ): MaterializeSelectionForest {
            require(fragment.nominalType == expectedType) {
                "$role type ${fragment.nominalType.name} does not match ${expectedType.name}"
            }
            return fragment.materializeSelections.flatMap { selection ->
                if (expectedType !in selection.possibleTypes) {
                    materializeSelectionForestOf()
                } else {
                    materializeSelectionForestOf(
                        MaterializeSelection.of(
                            responseKey = selection.responseKey,
                            key = selection.key.objectKey(expectedType),
                            possibleTypes = setOf(expectedType),
                            inclusionCondition = selection.inclusionCondition,
                            fieldDirectives = selection.fieldDirectives,
                            subselections = selection.subselections,
                        ),
                    )
                }
            }
        }

        validateObjectFragment(objectFragment)
        val normalizedQueryFragment =
            queryFragment?.let { fragment ->
                require(fragment.nominalType == queryType) {
                    "Query fragment type ${fragment.nominalType.name} does not match ${queryType.name}"
                }
                normalize(fragment, queryType, "Query fragment")
            } ?: materializeSelectionForestOf()

        val normalizedObjectFragment = normalize(objectFragment, objectType, "Object fragment")
        val fragmentTemplates =
            ResolverFragmentTemplates(
                objectFragmentTemplate = normalizedObjectFragment,
                queryFragmentTemplate = normalizedQueryFragment,
                variables = variables,
                variablesProvider = variablesProvider,
            )
        return if (selective) {
            FieldResolver.ofSelective(
                field = field,
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
                function = { input, queryValue, arguments, selections, executionContext ->
                    function(
                        input,
                        queryValue,
                        arguments,
                        projectionDemand(selections),
                        executionContext,
                    )
                },
            )
        } else if (passesDemand) {
            FieldResolver.ofSelectionAwareNonselective(
                field = field,
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
                function = { input, queryValue, arguments, selections, executionContext ->
                    function(
                        input,
                        queryValue,
                        arguments,
                        projectionDemand(selections),
                        executionContext,
                    )
                },
            )
        } else {
            FieldResolver.of(
                field = field,
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
                function = { input, queryValue, arguments, executionContext ->
                    function(
                        input,
                        queryValue,
                        arguments,
                        selectionForestOf(),
                        executionContext,
                    )
                },
                projectionDemand = projectionDemand,
            )
        }
    }

    companion object {
        fun of(
            objectFragment: Fragment,
            queryFragment: Fragment?,
            function: NonselectiveFieldResolverFunction,
        ): FieldResolverDefinition =
            FieldResolverDefinition(
                objectFragment = objectFragment,
                queryFragment = queryFragment,
                function = { input, queryValue, arguments, _, executionContext ->
                    function(input, queryValue, arguments, executionContext)
                },
                selective = false,
                passesDemand = false,
                projectionDemand = { it },
                variablesProviderNames = emptySet(),
                variablesProvider = null,
            )

        fun ofSelective(
            objectFragment: Fragment,
            queryFragment: Fragment?,
            function: SelectiveFieldResolverFunction,
        ): FieldResolverDefinition =
            FieldResolverDefinition(
                objectFragment = objectFragment,
                queryFragment = queryFragment,
                function = function,
                selective = true,
                passesDemand = true,
                projectionDemand = { it },
                variablesProviderNames = emptySet(),
                variablesProvider = null,
            )

        fun of(
            objectFragment: Fragment,
            function: suspend (EngineObjectData.Sync, Arguments.Resolved) -> ResolverOutputData?,
        ): FieldResolverDefinition =
            of(
                objectFragment = objectFragment,
                queryFragment = null,
                function = { input, _, arguments, _ -> function(input, arguments) },
            )
        fun ofSelectionAwareNonselective(
            objectFragment: Fragment,
            queryFragment: Fragment?,
            function: SelectiveFieldResolverFunction,
        ): FieldResolverDefinition =
            FieldResolverDefinition(
                objectFragment = objectFragment,
                queryFragment = queryFragment,
                function = function,
                selective = false,
                passesDemand = true,
                projectionDemand = { it },
                variablesProviderNames = emptySet(),
                variablesProvider = null,
            )
        }
}

fun fieldResolverOf(
    objectFragment: Fragment,
    queryFragment: Fragment,
    function: suspend (
        EngineObjectData.Sync,
        EngineObjectData.Sync,
        Arguments.Resolved,
    ) -> ResolverOutputData?,
): FieldResolverDefinition =
    FieldResolverDefinition.of(objectFragment, queryFragment) { input, queryValue, arguments, _ ->
        function(input, queryValue, arguments)
    }

fun fieldResolverOf(
    objectFragment: Fragment,
    queryFragment: Fragment,
    function: NonselectiveFieldResolverFunction,
): FieldResolverDefinition =
    FieldResolverDefinition.of(objectFragment, queryFragment, function)

fun fieldResolverOf(
    objectFragment: Fragment,
    function: suspend (EngineObjectData.Sync, Arguments.Resolved) -> ResolverOutputData?,
): FieldResolverDefinition = FieldResolverDefinition.of(objectFragment, function)

fun fieldResolverOf(
    objectFragment: Fragment,
    function: suspend (
        EngineObjectData.Sync,
        Arguments.Resolved,
        ResolutionExecutionContext,
    ) -> ResolverOutputData?,
): FieldResolverDefinition =
    FieldResolverDefinition.of(
        objectFragment = objectFragment,
        queryFragment = null,
    ) { input, _, arguments, executionContext ->
        function(input, arguments, executionContext)
    }

fun selectiveFieldResolverOf(
    objectFragment: Fragment,
    queryFragment: Fragment,
    function: suspend (
        EngineObjectData.Sync,
        EngineObjectData.Sync,
        Arguments.Resolved,
        SelectionForest,
    ) -> ResolverOutputData?,
): FieldResolverDefinition =
    FieldResolverDefinition.ofSelective(
        objectFragment,
        queryFragment,
    ) { input, queryValue, arguments, selections, _ ->
        function(input, queryValue, arguments, selections)
    }

fun selectiveFieldResolverOf(
    objectFragment: Fragment,
    queryFragment: Fragment,
    function: SelectiveFieldResolverFunction,
): FieldResolverDefinition =
    FieldResolverDefinition.ofSelective(objectFragment, queryFragment, function)

fun selectionAwareFieldResolverOf(
    objectFragment: Fragment,
    queryFragment: Fragment,
    function: suspend (
        EngineObjectData.Sync,
        EngineObjectData.Sync,
        Arguments.Resolved,
        SelectionForest,
    ) -> ResolverOutputData?,
): FieldResolverDefinition =
    FieldResolverDefinition.ofSelectionAwareNonselective(
        objectFragment,
        queryFragment,
    ) { input, queryValue, arguments, selections, _ ->
        function(input, queryValue, arguments, selections)
    }

fun selectionAwareFieldResolverOf(
    objectFragment: Fragment,
    queryFragment: Fragment,
    function: SelectiveFieldResolverFunction,
): FieldResolverDefinition =
    FieldResolverDefinition.ofSelectionAwareNonselective(objectFragment, queryFragment, function)

fun selectiveFieldResolverOf(
    objectFragment: Fragment,
    function: suspend (
        EngineObjectData.Sync,
        Arguments.Resolved,
        SelectionForest,
    ) -> ResolverOutputData?,
): FieldResolverDefinition =
    FieldResolverDefinition.ofSelective(
        objectFragment = objectFragment,
        queryFragment = null,
        function = { input, _, arguments, selections, _ -> function(input, arguments, selections) },
    )
