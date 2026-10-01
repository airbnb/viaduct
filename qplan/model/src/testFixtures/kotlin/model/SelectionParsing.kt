package model

import model.lowering.ViaductAndGJSchema
import model.parsing.materializeSelectionsFrom
import model.registry.ResolverTarget
import viaduct.graphql.schema.ViaductSchema

fun ViaductAndGJSchema.fragmentFrom(
    source: String,
    bindings: Map<String, EngineInputData?> = emptyMap(),
    variableField: ViaductSchema.ObjectField? = null,
    variableTarget: ResolverTarget? = null,
    preserveSourceResponseKeys: Boolean = false,
): Fragment {
    val (nominalType, selections) =
        materializeSelectionsFrom(
            source = source,
            bindings = bindings,
            variableField = variableField,
            variableTarget = variableTarget,
            preserveSourceResponseKeys = preserveSourceResponseKeys,
        )
    return Fragment.of(
        nominalType = nominalType,
        materializeSelections = selections,
    )
}

/** Constructs the model-only empty fragment that GraphQL text cannot express. */
fun Assumptions.emptyFragmentOf(typeName: String): Fragment = schema.emptyFragmentOf(typeName)
