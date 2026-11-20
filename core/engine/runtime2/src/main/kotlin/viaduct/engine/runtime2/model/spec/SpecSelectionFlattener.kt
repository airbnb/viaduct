package viaduct.engine.runtime2.model.spec

import java.util.IdentityHashMap
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MaterializeSelection
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.flatMapToMaterializeSelectionForest
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.graphql.schema.ViaductSchema

/**
 * Flattens a spec selection set interpreted with [typeInScope].
 *
 * Fields become [Selection] values, while inline fragments are removed after contributing their
 * type conditions to the fields they contain. At each selection-set level, every source field
 * occurrence contributes one member to the corresponding [SelectionForest], which erases source
 * order while preserving occurrences and does not define semantic equality for [Selection]. This
 * one-to-one correspondence is a postcondition of this translation; later normalization with
 * [viaduct.engine.runtime2.model.merge] may combine members that produce equal concrete-object keys.
 */
fun flatten(
    schema: ViaductSchema,
    typeInScope: ViaductSchema.CompositeTypeDef,
    selectionSet: List<SpecSelection>,
): SelectionForest =
    flattenForMaterialization(
        schema = schema,
        typeInScope = typeInScope,
        selectionSet = selectionSet,
    ).constructionSelections()

/**
 * Flattens a spec selection set while retaining each field occurrence's GraphQL response key.
 *
 * Inline fragments contribute applicability guards without becoming materialize selections.
 * Source occurrences remain uncollected until a concrete parent type is supplied to
 * [MaterializeSelectionForest.collect].
 */
fun flattenForMaterialization(
    schema: ViaductSchema,
    typeInScope: ViaductSchema.CompositeTypeDef,
    selectionSet: List<SpecSelection>,
): MaterializeSelectionForest {
    val initialContext =
        SelectionContext(
            nominalType = typeInScope,
            possibleTypes = typeInScope.possibleObjectTypes,
            inclusionCondition = InclusionCondition.Always,
        )
    return flattenSelectionSet(schema, selectionSet, initialContext)
}

private fun flattenSelectionSet(
    schema: ViaductSchema,
    selections: List<SpecSelection>,
    context: SelectionContext,
    childForests: IdentityHashMap<SpecSelection.Field, MaterializeSelectionForest> = IdentityHashMap(),
): MaterializeSelectionForest =
    selections.flatMapToMaterializeSelectionForest { selection ->
        when (selection) {
            is SpecSelection.Field ->
                materializeSelectionForestOf(selection.flattenField(schema, context, childForests))
            is SpecSelection.InlineFragment -> {
                val fragmentContext =
                    selection.typeCondition?.let { typeCondition ->
                        SelectionContext(
                            nominalType = typeCondition,
                            possibleTypes =
                                context.possibleTypes intersect typeCondition.possibleObjectTypes,
                            inclusionCondition =
                                context.inclusionCondition.and(selection.inclusionCondition),
                        )
                    } ?: SelectionContext(
                        nominalType = context.nominalType,
                        possibleTypes = context.possibleTypes,
                        inclusionCondition =
                            context.inclusionCondition.and(selection.inclusionCondition),
                    )
                flattenSelectionSet(schema, selection.selections, fragmentContext, childForests)
            }
        }
    }

private fun SpecSelection.Field.flattenField(
    schema: ViaductSchema,
    context: SelectionContext,
    childForests: IdentityHashMap<SpecSelection.Field, MaterializeSelectionForest>,
): MaterializeSelection {
    val field = schemaField
    // Child context resets at a field, so shared source fields can reuse their immutable children.
    val flattenedSubselections = childForests.getOrPut(this) {
        when (val resultType = field.type.baseTypeDef) {
            is ViaductSchema.SimpleTypeDef -> materializeSelectionForestOf()

            is ViaductSchema.CompositeTypeDef ->
                flattenSelectionSet(
                    schema,
                    subselections.orEmpty(),
                    SelectionContext(
                        nominalType = resultType,
                        possibleTypes = resultType.possibleObjectTypes,
                        inclusionCondition = InclusionCondition.Always,
                    ),
                    childForests,
                )
            else -> error("Output field has a non-output type")
        }
    }

    return MaterializeSelection.of(
        responseKey = alias ?: fieldName,
        key =
            ObjectEngineResult.Key.of(
                field = field,
                arguments = arguments,
            ),
        possibleTypes = context.possibleTypes,
        inclusionCondition = context.inclusionCondition.and(inclusionCondition),
        fieldDirectives = fieldDirectives,
        subselections = flattenedSubselections,
    )
}

private class SelectionContext(
    val nominalType: ViaductSchema.CompositeTypeDef,
    val possibleTypes: Set<ViaductSchema.Object>,
    val inclusionCondition: InclusionCondition,
)
