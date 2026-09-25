package model.registry

import model.Arguments
import model.InclusionCondition
import model.MaterializeSelection
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.SelectionForest
import model.arg
import model.mapVariableTemplates
import model.materializeSelectionForestOf
import model.selectionForestOf
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/** The materialized object- and Query-rooted inputs for one named checker fragment pair. */
class CheckerInput(
    val objectValue: EngineObjectData.Sync,
    val queryValue: EngineObjectData.Sync,
)

/** Executes one field checker from its resolved arguments and named materialized input pairs. */
typealias FieldCheckerFunction =
    suspend (
        Arguments.Resolved,
        Map<String, CheckerInput>,
        ResolutionExecutionContext,
    ) -> CheckerResult

/**
 * A field checker supplied by the reasoning world's external resolver registry.
 *
 * Keep this model shaped like [FieldResolver] except where their semantics require a difference.
 * A resolver owns one [ResolverFragmentTemplates] pair; a checker owns a named map of pairs because
 * its function receives one independently materialized [CheckerInput] for each name. For
 * resolution, both expose exactly one combined object fragment and one combined Query fragment and
 * instantiate them as [ResolverFragments]. A checker returns [CheckerResult] rather than producing
 * the checked field's value.
 *
 * Each named pair retains its external variable names for materialization. Before resolution, the
 * checker prefixes those names with the pair name so equal variable names in different pairs remain
 * independent. The variables of one pair are shared by its object and Query templates, so a
 * variable supplied from either root may be used by selections in either template.
 */
class FieldChecker private constructor(
    val field: ViaductSchema.ObjectField,
    val fragmentTemplates: Map<String, ResolverFragmentTemplates>,
    private val queryType: ViaductSchema.Object,
    private val function: FieldCheckerFunction,
) {
    private val loweredObjectFragment =
        fragmentTemplates.lowerForResolution(ProviderFragment.OBJECT)

    private val loweredQueryFragment =
        fragmentTemplates.lowerForResolution(ProviderFragment.QUERY)

    /** The combined object-rooted fragment used for field-resolution demand. */
    val objectFragment: SelectionForest
        get() = loweredObjectFragment.constructionSelections

    /** The combined Query-rooted fragment used for field-resolution demand. */
    val queryFragment: SelectionForest
        get() = loweredQueryFragment.constructionSelections

    /** Instantiates both combined resolution fragments at one exact checker path. */
    fun instantiateFragmentsAt(
        root: ObjectEngineResult,
        path: List<PathComponent>,
    ): ResolverFragments = instantiateFragments(ResolverOccurrenceId.at(root, path))

    /** Instantiates the checker's combined resolution fragments for one field occurrence. */
    fun instantiateFragments(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): ResolverFragments =
        ResolverFragments(
            objectFragment =
                instantiateResolverFragment(
                    resolverOccurrenceId = resolverOccurrenceId,
                    constructionSelections = objectFragment,
                    variables = loweredObjectFragment.variables,
                    fieldPathInclusionConditions =
                        loweredObjectFragment.fieldPathInclusionConditions,
                ),
            queryFragment =
                instantiateResolverFragment(
                    resolverOccurrenceId = resolverOccurrenceId,
                    constructionSelections = queryFragment,
                    variables = loweredQueryFragment.variables,
                    fieldPathInclusionConditions =
                        loweredQueryFragment.fieldPathInclusionConditions,
                ),
        )

    /** Instantiates each named object template without combining its response-key namespace. */
    fun instantiateObjectMaterializationSelections(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): Map<String, MaterializeSelectionForest> =
        fragmentTemplates.mapValues { (name, templates) ->
            templates
                .lowerForResolution(name)
                .objectFragmentTemplate
                .instantiateVariables(resolverOccurrenceId)
        }

    /** Instantiates each named Query template without combining its response-key namespace. */
    fun instantiateQueryMaterializationSelections(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): Map<String, MaterializeSelectionForest> =
        fragmentTemplates.mapValues { (name, templates) ->
            templates
                .lowerForResolution(name)
                .queryFragmentTemplate
                .instantiateVariables(resolverOccurrenceId)
        }

    suspend operator fun invoke(
        arguments: Arguments.Resolved,
        inputs: Map<String, CheckerInput>,
        executionContext: ResolutionExecutionContext,
    ): CheckerResult = function(arguments, inputs, executionContext)

    companion object {
        fun of(
            field: ViaductSchema.ObjectField,
            queryType: ViaductSchema.Object,
            fragmentTemplates: Map<String, ResolverFragmentTemplates> = emptyMap(),
            function: FieldCheckerFunction,
        ): FieldChecker {
            require(queryType.name == "Query") { "Checker Query type must be Query" }
            fragmentTemplates.values.forEach { templates ->
                require(
                    templates.objectFragmentTemplate.all { selection ->
                        selection.key.field.containingDef == field.containingDef &&
                            selection.possibleTypes == setOf(field.containingDef)
                    },
                ) {
                    "Checker object fragment template must be rooted at " +
                        field.containingDef.name
                }
                require(
                    templates.queryFragmentTemplate.all { selection ->
                        selection.key.field.containingDef == queryType &&
                            selection.possibleTypes == setOf(queryType)
                    },
                ) {
                    "Checker Query fragment template must be rooted at Query"
                }
                templates.objectFragmentTemplate.collect(field.containingDef)
                templates.queryFragmentTemplate.collect(queryType)
                templates.requireVariablesBelongTo(field)
            }
            return FieldChecker(
                field = field,
                fragmentTemplates = fragmentTemplates.toMap(),
                queryType = queryType,
                function = function,
            )
        }
    }
}

