package viaduct.engine.runtime2.model.registry

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.graphql.schema.ViaductSchema

/**
 * A source field-resolver definition prepared for canonical registry construction.
 *
 * Definitions may be transformed while external coordinates are lowered. Registry assembly
 * consumes them and exposes only fully assembled canonical [FieldValueResolver] values.
 */
class FieldResolverDefinition private constructor(
    val objectFragment: Fragment,
    val queryFragment: Fragment?,
    private val function: SelectiveFieldResolverFunction,
    internal val variablesProviderNames: Set<String>,
    internal val variablesProvider: VariablesProviderFunction?,
) {
    fun mapOutput(transform: (ResolverOutputData?) -> ResolverOutputData?): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment,
            function = { input, queryValue, arguments, selections, additionalContextForResolver, executionContext ->
                transform(function(input, queryValue, arguments, selections, additionalContextForResolver, executionContext))
            },
            variablesProviderNames = variablesProviderNames,
            variablesProvider = variablesProvider,
        )

    fun mapDemand(transform: (SelectionForest) -> SelectionForest): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment,
            function = { input, queryValue, arguments, selections, context, executionContext ->
                function(input, queryValue, arguments, transform(selections), context, executionContext)
            },
            variablesProviderNames = variablesProviderNames,
            variablesProvider = variablesProvider,
        )

    /** Routes internally encoded Query.node identities through runtime2's built-in node dispatcher. */
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
            function = { input, queryValue, arguments, selections, additionalContextForResolver, executionContext ->
                if (isNodeDispatch(arguments)) {
                    dispatch(arguments, selections, executionContext)
                } else {
                    function(input, queryValue, arguments, selections, additionalContextForResolver, executionContext)
                }
            },
            variablesProviderNames = variablesProviderNames,
            variablesProvider = variablesProvider,
        )

    fun mapObjectFragment(transform: (Fragment) -> Fragment): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = transform(objectFragment),
            queryFragment = queryFragment,
            function = function,
            variablesProviderNames = variablesProviderNames,
            variablesProvider = variablesProvider,
        )

    fun mapQueryFragment(transform: (Fragment) -> Fragment): FieldResolverDefinition =
        FieldResolverDefinition(
            objectFragment = objectFragment,
            queryFragment = queryFragment?.let(transform),
            function = function,
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
            variablesProviderNames = variableNames.toSet(),
            variablesProvider = provider,
        )
    }

    internal fun assemble(
        field: ViaductSchema.ObjectField,
        queryType: ViaductSchema.Object,
        variables: Map<Arguments.Variable, VariableDefinition>,
        validateObjectFragment: (Fragment) -> Unit,
    ): FieldValueResolver {
        val objectType = field.containingDef

        validateObjectFragment(objectFragment)
        val normalizedQueryFragment =
            queryFragment?.normalizeRoot(queryType, "Query fragment") ?: materializeSelectionForestOf()

        val normalizedObjectFragment = objectFragment.normalizeRoot(objectType, "Object fragment")
        val fragmentTemplates =
            ResolverFragmentTemplates(
                objectFragmentTemplate = normalizedObjectFragment,
                queryFragmentTemplate = normalizedQueryFragment,
                variables = variables,
                variablesProvider = variablesProvider,
            )
        return FieldValueResolver.ofSelective(
            field = field,
            fragmentTemplates = fragmentTemplates,
            queryType = queryType,
            function = function,
        )
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
                function = { input, queryValue, arguments, _, additionalContextForResolver, executionContext ->
                    function(input, queryValue, arguments, additionalContextForResolver, executionContext)
                },
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
                function = { input, _, arguments, _, _ -> function(input, arguments) },
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
    FieldResolverDefinition.of(objectFragment, queryFragment) { input, queryValue, arguments, _, _ ->
        function(input, queryValue, arguments)
    }

fun fieldResolverOf(
    objectFragment: Fragment,
    queryFragment: Fragment,
    function: NonselectiveFieldResolverFunction,
): FieldResolverDefinition = FieldResolverDefinition.of(objectFragment, queryFragment, function)

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
    ) { input, _, arguments, _, executionContext ->
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
    ) { input, queryValue, arguments, selections, _, _ ->
        function(input, queryValue, arguments, selections)
    }

fun selectiveFieldResolverOf(
    objectFragment: Fragment,
    queryFragment: Fragment,
    function: SelectiveFieldResolverFunction,
): FieldResolverDefinition = FieldResolverDefinition.ofSelective(objectFragment, queryFragment, function)

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
    ) { input, queryValue, arguments, selections, _, _ ->
        function(input, queryValue, arguments, selections)
    }

fun selectionAwareFieldResolverOf(
    objectFragment: Fragment,
    queryFragment: Fragment,
    function: SelectiveFieldResolverFunction,
): FieldResolverDefinition = FieldResolverDefinition.ofSelectionAwareNonselective(objectFragment, queryFragment, function)

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
        function = { input, _, arguments, selections, _, _ -> function(input, arguments, selections) },
    )
