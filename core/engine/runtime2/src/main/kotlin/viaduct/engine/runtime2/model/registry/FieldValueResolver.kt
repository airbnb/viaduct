package viaduct.engine.runtime2.model.registry

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorDataReadException
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.arg
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.instantiateVariables
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.graphql.schema.ViaductSchema

/** A deterministic partial map from resolved object and Query fragments plus arguments to an output value. */
typealias NonselectiveFieldResolverFunction =
    suspend (
        EngineObjectData.Sync,
        EngineObjectData.Sync,
        Arguments.Resolved,
        ResolutionExecutionContext,
    ) -> ResolverOutputData?

/**
 * A deterministic partial map from resolved inputs and output demand to an output value.
 *
 * For fixed non-selection inputs, calls with different demands have the same null, error, simple,
 * list, and concrete-object skeleton and agree at every object-field coordinate selected by both
 * demands. The returned value may omit object fields outside the supplied demand.
 */
typealias SelectiveFieldResolverFunction =
    suspend (
        EngineObjectData.Sync,
        EngineObjectData.Sync,
        Arguments.Resolved,
        SelectionForest,
        ResolutionExecutionContext,
    ) -> ResolverOutputData?

/** Computes all tenant-provided variables once for one resolver-function occurrence. */
typealias VariablesProviderFunction =
    suspend (Arguments.Resolved) -> Map<String, EngineInputData?>

/**
 * The object- and Query-rooted input templates for one resolver function.
 *
 * [variables] and [variablesProvider] are shared by both templates. Equality is undefined.
 */
class ResolverFragmentTemplates(
    val objectFragmentTemplate: MaterializeSelectionForest,
    val queryFragmentTemplate: MaterializeSelectionForest,
    variables: Map<Arguments.Variable, VariableDefinition> = emptyMap(),
    val variablesProvider: VariablesProviderFunction? = null,
) {
    val variables: Map<Arguments.Variable, VariableDefinition> = variables.toMap()

    init {
        val providerVariables =
            variables.filterValues { definition ->
                definition == VariableDefinition.FromProvider
            }
        require((variablesProvider != null) == providerVariables.isNotEmpty()) {
            "A variables provider and its declared variables must be supplied together"
        }
    }
}

/** One instantiated field-resolution input fragment. Equality is undefined. */
class ResolverFragment internal constructor(
    val resolverOccurrenceId: ResolverOccurrenceId,
    val constructionSelections: SelectionForest,
    val variableDefinitions: List<VariableInstanceDefinition>,
    val pathVariableDefinitions: List<InstantiatedFieldPathDefinition>,
)

class ResolverFragments(
    val objectFragment: ResolverFragment,
    val queryFragment: ResolverFragment,
)

/**
 * A field resolver supplied by the reasoning world's external resolver registry.
 *
 * Equality is undefined. Resolver-demand identity is expressed with canonical object fields
 * instead.
 *
 * [objectFragment] is the direct parent-object resolution requirement. [queryFragment] is the
 * independently resolved Query-rooted requirement. Their response-key-preserving
 * [fragmentTemplates] are instantiated by [instantiateObjectMaterializationSelections] and
 * [instantiateQueryMaterializationSelections] only when their inputs are materialized. In a
 * canonical registry entry,
 * [variables] maps every variable template defined by this resolver and used by either fragment
 * to its argument, nonempty alias-free object- or Query-field path, or the resolver's single
 * [variablesProvider].
 *
 * ### Invariant: resolver-fixed-object-fragment-shape
 *
 * [objectFragment] is specialized to the resolver field's concrete parent type. [queryFragment]
 * is specialized to the canonical Query type.
 *
 * ### Invariant: field-resolver-variable-definitions
 *
 * Every variable is defined by this resolver's field. A [VariableDefinition.FromArgument]
 * references one schema-valid input path rooted at an argument belonging to that field. A
 * [VariableDefinition.FromField] supplied by [ProviderFragment.OBJECT] is a valid selection path
 * relative to that field's containing type and is structurally contained by [objectFragment]. A
 * definition supplied by [ProviderFragment.QUERY] satisfies the same path invariant relative to
 * Query and is structurally contained by [queryFragment]. A
 * [VariableDefinition.FromProvider] requires [variablesProvider], and the provider must return
 * exactly the declared provider-variable names for every invocation.
 *
 * Neither input fragment may use a variable on any concrete branch reachable beneath an `@parent`
 * selection. A variable guarded by a concrete branch disjoint from every parent retarget remains
 * valid. Parent traversal itself remains valid for selective and nonselective resolvers.
 */
