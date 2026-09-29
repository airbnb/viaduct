package semantics.resolvers

import model.EngineResultCell
import model.ObjectSelection
import model.PathComponent
import model.RootFieldReferenceData
import model.SelectionForest
import model.outputType
import semantics.shared.Demand
import semantics.shared.OEROccurrence
import semantics.shared.SharedFieldPublicationOccurrence
import semantics.shared.SharedOERContext
import semantics.shared.SharedOperationContext
import semantics.shared.SharedTaskDispatcher
import viaduct.graphql.schema.ViaductSchema

/**
 * Immutable inputs to one grounded field or list-element publication in Resolver01-23.
 * Delegates the shared operation contract while retaining the concrete [operation] type;
 * family-specific dispatch remains available through `operation.dispatcher`.
 */
internal class GroundedFieldPublicationOccurrence<out O : SharedOperationContext<*>>(
    override val operation: O,
    override val oerOccurrence: OEROccurrence,
    val selection: ObjectSelection,
    override val publicationCell: EngineResultCell,
    val reference: RootFieldReferenceData? = null,
    val invocationDemand: SelectionForest? = null,
    val publicationPath: List<PathComponent> = oerOccurrence.coordinate(selection.key),
    val publicationExpectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> = selection.key.field.outputType,
    val queryOER: SharedOERContext,
    val constructionDemand: Demand<SelectionForest> = Demand.checked(selection.subselections),
) : SharedFieldPublicationOccurrence<O, SharedTaskDispatcher<Nothing, Nothing>>,
    SharedOperationContext<SharedTaskDispatcher<Nothing, Nothing>> by operation
