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
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.api.spi.MaterializedFieldValueReader
import viaduct.engine.runtime.HasResolver
import viaduct.engine.runtime.LazyEngineObjectData
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
    resolutionPolicy: ResolutionPolicy = ResolutionPolicy.STANDARD,
): KeyTree =
    EngineObjectDataKeyTreeBuilder(schema, filter, fieldValueReader)
        .build(
            data = this,
            selections = selections,
            atOutputSelectionSetRoot = true,
            inheritedResolutionPolicy = resolutionPolicy,
        ).toKeyTree().withoutEmptyTypeBranches()

private class EngineObjectDataKeyTreeBuilder(
    private val schema: GraphQLSchema,
    private val outputSelectionSetFilter: KeyTreeFilter,
    private val fieldValueReader: MaterializedFieldValueReader,
) {
    suspend fun build(
        data: EngineObjectData?,
        selections: KeyTree,
        atOutputSelectionSetRoot: Boolean,
        inheritedResolutionPolicy: ResolutionPolicy,
    ): ReturnedCoverage {
        val resolutionPolicy = if (data == null || atOutputSelectionSetRoot) {
            inheritedResolutionPolicy
        } else {
            fieldValueReader.resolutionPolicy(data, inheritedResolutionPolicy)
        }
        if (data == null) {
            return ReturnedCoverage.absent(selections.withinOutputSelectionSet(atOutputSelectionSetRoot, resolutionPolicy))
        }
        val type = schema.getObjectType(data.type.name) ?: return ReturnedCoverage.NoChildCoverage
        if (!atOutputSelectionSetRoot && inheritedResolutionPolicy == ResolutionPolicy.PARENT_MANAGED && resolutionPolicy == ResolutionPolicy.STANDARD) {
            return ReturnedCoverage.observed(type, emptyMap())
        }
        if (data is NodeEngineObjectData) {
            return buildNodeReference(data, selections, atOutputSelectionSetRoot, resolutionPolicy)
        }
        if (data is LazyEngineObjectData) {
            // Child traversal must resolve lazy data before its selections can be read.
            return ReturnedCoverage.observed(type, emptyMap())
        }

        val selectionsForType = selections.fieldsFor(type)
        val returnedFieldNames = data.fetchSelections().toSet()
        val returnedFields = mutableMapOf<ObjectEngineResult.Key, ReturnedCoverage>()

        for (fieldName in returnedFieldNames) {
            val fieldDefinition = type.getFieldDefinition(fieldName) ?: continue
            val returnedSelections = selectionsForReturnedField(
                fieldDefinition = fieldDefinition,
                selections = selectionsForType,
            )
            val decisions = returnedSelections.mapValues { (key, _) ->
                coverageFilter(resolutionPolicy)(type, key, atOutputSelectionSetRoot)
            }
            val isComposite = GraphQLTypeUtil.unwrapAll(fieldDefinition.type) is GraphQLCompositeType
            // Aliases that get the same object share one walk, using all of their requested fields.
            val keysByValue = IdentityHashMap<Any?, MutableList<ObjectEngineResult.Key>>()

            for ((key, decision) in decisions) {
                when (decision) {
                    DROP -> continue
                    KEEP_WITHOUT_CHILDREN -> returnedFields[key] = ReturnedCoverage.NoChildCoverage
                    KEEP_AND_RECURSE -> {
                        if (!isComposite) {
                            returnedFields[key] = ReturnedCoverage.NoChildCoverage
                            continue
                        }
                        val read = fieldValueReader.readForCoverage(data, key.name, key.responseKey) ?: continue
                        if (read.fieldIsMissing) continue
                        keysByValue.getOrPut(read.value) { mutableListOf() } += key
                    }
                }
            }
            for ((value, keys) in keysByValue) {
                val subtree = buildValue(value, keys.map(returnedSelections::getValue).fold(KeyTree.empty, KeyTree::plus), resolutionPolicy)
                keys.forEach { returnedFields[it] = subtree }
            }
        }

        return ReturnedCoverage.observed(type, returnedFields)
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
        resolutionPolicy: ResolutionPolicy,
    ): ReturnedCoverage =
        when (value) {
            null -> ReturnedCoverage.absent(selections.withinOutputSelectionSet(atOutputSelectionSetRoot = false, resolutionPolicy))
            is EngineObjectData -> build(value, selections, atOutputSelectionSetRoot = false, resolutionPolicy)
            is Iterable<*> -> buildIterable(value, selections, resolutionPolicy)
            else -> ReturnedCoverage.NoChildCoverage
        }

    private suspend fun buildIterable(
        values: Iterable<*>,
        selections: KeyTree,
        resolutionPolicy: ResolutionPolicy,
    ): ReturnedCoverage {
        var coverage = ReturnedCoverage.absent(
            selections.withinOutputSelectionSet(
                atOutputSelectionSetRoot = false,
                resolutionPolicy = resolutionPolicy,
            )
        )

        suspend fun visit(value: Any?) {
            when (value) {
                is Iterable<*> -> value.forEach { visit(it) }
                is EngineObjectData -> {
                    if (schema.getObjectType(value.type.name) == null) return
                    coverage = coverage.merge(
                        build(
                            data = value,
                            selections = selections,
                            atOutputSelectionSetRoot = false,
                            inheritedResolutionPolicy = resolutionPolicy,
                        )
                    )
                }
            }
        }

        visit(values)
        return coverage
    }

    private fun buildNodeReference(
        data: NodeEngineObjectData,
        selections: KeyTree,
        atOutputSelectionSetRoot: Boolean,
        resolutionPolicy: ResolutionPolicy,
    ): ReturnedCoverage {
        val type = schema.getObjectType(data.type.name) ?: return ReturnedCoverage.NoChildCoverage
        val idDefinition = type.getFieldDefinition("id") ?: return ReturnedCoverage.observed(type, emptyMap())
        val returnedSelections = selectionsForReturnedField(
            fieldDefinition = idDefinition,
            selections = selections.fieldsFor(type),
        ).filterKeys { key ->
            coverageFilter(resolutionPolicy)(type, key, atOutputSelectionSetRoot) != DROP
        }
        return ReturnedCoverage.observed(type, returnedSelections.mapValues { ReturnedCoverage.NoChildCoverage })
    }

    private fun coverageFilter(resolutionPolicy: ResolutionPolicy): KeyTreeFilter =
        if (resolutionPolicy == ResolutionPolicy.PARENT_MANAGED) FieldOutputSelectionSetFilter(HasResolver.Never) else outputSelectionSetFilter

    private fun KeyTree.withinOutputSelectionSet(
        atOutputSelectionSetRoot: Boolean,
        resolutionPolicy: ResolutionPolicy
    ): KeyTree =
        filter { type, key, atKeyTreeRoot ->
            coverageFilter(resolutionPolicy)(
                type,
                key,
                atOutputSelectionSetRoot && atKeyTreeRoot,
            )
        }.withoutEmptyTypeBranches()

    private fun KeyTree.fieldsFor(type: GraphQLObjectType): Map<ObjectEngineResult.Key, KeyTree> {
        val fieldsByType = keysByType()
        return fieldsByType[type]
            ?: fieldsByType.entries.firstOrNull { it.key.name == type.name }?.value
            ?: emptyMap()
    }
}