class FieldValueResolver private constructor(
    val target: ResolverTarget.FieldValueResolverTarget,
    private val fragmentTemplates: ResolverFragmentTemplates,
    private val queryType: ViaductSchema.Object,
    private val function: SelectiveFieldResolverFunction,
    private val projectNonselectiveOutput: Boolean,
    private val projectionDemand: (SelectionForest) -> SelectionForest,
) {
    val variables: Map<Arguments.Variable, VariableDefinition>
        get() = fragmentTemplates.variables

    val variablesProvider: VariablesProviderFunction?
        get() = fragmentTemplates.variablesProvider

    val objectFragment: SelectionForest =
        fragmentTemplates.objectFragmentTemplate.constructionSelections()

    val queryFragment: SelectionForest =
        fragmentTemplates.queryFragmentTemplate.constructionSelections()

    private val objectFieldPathInclusionConditions =
        variables.fieldPathInclusionConditions(
            ProviderFragment.OBJECT,
            fragmentTemplates.objectFragmentTemplate,
        )

    private val queryFieldPathInclusionConditions =
        variables.fieldPathInclusionConditions(
            ProviderFragment.QUERY,
            fragmentTemplates.queryFragmentTemplate,
        )

    /** Instantiates the object-fragment template when its input is ready to be materialized. */
    fun instantiateObjectMaterializationSelections(resolverOccurrenceId: ResolverOccurrenceId): MaterializeSelectionForest =
        fragmentTemplates.objectFragmentTemplate.instantiateVariables(resolverOccurrenceId)

    /** Instantiates the Query-fragment template when its input is ready to be materialized. */
    fun instantiateQueryMaterializationSelections(resolverOccurrenceId: ResolverOccurrenceId): MaterializeSelectionForest =
        fragmentTemplates.queryFragmentTemplate.instantiateVariables(resolverOccurrenceId)

    /** Instantiates both resolver input fragments at one exact resolver path. */
    fun instantiateFragmentsAt(
        root: ObjectEngineResult,
        path: List<PathComponent>,
    ): ResolverFragments = instantiateFragments(ResolverOccurrenceId.at(root, path))

    /** Instantiates both resolver input fragments from one shared occurrence-variable set. */
    fun instantiateFragments(resolverOccurrenceId: ResolverOccurrenceId): ResolverFragments =
        ResolverFragments(
            objectFragment =
                instantiateResolverFragment(
                    resolverOccurrenceId = resolverOccurrenceId,
                    constructionSelections = objectFragment,
                    variables = variables,
                    fieldPathInclusionConditions = objectFieldPathInclusionConditions,
                ),
            queryFragment =
                instantiateResolverFragment(
                    resolverOccurrenceId = resolverOccurrenceId,
                    constructionSelections = queryFragment,
                    variables = variables,
                    fieldPathInclusionConditions = queryFieldPathInclusionConditions,
                ),
        )

    /** Returns each resolver variable definition instantiated once for this application. */
    fun instantiatedVariableDefinitions(resolverOccurrenceId: ResolverOccurrenceId): List<VariableInstanceDefinition> =
        variables.map { (variable, definition) ->
            VariableInstanceDefinition.of(
                variable = variable.instantiate(resolverOccurrenceId),
                definition = definition,
            )
        }

    /** Returns this resolver's from-field path definitions for one application. */
    fun instantiatedFieldPathVariableDefinitions(resolverOccurrenceId: ResolverOccurrenceId): List<InstantiatedFieldPathDefinition> =
        variables.mapNotNull { (variable, definition) ->
            (definition as? VariableDefinition.FromField)?.let {
                val fragment = when (it.providerFragment) {
                    ProviderFragment.OBJECT -> fragmentTemplates.objectFragmentTemplate
                    ProviderFragment.QUERY -> fragmentTemplates.queryFragmentTemplate
                }
                val conditions = it.inclusionConditions(fragment)
                InstantiatedFieldPathDefinition.of(
                    variable = variable.instantiate(resolverOccurrenceId),
                    providerFragment = it.providerFragment,
                    path =
                        it.path.mapIndexed { index, key ->
                            InstantiatedFieldPathElement.of(
                                key = ObjectEngineResult.Key.of(
                                    field = key.field,
                                    arguments = key.arguments.instantiateVariables(key.field, resolverOccurrenceId),
                                ),
                                inclusionCondition = conditions[index].mapVariables { template ->
                                    template.instantiate(resolverOccurrenceId)
                                },
                            )
                        },
                )
            }
        }

    /** Applies this field resolver to the supplied output demand. */
    internal suspend operator fun invoke(
        input: EngineObjectData.Sync,
        arguments: Arguments.Resolved,
        selections: SelectionForest = selectionForestOf(),
        selectiveResolvers: Boolean,
        executionContext: ResolutionExecutionContext,
    ): ResolverOutputData? =
        invoke(
            input = input,
            queryValue = engineObjectDataOf(queryType),
            arguments = arguments,
            selections = selections,
            selectiveResolvers = selectiveResolvers,
            executionContext = executionContext,
        )

    /** Applies this field resolver to the supplied output demand. */
    suspend operator fun invoke(
        input: EngineObjectData.Sync,
        queryValue: EngineObjectData.Sync,
        arguments: Arguments.Resolved,
        selections: SelectionForest = selectionForestOf(),
        selectiveResolvers: Boolean,
        executionContext: ResolutionExecutionContext,
    ): ResolverOutputData? {
        return evaluateRelation(
            input,
            queryValue,
            arguments,
            selections,
            selectiveResolvers,
            executionContext,
        )
    }

    /**
     * Evaluates the deterministic function relation for a semantic judgment.
     *
     * [selectiveResolvers] controls projection of nonselective resolver output to supplied demand.
     *
     * This is not an observed resolver application and establishes no execution-count property.
     */
    suspend fun evaluateRelation(
        input: EngineObjectData.Sync,
        queryValue: EngineObjectData.Sync,
        arguments: Arguments.Resolved,
        selections: SelectionForest,
        selectiveResolvers: Boolean,
        executionContext: ResolutionExecutionContext,
    ): ResolverOutputData? {
        require(queryValue.schemaType == queryType) {
            "Query value type ${queryValue.schemaType.name} does not match ${queryType.name}"
        }
        val output =
            try {
                function(input, queryValue, arguments, selections, executionContext)
            } catch (exception: EngineErrorDataReadException) {
                exception.errorData
            }
        // output.requireArgumentlessObjectFields()
        val selectedOutput =
            if (projectNonselectiveOutput && selectiveResolvers) {
                output.snipToDemand(projectionDemand(selections))
            } else {
                output
            }
        return selectedOutput
    }

    companion object {
        /**
         * Constructs one fully assembled canonical registry entry.
         *
         * External composition is responsible for lowering coordinates and attaching variables
         * before calling this factory.
         */
        fun of(
            field: ViaductSchema.ObjectField,
            fragmentTemplates: ResolverFragmentTemplates,
            queryType: ViaductSchema.Object,
            function: NonselectiveFieldResolverFunction,
            projectionDemand: (SelectionForest) -> SelectionForest = { it },
        ): FieldValueResolver {
            val target = ResolverTarget.FieldValueResolverTarget(field)
            validateFactoryArguments(
                target = target,
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
            )
            return FieldValueResolver(
                target = target,
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
                function = { input, queryValue, arguments, _, executionContext ->
                    function(input, queryValue, arguments, executionContext)
                },
                projectNonselectiveOutput = true,
                projectionDemand = projectionDemand,
            )
        }

        /**
         * Constructs one fully assembled canonical registry entry backed by a selective relation.
         *
         * Unlike [of], this factory passes output demand directly to [function] and does not project
         * the returned value afterward.
         */
        fun ofSelective(
            field: ViaductSchema.ObjectField,
            fragmentTemplates: ResolverFragmentTemplates,
            queryType: ViaductSchema.Object,
            function: SelectiveFieldResolverFunction,
        ): FieldValueResolver {
            val target = ResolverTarget.FieldValueResolverTarget(field)
            validateFactoryArguments(
                target = target,
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
            )
            return FieldValueResolver(
                target = target,
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
                function = function,
                projectNonselectiveOutput = false,
                projectionDemand = { it },
            )
        }

        /**
         * Constructs a resolver whose executor receives demand metadata while its output is still
         * projected according to the nonselective resolver contract.
         *
         * This exists for integration adapters whose executor SPI supplies requested selections
         * to every invocation independently of the executor's selectivity declaration.
         */
        fun ofSelectionAwareNonselective(
            field: ViaductSchema.ObjectField,
            fragmentTemplates: ResolverFragmentTemplates,
            queryType: ViaductSchema.Object,
            function: SelectiveFieldResolverFunction,
            projectionDemand: (SelectionForest) -> SelectionForest = { it },
        ): FieldValueResolver {
            val target = ResolverTarget.FieldValueResolverTarget(field)
            validateFactoryArguments(
                target = target,
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
            )
            return FieldValueResolver(
                target = target,
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
                function = function,
                projectNonselectiveOutput = true,
                projectionDemand = projectionDemand,
            )
        }

        private fun validateFactoryArguments(
            target: ResolverTarget.FieldValueResolverTarget,
            fragmentTemplates: ResolverFragmentTemplates,
            queryType: ViaductSchema.Object,
        ) {
            val field = target.field
            val objectFragment = fragmentTemplates.objectFragmentTemplate
            val queryFragment = fragmentTemplates.queryFragmentTemplate
            require(
                objectFragment.all { selection ->
                    selection.key.field.containingDef == field.containingDef &&
                        selection.possibleTypes == setOf(field.containingDef)
                },
            ) {
                "Object fragment must be specialized to ${field.containingDef.name}"
            }
            require(
                queryFragment.all { selection ->
                    selection.key.field.containingDef == queryType &&
                        selection.possibleTypes == setOf(queryType)
                },
            ) {
                "Query fragment must be specialized to ${queryType.name}"
            }
            require(queryType.name == "Query") {
                "Query fragment type must be Query"
            }
            val inputOwner = "Resolver input for ${field.containingDef.name}/${field.name}"
            objectFragment.requireNoVariablesBeneathParent(inputOwner)
            queryFragment.requireNoVariablesBeneathParent(inputOwner)
            objectFragment.collect(field.containingDef)
            queryFragment.collect(queryType)
            fragmentTemplates.variables.forEach { (variable, definition) ->
                require(variable.isTemplate) {
                    "Resolver registry variables must be templates"
                }
                require(variable.target == target) {
                    "Variable ${variable.variableName} is not defined by a resolver on " +
                        "${field.containingDef.name}/${field.name}"
                }
                when (definition) {
                    VariableDefinition.FromProvider -> Unit
                    is VariableDefinition.FromArgument -> {
                        val argument = definition.argument
                        require(
                            argument.containingDef == target.field &&
                                target.field.arg(argument.name) == argument,
                        ) {
                            "Variable ${variable.variableName} argument ${argument.name} " +
                                "does not belong to ${target.field.containingDef.name}/" +
                                target.field.name
                        }
                    }
                    is VariableDefinition.FromField -> {
                        val fragment =
                            when (definition.providerFragment) {
                                ProviderFragment.OBJECT -> objectFragment
                                ProviderFragment.QUERY -> queryFragment
                            }
                        definition.inclusionConditions(fragment)
                    }
                }
            }
        }
    }
}

private fun ResolverOutputData?.requireArgumentlessObjectFields() {
    when (this) {
        is EngineObjectData.Sync -> {
            getSelections().forEach { selection ->
                val outputField = schemaType.field(selection)
                require(outputField is ViaductSchema.ObjectField) {
                    "Resolver output selection ${schemaType.name}/$selection is not a canonical " +
                        "object field"
                }
                require(outputField.args.isEmpty()) {
                    "Resolver output must not supply argument-bearing field " +
                        "${schemaType.name}/$selection"
                }
                outputValue(selection).requireArgumentlessObjectFields()
            }
        }

        is List<*> -> forEach { value -> value.requireArgumentlessObjectFields() }
    }
}
