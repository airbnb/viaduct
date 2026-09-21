package viaduct.engine.runtime.execution

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeFilter
import viaduct.engine.runtime.result.ObjectEngineResult

/**
 * Converts returned object data to the selections it satisfies.
 *
 * The returned data identifies fields by schema name. [selections] supplies the exact aliases and
 * arguments for fields requested from this materialization. A returned field absent from
 * [selections] can be represented only when it has no arguments. [filter] drops fields outside the
 * resolver's output selection set and stops traversal below them.
 */
internal suspend fun EngineObjectData?.toKeyTree(
    schema: GraphQLSchema,
    selections: KeyTree,
    filter: KeyTreeFilter,
): KeyTree =
    EngineObjectDataKeyTreeBuilder(schema, filter)
        .build(
            data = this,
            selections = selections,
            atOutputSelectionSetRoot = true,
        )

private class EngineObjectDataKeyTreeBuilder(
    private val schema: GraphQLSchema,
    private val outputSelectionSetFilter: KeyTreeFilter,
) {
    suspend fun build(
        data: EngineObjectData?,
        selections: KeyTree,
        atOutputSelectionSetRoot: Boolean,
    ): KeyTree {
        if (data == null) {
            return selections.withinOutputSelectionSet(atOutputSelectionSetRoot)
        }
        if (data is NodeEngineObjectData) {
            return buildNodeReference(data, selections, atOutputSelectionSetRoot)
        }

        val type = schema.getObjectType(data.type.name) ?: return KeyTree.empty
        val selectionsForType = selections.fieldsFor(type)
        val returnedFieldNames = data.fetchSelections().toSet()
        val returnedFields = mutableMapOf<ObjectEngineResult.Key, KeyTree>()

        for (fieldName in returnedFieldNames) {
            val fieldDefinition = type.getFieldDefinition(fieldName) ?: continue
            val returnedSelections = selectionsForReturnedField(
                fieldDefinition = fieldDefinition,
                selections = selectionsForType,
            ).filterKeys { key ->
                outputSelectionSetFilter(type, key, atOutputSelectionSetRoot)
            }
            if (returnedSelections.isEmpty()) continue

            val nestedSelections = returnedSelections.values.fold(KeyTree.empty, KeyTree::plus)
            val returnedSubtree =
                if (GraphQLTypeUtil.unwrapAll(fieldDefinition.type) is GraphQLCompositeType) {
                    buildValue(
                        value = data.fetchOrNull(fieldName),
                        selections = nestedSelections,
                    )
                } else {
                    KeyTree.empty
                }

            for (key in returnedSelections.keys) {
                returnedFields[key] = returnedSubtree
            }
        }

        return KeyTree(mapOf(type to returnedFields)).withoutEmptyTypeBranches()
    }

    private fun selectionsForReturnedField(
        fieldDefinition: GraphQLFieldDefinition,
        selections: Map<ObjectEngineResult.Key, KeyTree>,
    ): Map<ObjectEngineResult.Key, KeyTree> {
        val fieldName = fieldDefinition.name
        val matchingSelections = selections.filterKeys { it.name == fieldName }

        if (matchingSelections.isEmpty()) {
            return if (fieldDefinition.arguments.isEmpty()) {
                mapOf(ObjectEngineResult.Key(fieldName) to KeyTree.empty)
            } else {
                emptyMap()
            }
        }

        // Returned data has one value per schema field name, so it cannot distinguish argument sets.
        return matchingSelections.takeIf {
            it.keys.map { key -> key.arguments }.distinct().size == 1
        }.orEmpty()
    }

    private suspend fun buildValue(
        value: Any?,
        selections: KeyTree,
    ): KeyTree =
        when (value) {
            null -> selections.withinOutputSelectionSet(atOutputSelectionSetRoot = false)
            is EngineObjectData -> build(value, selections, atOutputSelectionSetRoot = false)
            is Iterable<*> -> buildIterable(value, selections)
            else -> KeyTree.empty
        }

    private suspend fun buildIterable(
        values: Iterable<*>,
        selections: KeyTree,
    ): KeyTree {
        val commonFieldsByType = selections.withinOutputSelectionSet(
            atOutputSelectionSetRoot = false,
        ).keysByType().toMutableMap()
        val seenTypes = mutableSetOf<GraphQLObjectType>()

        suspend fun visit(value: Any?) {
            when (value) {
                null -> Unit
                is Iterable<*> -> value.forEach { visit(it) }
                is EngineObjectData -> {
                    val type = schema.getObjectType(value.type.name) ?: return
                    val returnedFields = build(
                        data = value,
                        selections = selections,
                        atOutputSelectionSetRoot = false,
                    ).fieldsFor(type)
                    commonFieldsByType[type] =
                        if (seenTypes.add(type)) {
                            returnedFields
                        } else {
                            intersectFields(
                                type,
                                commonFieldsByType[type].orEmpty(),
                                returnedFields,
                            )
                        }
                }
            }
        }

        visit(values)
        return KeyTree(commonFieldsByType).withoutEmptyTypeBranches()
    }

    private fun buildNodeReference(
        data: NodeEngineObjectData,
        selections: KeyTree,
        atOutputSelectionSetRoot: Boolean,
    ): KeyTree {
        val type = schema.getObjectType(data.type.name) ?: return KeyTree.empty
        val idDefinition = type.getFieldDefinition("id") ?: return KeyTree.empty
        val returnedSelections = selectionsForReturnedField(
            fieldDefinition = idDefinition,
            selections = selections.fieldsFor(type),
        ).filterKeys { key ->
            outputSelectionSetFilter(type, key, atOutputSelectionSetRoot)
        }
        return KeyTree(mapOf(type to returnedSelections.mapValues { KeyTree.empty }))
            .withoutEmptyTypeBranches()
    }

    private fun KeyTree.withinOutputSelectionSet(atOutputSelectionSetRoot: Boolean): KeyTree =
        filter { type, key, atKeyTreeRoot ->
            outputSelectionSetFilter(
                type,
                key,
                atOutputSelectionSetRoot && atKeyTreeRoot,
            )
        }.withoutEmptyTypeBranches()

    private fun intersectFields(
        type: GraphQLObjectType,
        left: Map<ObjectEngineResult.Key, KeyTree>,
        right: Map<ObjectEngineResult.Key, KeyTree>,
    ): Map<ObjectEngineResult.Key, KeyTree> =
        KeyTree(mapOf(type to left))
            .intersect(KeyTree(mapOf(type to right)))
            .fieldsFor(type)

    private fun KeyTree.fieldsFor(type: GraphQLObjectType): Map<ObjectEngineResult.Key, KeyTree> {
        val fieldsByType = keysByType()
        return fieldsByType[type]
            ?: fieldsByType.entries.firstOrNull { it.key.name == type.name }?.value
            ?: emptyMap()
    }
}