// Empty observed objects constrain coverage; nulls and absent concrete types do not.
private class ReturnedCoverage private constructor(
    private val fieldsByType: Map<GraphQLObjectType, Map<ObjectEngineResult.Key, ReturnedCoverage>> = emptyMap(),
    private val absentSelections: KeyTree = KeyTree.empty,
    private val blocksChildCoverage: Boolean = false,
) {
    fun merge(other: ReturnedCoverage): ReturnedCoverage = merge(other, IdentityHashMap())

    private fun merge(
        other: ReturnedCoverage,
        memo: IdentityHashMap<ReturnedCoverage, IdentityHashMap<ReturnedCoverage, ReturnedCoverage>>,
    ): ReturnedCoverage {
        if (this === other) return this
        if (blocksChildCoverage || other.blocksChildCoverage) return NoChildCoverage
        val byOther = memo.getOrPut(this) { IdentityHashMap() }
        byOther[other]?.let { return it }
        val merged = fieldsByType.toMutableMap()
        for ((type, right) in other.fieldsByType) {
            val left = merged[type]
            merged[type] = if (left == null) {
                right
            } else {
                left.keys.intersect(right.keys).associateWith { key -> left.getValue(key).merge(right.getValue(key), memo) }
            }
        }
        return ReturnedCoverage(merged, absentSelections + other.absentSelections).also { byOther[other] = it }
    }

    fun toKeyTree(): KeyTree = toKeyTree(IdentityHashMap())

    private fun toKeyTree(memo: IdentityHashMap<ReturnedCoverage, KeyTree>): KeyTree {
        memo[this]?.let { return it }
        val fields = absentSelections.keysByType().toMutableMap()
        fieldsByType.forEach { (type, returned) -> fields[type] = returned.mapValues { (_, children) -> children.toKeyTree(memo) } }
        return KeyTree(fields).also { memo[this] = it }
    }

    companion object {
        val NoChildCoverage = ReturnedCoverage(blocksChildCoverage = true)

        fun absent(selections: KeyTree): ReturnedCoverage = ReturnedCoverage(absentSelections = selections)

        fun observed(
            type: GraphQLObjectType,
            fields: Map<ObjectEngineResult.Key, ReturnedCoverage>,
        ): ReturnedCoverage = ReturnedCoverage(fieldsByType = mapOf(type to fields))
    }
}
