package viaduct.engine.runtime2.model.registry

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MaterializeSelection
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.arg
import viaduct.engine.runtime2.model.mapVariableTemplates
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.render
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.usedVariables
import viaduct.graphql.schema.ViaductSchema

/** The materialized object- and Query-rooted inputs for one named checker fragment pair. */
class CheckerInput(
    val objectValue: EngineObjectData.Sync,
    val queryValue: EngineObjectData.Sync,
)

/**
 * Common fragment lowering, instantiation, and variable provisioning for access checkers.
 *
 * Each named pair retains its external variable names for materialization. Before resolution, the
 * checker prefixes those names with the pair name so equal variable names in different pairs remain
 * independent. The variables of one pair are shared by its object and Query templates, so a
 * variable supplied from either root may be used by selections in either template.
 */
abstract class CheckerResolverBase<out T : ResolverTarget> internal constructor(
    val target: T,
    fragmentTemplates: Map<String, ResolverFragmentTemplates>,
    queryType: ViaductSchema.Object,
) {
    val fragmentTemplates: Map<String, ResolverFragmentTemplates> = fragmentTemplates.toMap()

    init {
        val objectType = target.checkerObjectType
        val inputOwner = target.checkerInputOwner
        require(queryType.name == "Query") { "Checker Query type must be Query" }
        this.fragmentTemplates.values.forEach { templates ->
            require(
                templates.objectFragmentTemplate.all { selection ->
                    selection.key.field.containingDef == objectType &&
                        selection.possibleTypes == setOf(objectType)
                },
            ) {
                "Checker object fragment template must be rooted at ${objectType.name}"
            }
            require(
                templates.queryFragmentTemplate.all { selection ->
                    selection.key.field.containingDef == queryType &&
                        selection.possibleTypes == setOf(queryType)
                },
            ) {
                "Checker Query fragment template must be rooted at Query"
            }
            templates.objectFragmentTemplate.collect(objectType)
            templates.queryFragmentTemplate.collect(queryType)
            templates.requireVariablesBelongTo(target)
            templates.requireCheckerVariableDependencies()
            templates.objectFragmentTemplate.requireNoVariablesBeneathParent(inputOwner)
            templates.queryFragmentTemplate.requireNoVariablesBeneathParent(inputOwner)
        }
    }

    private val loweredNamedTemplates =
        this.fragmentTemplates.mapValues { (name, templates) -> templates.lowerForResolution(name) }

    private val providerVariableNames =
        this.fragmentTemplates.mapValues { (_, templates) ->
            templates.variables
                .filterValues { it == VariableDefinition.FromProvider }
                .keys
                .mapTo(linkedSetOf()) { it.variableName }
        }

    private val loweredObjectFragment =
        loweredNamedTemplates.combineForResolution(ProviderFragment.OBJECT, target.checkerObjectType)

    private val loweredQueryFragment =
        loweredNamedTemplates.combineForResolution(ProviderFragment.QUERY, queryType)

    internal val constructionDemandTemplate: CheckerConstructionDemandTemplate? =
        if (
            when (target) {
                is ResolverTarget.FieldCheckerTarget -> variables.values.all { it is VariableDefinition.FromArgument }
                is ResolverTarget.TypeCheckerTarget -> variables.isEmpty()
                else -> false
            }
        ) {
            val (staticObject, dynamicObject) = objectFragment.partitionVariableDemand()
            val (staticQuery, dynamicQuery) = queryFragment.partitionVariableDemand()
            CheckerConstructionDemandTemplate(staticObject, dynamicObject, staticQuery, dynamicQuery)
        } else {
            null
        }

    /** The lowered variable definitions shared by the combined resolution fragments. */
    val variables: Map<Arguments.Variable, VariableDefinition>
        get() = loweredObjectFragment.variables

    /** The combined object-rooted fragment used for checker-resolution demand. */
    val objectFragment: ObjectSelectionForest
        get() = loweredObjectFragment.constructionSelections

    /** The combined Query-rooted fragment used for checker-resolution demand. */
    val queryFragment: ObjectSelectionForest
        get() = loweredQueryFragment.constructionSelections

    /** Instantiates both combined resolution fragments at one exact checker path. */
    fun instantiateFragmentsAt(
        root: ObjectEngineResult,
        path: List<PathComponent>,
    ): ResolverFragments = instantiateFragments(ResolverOccurrenceId.at(root, path))

    /** Instantiates the checker's combined resolution fragments for one checker occurrence. */
    fun instantiateFragments(resolverOccurrenceId: ResolverOccurrenceId): ResolverFragments =
        ResolverFragments(
            objectFragment =
                instantiateResolverFragment(
                    resolverOccurrenceId = resolverOccurrenceId,
                    constructionSelections = objectFragment,
                    usedVariableTemplates = loweredObjectFragment.usedVariableTemplates,
                    variables = loweredObjectFragment.variables,
                    fieldPathInclusionConditions =
                        loweredObjectFragment.fieldPathInclusionConditions,
                ),
            queryFragment =
                instantiateResolverFragment(
                    resolverOccurrenceId = resolverOccurrenceId,
                    constructionSelections = queryFragment,
                    usedVariableTemplates = loweredQueryFragment.usedVariableTemplates,
                    variables = loweredQueryFragment.variables,
                    fieldPathInclusionConditions =
                        loweredQueryFragment.fieldPathInclusionConditions,
                ),
        )

    /** Instantiates each named object template without combining its response-key namespace. */
    fun instantiateObjectMaterializationSelections(resolverOccurrenceId: ResolverOccurrenceId): Map<String, MaterializeSelectionForest> =
        loweredNamedTemplates.mapValues { (_, templates) ->
            templates
                .objectFragmentTemplate
                .instantiateVariables(resolverOccurrenceId)
        }

    /** Instantiates each named Query template without combining its response-key namespace. */
    fun instantiateQueryMaterializationSelections(resolverOccurrenceId: ResolverOccurrenceId): Map<String, MaterializeSelectionForest> =
        loweredNamedTemplates.mapValues { (_, templates) ->
            templates
                .queryFragmentTemplate
                .instantiateVariables(resolverOccurrenceId)
        }

    /** Runs each named provider once and returns its pair-qualified variable names. */
    protected open suspend fun provideVariables(arguments: Arguments.Resolved): Map<String, viaduct.engine.runtime2.model.EngineInputData?> =
        buildMap {
            fragmentTemplates.forEach { (name, templates) ->
                val provider = templates.variablesProvider ?: return@forEach
                val expected = providerVariableNames.getValue(name)
                val values = provider(arguments)
                require(values.keys == expected) {
                    "Checker variables provider $name returned invalid variables: " +
                        "expected $expected, got ${values.keys}"
                }
                values.forEach { (variable, value) ->
                    put(loweredCheckerVariableName(name, variable), value)
                }
            }
        }
}

