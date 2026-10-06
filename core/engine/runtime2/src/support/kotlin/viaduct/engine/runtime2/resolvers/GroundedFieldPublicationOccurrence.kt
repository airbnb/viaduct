package viaduct.engine.runtime2.resolvers

import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedFieldPublicationOccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.SharedTaskDispatcher
import viaduct.graphql.schema.ViaductSchema

/**
 * Immutable inputs to one grounded field or list-element publication in Resolver01-23.
 * Delegates the shared operation contract while retaining the concrete [operation] type;
 * family-specific dispatch remains available through `operation.dispatcher`.
 */
internal open class GroundedFieldPublicationOccurrence<out O : SharedOperationContext<*>>(
    override val operation: O,
    override val oerOccurrence: OEROccurrence,
    val selection: ObjectSelection,
    override val publicationCell: EngineResultCell,
    val reference: RootFieldReferenceData? = null,
    val invocationDemand: SelectionForest? = null,
    val publicationPath: List<PathComponent> = oerOccurrence.coordinate(selection.key),
    val publicationExpectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> = selection.key.field.outputType,
    /** Associated Query context for ordinary fragments; absent for mutation and list-reference publications. */
    val queryOER: SharedOERContext? = null,
    val constructionDemand: Demand<SelectionForest> = Demand.checked(selection.subselections),
) : SharedFieldPublicationOccurrence<O, SharedTaskDispatcher<Nothing, Nothing, Nothing>>,
    SharedOperationContext<SharedTaskDispatcher<Nothing, Nothing, Nothing>> by operation
