package semantics.resolver26

import model.Assumptions
import model.EngineOutputData
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.SelectionForest
import model.objectKey
import model.outputValue
import model.registry.FieldValueResolver
import model.registry.ProviderFragment
import model.registry.ResolverTarget
import model.registry.VariableDefinition
import model.schemaType
import model.usedVariables
import semantics.shared.ResolverInvocationObservation
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

internal enum class ParentVariableSource {
    ARGUMENT,
    OBJECT_FIELD,
    QUERY_FIELD,
    PROVIDER,
}

internal enum class ParentResolverInputFragment {
    OBJECT,
    QUERY,
}

internal data class ParentSelectedResolverCoverage(
    val field: ViaductSchema.ObjectField,
    val selectionDepthBelowParent: Int,
    val requiredInputVariableSources: Set<ParentVariableSource>,
    val variableArgumentSelections: List<ParentResolverVariableArgumentCoverage>,
    val diagonalParentDepth: Int,
)

internal data class ParentResolverVariableArgumentCoverage(
    val fragment: ParentResolverInputFragment,
    val selectionDepth: Int,
    val variableSources: Set<ParentVariableSource>,
)

internal data class ParentArgumentVariableCoverage(
    val field: ViaductSchema.ObjectField,
    val selectionDepthBelowParent: Int,
    val variableSources: Set<ParentVariableSource>,
)

internal data class ParentSelectionSetCoverage(
    val field: ViaductSchema.ObjectField,
    val consecutiveParentDepth: Int,
    val argumentVariables: List<ParentArgumentVariableCoverage>,
    val selectedResolvers: List<ParentSelectedResolverCoverage>,
)

/**
 * Attributes materialized input selections to every enclosing parent selection set.
 *
 * Direct argument variables are inspected in the observed application input. Variables legally
 * introduced across a selected-resolver boundary are inspected in that resolver's object and
 * Query fragments. List elements contribute separate materialized parent occurrences.
 */