private class LoweredCheckerFragment(
    val constructionSelections: SelectionForest,
    val variables: Map<Arguments.Variable, VariableDefinition>,
    val fieldPathInclusionConditions: Map<Arguments.Variable, List<InclusionCondition>>,
)

private fun Map<String, ResolverFragmentTemplates>.lowerForResolution(
    fragmentRoot: ProviderFragment,
): LoweredCheckerFragment {
    var constructionSelections = selectionForestOf()
    val variables = linkedMapOf<Arguments.Variable, VariableDefinition>()
    val fieldPathInclusionConditions =
        linkedMapOf<Arguments.Variable, List<InclusionCondition>>()
    forEach { (name, templates) ->
        val lowered = templates.lowerForResolution(name)
        val materializeSelections =
            when (fragmentRoot) {
                ProviderFragment.OBJECT -> lowered.objectFragmentTemplate
                ProviderFragment.QUERY -> lowered.queryFragmentTemplate
            }
        constructionSelections += materializeSelections.constructionSelections()
        lowered.variables.forEach { (variable, definition) ->
            check(variables.put(variable, definition) == null) {
                "Checker resolution variable was lowered twice: ${variable.variableName}"
            }
            if (
                definition is VariableDefinition.FromField &&
                    definition.providerFragment == fragmentRoot
            ) {
                fieldPathInclusionConditions[variable] =
                    definition.inclusionConditions(materializeSelections)
            }
        }
    }
    return LoweredCheckerFragment(
        constructionSelections = constructionSelections,
        variables = variables,
        fieldPathInclusionConditions = fieldPathInclusionConditions,
    )
}

private fun ResolverFragmentTemplates.lowerForResolution(
    fragmentName: String,
): ResolverFragmentTemplates {
    fun lower(variable: Arguments.Variable): Arguments.Variable {
        require(variable.isTemplate) {
            "Checker fragment templates may contain only variable templates"
        }
        return Arguments.Variable.of(
            variable.field,
            loweredCheckerVariableName(fragmentName, variable.variableName),
        )
    }

    return ResolverFragmentTemplates(
        objectFragmentTemplate = objectFragmentTemplate.mapVariableTemplates(::lower),
        queryFragmentTemplate = queryFragmentTemplate.mapVariableTemplates(::lower),
        variables =
            variables.map { (variable, definition) ->
                lower(variable) to definition.mapVariableTemplates(::lower)
            }.toMap(),
        variablesProvider = variablesProvider,
    )
}

private fun loweredCheckerVariableName(
    fragmentName: String,
    variableName: String,
): String = "$fragmentName:$variableName"

private fun MaterializeSelectionForest.mapVariableTemplates(
    transform: (Arguments.Variable) -> Arguments.Variable,
): MaterializeSelectionForest =
    flatMap { selection ->
        materializeSelectionForestOf(
            MaterializeSelection.of(
                responseKey = selection.responseKey,
                key =
                    ObjectEngineResult.Key.of(
                        selection.key.field,
                        selection.key.arguments.mapVariableTemplates(
                            selection.key.field,
                            transform,
                        ),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.mapVariableTemplates(transform),
                inclusionCondition = selection.inclusionCondition.mapVariables(transform),
                fieldDirectives = selection.fieldDirectives,
            ),
        )
    }

private fun VariableDefinition.mapVariableTemplates(
    transform: (Arguments.Variable) -> Arguments.Variable,
): VariableDefinition =
    when (this) {
        VariableDefinition.FromProvider -> this
        is VariableDefinition.FromArgument -> this
        is VariableDefinition.FromField ->
            VariableDefinition.FromField.of(
                providerFragment = providerFragment,
                path =
                    path.map { key ->
                        ObjectEngineResult.Key.of(
                            key.field,
                            key.arguments.mapVariableTemplates(key.field, transform),
                        )
                    },
                responsePath = responsePath,
            )
    }

private fun ResolverFragmentTemplates.requireVariablesBelongTo(
    field: ViaductSchema.ObjectField,
) {
    variables.forEach { (variable, definition) ->
        require(variable.isTemplate) {
            "Checker registry variables must be templates"
        }
        require(variable.field == field) {
            "Variable ${variable.variableName} is not defined by a checker on " +
                "${field.containingDef.name}/${field.name}"
        }
        when (definition) {
            VariableDefinition.FromProvider -> Unit
            is VariableDefinition.FromArgument -> {
                val argument = definition.argument
                require(argument.containingDef == field && field.arg(argument.name) == argument) {
                    "Variable ${variable.variableName} argument ${argument.name} does not belong " +
                        "to ${field.containingDef.name}/${field.name}"
                }
            }
            is VariableDefinition.FromField -> {
                val providerTemplate =
                    when (definition.providerFragment) {
                        ProviderFragment.OBJECT -> objectFragmentTemplate
                        ProviderFragment.QUERY -> queryFragmentTemplate
                    }
                definition.inclusionConditions(providerTemplate)
            }
        }
    }
}
