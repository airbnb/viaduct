package execution.testing

import model.fragmentFromDocument
import model.registry.ResolutionExecutionContext
import model.requireQueryTypeDef
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.graphql.schema.ViaductSchema

/** Engine API facade whose selection execution is owned by the active Resolver26 field task. */
internal class QPlanEngineExecutionContext(
    delegate: EngineExecutionContext,
    private val schema: ViaductSchema,
    private val resolutionContext: ResolutionExecutionContext,
) : EngineExecutionContext by delegate {
    override suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync {
        require(options.operationType == Engine.OperationType.QUERY) {
            "Qplan selection execution currently supports Query only"
        }
        require(selectionSet.type == schema.requireQueryTypeDef().name) {
            "Cannot execute selections with type ${selectionSet.type} on schema root type ${schema.requireQueryTypeDef().name}"
        }
        val fragment =
            schema.fragmentFromDocument(
                document = selectionSet.toFragment().parsedDocument,
                bindings = selectionSet.variables,
            )
        return resolutionContext.resolveSelectionSet(fragment.materializeSelections)
    }
}
