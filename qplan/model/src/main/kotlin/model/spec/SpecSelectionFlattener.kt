package model.spec

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult
import model.MaterializeSelection
import model.MaterializeSelectionForest
import model.InclusionCondition
import model.Selection
import model.SelectionForest
import model.flatMapToMaterializeSelectionForest
import model.materializeSelectionForestOf
import model.requireField

/**
 * Flattens a spec selection set interpreted with [typeInScope].
 *
 * Fields become [Selection] values, while inline fragments are removed after contributing their
 * type conditions to the fields they contain. At each selection-set level, every source field
 * occurrence contributes one member to the corresponding [SelectionForest], which erases source
 * order while preserving occurrences and does not define semantic equality for [Selection]. This
 * one-to-one correspondence is a postcondition of this translation; later normalization with
 * [model.merge] may combine members that produce equal concrete-object keys.
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
): MaterializeSelectionForest =
    selections.flatMapToMaterializeSelectionForest { selection ->
        when (selection) {
            is SpecSelection.Field ->
                materializeSelectionForestOf(selection.flattenField(schema, context))
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
                flattenSelectionSet(schema, selection.selections, fragmentContext)
            }
        }
    }

private fun SpecSelection.Field.flattenField(
    schema: ViaductSchema,
    context: SelectionContext,
): MaterializeSelection {
    val field = schemaField
    val flattenedSubselections =
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
                )
            else -> error("Output field has a non-output type")
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
