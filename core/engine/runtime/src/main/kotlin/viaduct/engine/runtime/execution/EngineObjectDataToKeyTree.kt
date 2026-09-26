@file:Suppress("DEPRECATION")

package viaduct.engine.runtime.execution

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import java.util.IdentityHashMap
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.spi.MaterializedFieldValueReader
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeFilter
import viaduct.engine.runtime.mat.KeyTreeFilter.Result.DROP
import viaduct.engine.runtime.mat.KeyTreeFilter.Result.KEEP_AND_RECURSE
import viaduct.engine.runtime.mat.KeyTreeFilter.Result.KEEP_WITHOUT_CHILDREN
import viaduct.engine.runtime.result.ObjectEngineResult

/**
 * Lists the fields that this materialized [EngineObjectData] contains, so later reads know whether
 * they can reuse it.
 *
 * The data only says which fields are present, by schema name. Each recorded field also needs its
 * alias and arguments, so those are copied from the matching request in [selections], the fields
 * the engine asked the resolver for. Object fields are read through [fieldValueReader], once per
 * alias.
 *
 * @param filter drops fields outside the resolver's output selection set, and everything below them.
 */
internal suspend fun EngineObjectData?.toKeyTree(
    schema: GraphQLSchema,
    selections: KeyTree,
    filter: KeyTreeFilter,
    fieldValueReader: MaterializedFieldValueReader,
): KeyTree =
    EngineObjectDataKeyTreeBuilder(schema, filter, fieldValueReader)
        .build(
            data = this,
            selections = selections,
            atOutputSelectionSetRoot = true,
        )

private class EngineObjectDataKeyTreeBuilder(
    private val schema: GraphQLSchema,
    private val outputSelectionSetFilter: KeyTreeFilter,
    private val fieldValueReader: MaterializedFieldValueReader,
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
            )
            val decisions = returnedSelections.mapValues { (key, _) ->
                outputSelectionSetFilter(type, key, atOutputSelectionSetRoot)
            }
            val isComposite = GraphQLTypeUtil.unwrapAll(fieldDefinition.type) is GraphQLCompositeType
            // Aliases that get the same object share one walk, using all of their requested fields.
            val keysByValue = IdentityHashMap<Any?, MutableList<ObjectEngineResult.Key>>()

            for ((key, decision) in decisions) {
                when (decision) {
                    DROP -> continue
                    KEEP_WITHOUT_CHILDREN -> returnedFields[key] = KeyTree.empty
                    KEEP_AND_RECURSE -> {
                        if (!isComposite) {
                            returnedFields[key] = KeyTree.empty
                            continue
                        }
                        val read = fieldValueReader.read(data, key.name, key.responseKey)
                        if (read.fieldIsMissing) continue
                        keysByValue.getOrPut(read.value) { mutableListOf() } += key
                    }
                }
            }
            for ((value, keys) in keysByValue) {
                val subtree = buildValue(value, keys.map(returnedSelections::getValue).fold(KeyTree.empty, KeyTree::plus))
                keys.forEach { returnedFields[it] = subtree }
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
            outputSelectionSetFilter(type, key, atOutputSelectionSetRoot) != DROP
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
