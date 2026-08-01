package viaduct.engine.runtime.select

import graphql.introspection.Introspection
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ViaductSchema

/**
 * Returns true when this selection set contains every field selected by [other].
 * @param implicitlyCoveredFields fields that are always considered "covered", no matter where they appear in a selection set
 * @param implicitlyCoveredTopLevelFields fields that are always considered "covered" when they appear at the root of a selection set
 */
fun EngineSelectionSet.covers(
    other: EngineSelectionSet,
    implicitlyCoveredFields: Set<String> = emptySet(),
    implicitlyCoveredTopLevelFields: Set<String> = emptySet()
): Boolean {
    if (other === this) return true
    return Covers(
        schema = schema,
        implicitlyCoveredFields = implicitlyCoveredFields,
        implicitlyCoveredTopLevelFields = implicitlyCoveredTopLevelFields,
    ).covers(listOf(this), other)
}

private class Covers(
    private val schema: ViaductSchema,
    private val implicitlyCoveredFields: Set<String>,
    private val implicitlyCoveredTopLevelFields: Set<String>,
) {
    fun covers(
        covered: List<EngineSelectionSet>,
        required: EngineSelectionSet,
        topLevel: Boolean = true,
    ): Boolean {
        val requiredType = schema.schema.getType(required.type) as? GraphQLCompositeType ?: return true

        for (type in schema.rels.possibleObjectTypes(requiredType)) {
            val requiredProjection = required.selectionSetForType(type.name)
            for (requiredSelection in requiredProjection.selections()) {
                if (!selectionApplies(requiredSelection.typeCondition, type)) continue

                val fieldDef =
                    requireNotNull(Introspection.getFieldDef(schema.schema, type, requiredSelection.fieldName)) {
                        "Selected field `${requiredSelection.fieldName}` is not defined on `${type.name}`"
                    }
                val fieldType = GraphQLTypeUtil.unwrapAll(fieldDef.type)
                if (isImplicitlyCovered(requiredSelection, fieldType, topLevel)) {
                    continue
                }

                if (fieldType is GraphQLCompositeType) {
                    val coveredChildren = matchingCoveredChildren(covered, type, requiredProjection, requiredSelection)
                    if (coveredChildren.isEmpty()) return false

                    val requiredChild = requiredProjection.selectionSetForSelection(
                        type.name,
                        requiredSelection.selectionName,
                    )
                    if (!covers(coveredChildren, requiredChild, topLevel = false)) return false
                } else if (!hasMatchingCoveredField(covered, type, requiredProjection, requiredSelection)) {
                    return false
                }
            }
        }
        return true
    }

    private fun isImplicitlyCovered(
        requiredSelection: EngineSelection,
        fieldType: Any,
        topLevel: Boolean,
    ): Boolean =
        fieldType !is GraphQLCompositeType &&
            (
                requiredSelection.fieldName in implicitlyCoveredFields ||
                    (topLevel && requiredSelection.fieldName in implicitlyCoveredTopLevelFields)
            )

    private fun matchingCoveredChildren(
        covered: List<EngineSelectionSet>,
        type: GraphQLObjectType,
        requiredProjection: EngineSelectionSet,
        requiredSelection: EngineSelection
    ): List<EngineSelectionSet> {
        val children = mutableListOf<EngineSelectionSet>()
        forEachMatchingCoveredSelection(
            covered,
            type,
            requiredProjection,
            requiredSelection,
        ) { coveredProjection, coveredSelection ->
            children += coveredProjection.selectionSetForSelection(
                type.name,
                coveredSelection.selectionName,
            )
        }
        return children
    }

    private fun hasMatchingCoveredField(
        covered: List<EngineSelectionSet>,
        type: GraphQLObjectType,
        requiredProjection: EngineSelectionSet,
        requiredSelection: EngineSelection
    ): Boolean {
        forEachMatchingCoveredSelection(covered, type, requiredProjection, requiredSelection) { _, _ ->
            return true
        }
        return false
    }

    private inline fun forEachMatchingCoveredSelection(
        coveredSelectionSets: List<EngineSelectionSet>,
        type: GraphQLObjectType,
        requiredProjection: EngineSelectionSet,
        requiredSelection: EngineSelection,
        visit: (coveredProjection: EngineSelectionSet, coveredSelection: EngineSelection) -> Unit
    ) {
        val requiredArguments = requiredProjection.argumentsOfSelection(
            type.name,
            requiredSelection.selectionName,
        ) ?: emptyMap()

        for (covered in coveredSelectionSets) {
            val coveredProjection = covered.selectionSetForTypeOrNull(type) ?: continue
            for (coveredSelection in coveredProjection.selections()) {
                if (!selectionApplies(coveredSelection.typeCondition, type)) continue
                if (!coveredSelection.matches(requiredSelection)) continue

                val coveredArguments = coveredProjection.argumentsOfSelection(
                    type.name,
                    coveredSelection.selectionName,
                ) ?: emptyMap()
                if (coveredArguments == requiredArguments) {
                    visit(coveredProjection, coveredSelection)
                }
            }
        }
    }

    private fun EngineSelectionSet.selectionSetForTypeOrNull(type: GraphQLObjectType): EngineSelectionSet? {
        val selectionType = schema.schema.getType(this.type) as? GraphQLCompositeType ?: return null
        if (type !in schema.rels.possibleObjectTypes(selectionType)) return null
        return selectionSetForType(type.name)
    }

    private fun EngineSelection.matches(other: EngineSelection): Boolean = fieldName == other.fieldName && selectionName == other.selectionName

    private fun selectionApplies(
        conditionName: String,
        type: GraphQLObjectType
    ): Boolean {
        if (conditionName == type.name) return true
        val condition = schema.schema.getType(conditionName) as? GraphQLCompositeType ?: return false
        return type in schema.rels.possibleObjectTypes(condition)
    }
}