private class LoweredCheckerFragment(
    val constructionSelections: ObjectSelectionForest,
    val usedVariableTemplates: Set<Arguments.Variable>,
    val variables: Map<Arguments.Variable, VariableDefinition>,
    val fieldPathInclusionConditions: Map<Arguments.Variable, List<InclusionCondition>>,
)

private fun Map<String, ResolverFragmentTemplates>.combineForResolution(
    fragmentRoot: ProviderFragment,
    type: ViaductSchema.Object,
): LoweredCheckerFragment {
    var constructionSelections = selectionForestOf()
    val variables = linkedMapOf<Arguments.Variable, VariableDefinition>()
    val fieldPathInclusionConditions =
        linkedMapOf<Arguments.Variable, List<InclusionCondition>>()
    forEach { (_, lowered) ->
        val materializeSelections =
            when (fragmentRoot) {
                ProviderFragment.OBJECT -> lowered.objectFragmentTemplate
                ProviderFragment.QUERY -> lowered.queryFragmentTemplate
            }
        constructionSelections += materializeSelections.constructionSelections()
        val loweredFieldPathInclusionConditions =
            lowered.variables.fieldPathInclusionConditions(
                fragmentRoot,
                materializeSelections,
            )
        lowered.variables.forEach { (variable, definition) ->
            check(variables.put(variable, definition) == null) {
                "Checker resolution variable was lowered twice: ${variable.variableName}"
            }
        }
        fieldPathInclusionConditions.putAll(loweredFieldPathInclusionConditions)
    }
    return LoweredCheckerFragment(
        constructionSelections = constructionSelections.merge(type),
        usedVariableTemplates = constructionSelections.usedVariables(),
        variables = variables,
        fieldPathInclusionConditions = fieldPathInclusionConditions,
    )
}

