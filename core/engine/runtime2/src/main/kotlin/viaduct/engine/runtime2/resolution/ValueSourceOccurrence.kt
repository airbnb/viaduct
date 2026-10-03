package viaduct.engine.runtime2.resolution

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.registry.FieldValueResolver
import viaduct.engine.runtime2.model.registry.ResolverFragments
import viaduct.engine.runtime2.model.registry.VariableInstanceDefinition
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.graphql.schema.ViaductSchema

/**
 * One source of a value: a resolver invocation, reference, or conditioned passive value.
 * Reference-hop invocations contribute values without owning separate destination cells.
 */
internal sealed interface ValueSourceOccurrence {
    val selection: ObjectSelection

    val publicationPath: List<PathComponent>

    val publicationExpectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>
        get() = selection.key.field.outputType

    val publicationConstructionDemand: Demand<SelectionForest>
        get() = Demand.checked(selection.subselections)
}

/** One resolver invocation, with its identity, instantiated inputs, and variable definitions. */
internal data class FieldResolverOccurrence(
    override val selection: ObjectSelection,
    val invocationRoot: ObjectEngineResult,
    val invocationPath: List<PathComponent>,
    val resolverOccurrenceId: ResolverOccurrenceId,
    val resolver: FieldValueResolver,
    val variableDefinitions: List<VariableInstanceDefinition>,
    val fragments: ResolverFragments,
    override val publicationConstructionDemand: Demand<SelectionForest> =
        Demand.checked(selection.subselections),
) : ValueSourceOccurrence {
    override val publicationPath: List<PathComponent>
        get() = invocationPath
}

/** A source-provided symbolic reference waiting to be converted into a resolver occurrence. */
internal data class RootFieldReferenceOccurrence(
    override val selection: ObjectSelection,
    val reference: RootFieldReferenceData,
    override val publicationConstructionDemand: Demand<SelectionForest> =
        Demand.checked(selection.subselections),
    override val publicationPath: List<PathComponent>,
    override val publicationExpectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> =
        selection.key.field.outputType,
) : ValueSourceOccurrence

/** A conditioned passive value whose embedded references must not launch before activation. */
internal data class PassiveValueOccurrence(
    override val selection: ObjectSelection,
    val value: ResolverOutputData?,
    val invocationDemand: SelectionForest,
    override val publicationConstructionDemand: Demand<SelectionForest>,
    override val publicationPath: List<PathComponent>,
) : ValueSourceOccurrence