internal class ParentCoverageAnalyzer(
    private val world: Assumptions,
) {
    private val diagonalDepthByResolverField =
        mutableMapOf<ViaductSchema.ObjectField, Int>()

    fun analyze(application: ResolverInvocationObservation): List<ParentSelectionSetCoverage> {
        val resolver = world.resolverRegistry.resolver(application.field)
        val variableSources = resolver.variableSourcesByName()
        val parentSelections = mutableListOf<MutableParentSelectionSetCoverage>()
        application.input.collectParentCoverage(
            selections = application.inputSelections,
            activeParents = emptyList(),
            consecutiveParentDepth = 0,
            selectionDepth = 0,
            variableOwner = application.field,
            variableSources = variableSources,
            output = parentSelections,
        )
        return parentSelections.map(MutableParentSelectionSetCoverage::snapshot)
    }

    private fun EngineOutputData?.collectParentCoverage(
        selections: MaterializeSelectionForest,
        activeParents: List<MutableParentSelectionSetCoverage>,
        consecutiveParentDepth: Int,
        selectionDepth: Int,
        variableOwner: ViaductSchema.ObjectField,
        variableSources: Map<String, ParentVariableSource>,
        output: MutableList<MutableParentSelectionSetCoverage>,
    ) {
        when (this) {
            is EngineObjectData.Sync ->
                selections
                    .collect(schemaType)
                    .byResponseKey()
                    .forEach { (responseKey, selection) ->
                        if (!isPresent(responseKey)) return@forEach
                        val key = selection.key
                        val isParent = key is ObjectEngineResult.ParentKey
                        val nextParentDepth =
                            if (isParent) consecutiveParentDepth + 1 else 0
                        val currentParents =
                            if (isParent) {
                                val parent =
                                    MutableParentSelectionSetCoverage(
                                        field = key.field,
                                        consecutiveParentDepth = nextParentDepth,
                                        selectionDepth = selectionDepth,
                                    )
                                output += parent
                                activeParents + parent
                            } else {
                                activeParents
                            }
                        if (currentParents.isNotEmpty()) {
                            val sources =
                                key.arguments
                                    .usedVariables()
                                    .mapTo(linkedSetOf()) { variable ->
                                        check((variable.target as ResolverTarget.FieldTarget).field == variableOwner)
                                        variableSources.getValue(variable.variableName)
                                    }
                            currentParents.forEach { parent ->
                                val depthBelowParent = selectionDepth - parent.selectionDepth
                                if (sources.isNotEmpty()) {
                                    parent.argumentVariables +=
                                        ParentArgumentVariableCoverage(
                                            field = key.field,
                                            selectionDepthBelowParent = depthBelowParent,
                                            variableSources = sources,
                                        )
                                }
                                if (key.field in world.resolverRegistry) {
                                    parent.selectedResolvers +=
                                        selectedResolverCoverage(
                                            field = key.field,
                                            selectionDepthBelowParent = depthBelowParent,
                                        )
                                }
                            }
                        }
                        outputValue(responseKey).collectParentCoverage(
                            selections = selection.subselections,
                            activeParents = currentParents,
                            consecutiveParentDepth = nextParentDepth,
                            selectionDepth = selectionDepth + 1,
                            variableOwner = variableOwner,
                            variableSources = variableSources,
                            output = output,
                        )
                    }

            is List<*> ->
                forEach { value ->
                    value.collectParentCoverage(
                        selections = selections,
                        activeParents = activeParents,
                        consecutiveParentDepth = consecutiveParentDepth,
                        selectionDepth = selectionDepth,
                        variableOwner = variableOwner,
                        variableSources = variableSources,
                        output = output,
                    )
                }
        }
    }

    private fun selectedResolverCoverage(
        field: ViaductSchema.ObjectField,
        selectionDepthBelowParent: Int,
    ): ParentSelectedResolverCoverage {
        val resolver = world.resolverRegistry.resolver(field)
        val variableArgumentSelections = resolver.variableArgumentSelections(field)
        return ParentSelectedResolverCoverage(
            field = field,
            selectionDepthBelowParent = selectionDepthBelowParent,
            requiredInputVariableSources =
                variableArgumentSelections
                    .flatMapTo(linkedSetOf()) { selection -> selection.variableSources },
            variableArgumentSelections = variableArgumentSelections,
            diagonalParentDepth = diagonalParentDepth(field),
        )
    }

    private fun FieldValueResolver.variableArgumentSelections(variableOwner: ViaductSchema.ObjectField): List<ParentResolverVariableArgumentCoverage> {
        val sources = variableSourcesByName()
        return objectFragment.variableArgumentSelections(
            fragment = ParentResolverInputFragment.OBJECT,
            variableOwner = variableOwner,
            variableSources = sources,
        ) +
            queryFragment.variableArgumentSelections(
                fragment = ParentResolverInputFragment.QUERY,
                variableOwner = variableOwner,
                variableSources = sources,
            )
    }

    private fun SelectionForest.variableArgumentSelections(
        fragment: ParentResolverInputFragment,
        variableOwner: ViaductSchema.ObjectField,
        variableSources: Map<String, ParentVariableSource>,
        selectionDepth: Int = 1,
    ): List<ParentResolverVariableArgumentCoverage> =
        buildList {
            this@variableArgumentSelections.forEach { selection ->
                val sources =
                    selection.key.arguments
                        .usedVariables()
                        .mapTo(linkedSetOf()) { variable ->
                            check((variable.target as ResolverTarget.FieldTarget).field == variableOwner)
                            variableSources.getValue(variable.variableName)
                        }
                if (sources.isNotEmpty()) {
                    add(
                        ParentResolverVariableArgumentCoverage(
                            fragment = fragment,
                            selectionDepth = selectionDepth,
                            variableSources = sources,
                        ),
                    )
                }
                addAll(
                    selection.subselections.variableArgumentSelections(
                        fragment = fragment,
                        variableOwner = variableOwner,
                        variableSources = variableSources,
                        selectionDepth = selectionDepth + 1,
                    ),
                )
            }
        }

    private fun diagonalParentDepth(field: ViaductSchema.ObjectField): Int =
        diagonalDepthByResolverField.getOrPut(field) {
            val resolver = world.resolverRegistry.resolver(field)
            val parentSelections = resolver.objectFragment.topLevelParentSelections()
            if (parentSelections.isEmpty()) {
                0
            } else {
                1 +
                    (
                        parentSelections
                            .flatMap { selection ->
                                selection.subselections.directResolverFields()
                            }.maxOfOrNull(::diagonalParentDepth) ?: 0
                    )
            }
        }

    private fun SelectionForest.topLevelParentSelections(): List<model.Selection> =
        buildList {
            this@topLevelParentSelections.forEach { selection ->
                if (
                    selection.possibleTypes.any { possibleType ->
                        selection.objectKey(possibleType) is ObjectEngineResult.ParentKey
                    }
                ) {
                    add(selection)
                }
            }
        }

    private fun SelectionForest.directResolverFields(): Set<ViaductSchema.ObjectField> =
        buildSet {
            this@directResolverFields.forEach { selection ->
                selection.possibleTypes.forEach { possibleType ->
                    val field = selection.objectKey(possibleType).field
                    if (field in world.resolverRegistry) add(field)
                }
            }
        }
}

private data class MutableParentSelectionSetCoverage(
    val field: ViaductSchema.ObjectField,
    val consecutiveParentDepth: Int,
    val selectionDepth: Int,
    val argumentVariables: MutableList<ParentArgumentVariableCoverage> = mutableListOf(),
    val selectedResolvers: MutableList<ParentSelectedResolverCoverage> = mutableListOf(),
) {
    fun snapshot(): ParentSelectionSetCoverage =
        ParentSelectionSetCoverage(
            field = field,
            consecutiveParentDepth = consecutiveParentDepth,
            argumentVariables = argumentVariables.toList(),
            selectedResolvers = selectedResolvers.toList(),
        )
}

private fun FieldValueResolver.variableSourcesByName(): Map<String, ParentVariableSource> =
    variables.mapKeys { (variable, _) -> variable.variableName }
        .mapValues { (_, definition) -> definition.parentVariableSource() }

private fun VariableDefinition.parentVariableSource(): ParentVariableSource =
    when (this) {
        VariableDefinition.FromProvider -> ParentVariableSource.PROVIDER
        is VariableDefinition.FromArgument -> ParentVariableSource.ARGUMENT
        is VariableDefinition.FromField ->
            when (providerFragment) {
                ProviderFragment.OBJECT -> ParentVariableSource.OBJECT_FIELD
                ProviderFragment.QUERY -> ParentVariableSource.QUERY_FIELD
            }
    }