private fun ResolverFragmentTemplates.lowerForResolution(fragmentName: String): ResolverFragmentTemplates {
    fun lower(variable: Arguments.Variable): Arguments.Variable {
        require(variable.isTemplate) {
            "Checker fragment templates may contain only variable templates"
        }
        return Arguments.Variable.of(
            variable.target,
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

private fun MaterializeSelectionForest.mapVariableTemplates(transform: (Arguments.Variable) -> Arguments.Variable): MaterializeSelectionForest =
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

private fun VariableDefinition.mapVariableTemplates(transform: (Arguments.Variable) -> Arguments.Variable): VariableDefinition =
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

private val ResolverTarget.checkerObjectType: ViaductSchema.Object
    get() =
        when (this) {
            is ResolverTarget.FieldCheckerTarget -> field.containingDef
            is ResolverTarget.TypeCheckerTarget -> type
            is ResolverTarget.FieldValueResolverTarget ->
                error("A field-value resolver target cannot own a checker")
        }

private val ResolverTarget.checkerInputOwner: String
    get() =
        when (this) {
            is ResolverTarget.FieldCheckerTarget ->
                "Checker input for ${field.containingDef.name}/${field.name}"
            is ResolverTarget.TypeCheckerTarget -> "Checker input for ${type.name}"
            is ResolverTarget.FieldValueResolverTarget ->
                error("A field-value resolver target cannot own a checker")
        }

private fun ResolverFragmentTemplates.requireVariablesBelongTo(target: ResolverTarget) {
    variables.forEach { (variable, definition) ->
        require(variable.isTemplate) {
            "Checker registry variables must be templates"
        }
        require(variable.target == target) {
            "Variable ${variable.variableName} is not defined by ${target.render()}"
        }
        when (definition) {
            VariableDefinition.FromProvider -> Unit
            is VariableDefinition.FromArgument -> target.requireArgumentOwner(variable, definition)
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

private fun ResolverTarget.requireArgumentOwner(
    variable: Arguments.Variable,
    definition: VariableDefinition.FromArgument,
) {
    when (this) {
        is ResolverTarget.FieldCheckerTarget -> {
            val argument = definition.argument
            require(
                argument.containingDef == field && field.arg(argument.name) == argument,
            ) {
                "Variable ${variable.variableName} argument ${argument.name} does not belong to " +
                    "${field.containingDef.name}/${field.name}"
            }
        }
        is ResolverTarget.TypeCheckerTarget ->
            require(false) {
                "Type checker variable ${variable.variableName} cannot be defined from an argument"
            }
        is ResolverTarget.FieldValueResolverTarget ->
            error("A field-value resolver target cannot own a checker")
    }
}

/** Checker path bindings must be computable without depending on their own unresolved value. */
private fun ResolverFragmentTemplates.requireCheckerVariableDependencies() {
    val used =
        objectFragmentTemplate.constructionSelections().usedVariables() +
            queryFragmentTemplate.constructionSelections().usedVariables()
    require(used == variables.keys) {
        "Checker variable definitions must match the variables used by its named pair"
    }
    val dependencies =
        variables.mapValues { (_, definition) ->
            if (definition !is VariableDefinition.FromField) {
                emptySet()
            } else {
                val template =
                    if (definition.providerFragment == ProviderFragment.OBJECT) {
                        objectFragmentTemplate
                    } else {
                        queryFragmentTemplate
                    }
                definition.path.flatMapTo(linkedSetOf()) { it.arguments.usedVariables() } +
                    definition.inclusionConditions(template).flatMap { it.usedVariables() }
            }
        }
    val visited = mutableSetOf<Arguments.Variable>()
    val visiting = mutableSetOf<Arguments.Variable>()

    fun visit(variable: Arguments.Variable) {
        if (variable in visited) return
        require(visiting.add(variable)) {
            "Checker variables contain a provider dependency cycle"
        }
        dependencies.getValue(variable).forEach(::visit)
        visiting.remove(variable)
        visited.add(variable)
    }
    variables.keys.forEach(::visit)
}
