package viaduct.engine.runtime2.schema

import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.MutationSelection
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.isMutationNamespaceField
import viaduct.graphql.schema.ViaductSchema

/** Collects grounded operation fields by response key before entering ordered resolution. */
internal fun MaterializeSelectionForest.mutationSelections(
    schema: ViaductSchema,
    type: ViaductSchema.Object,
): MutationSelectionForest {
    val included = filter { selection ->
        require(selection.inclusionCondition === InclusionCondition.Always || selection.inclusionCondition === InclusionCondition.Never) {
            "Mutation operation conditions must be grounded"
        }
        selection.inclusionCondition === InclusionCondition.Always
    }
    return MutationSelectionForest.of(
        schema,
        type,
        included.collect(type).byResponseKey().values.map { selection ->
            val groundKey = selection.key as? ObjectEngineResult.GroundKey
                ?: error("Mutation operation arguments must be grounded")
            MutationSelection.of(
                selection.responseKey,
                groundKey,
                if (groundKey.field.isMutationNamespaceField()) {
                    selection.subselections.mutationSelections(schema, groundKey.field.type.baseTypeDef as ViaductSchema.Object)
                } else {
                    selection.subselections.constructionSelections()
                },
            )
        },
    )
}
