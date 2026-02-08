@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.ACCESS_CHECK_SLOT
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.newCell
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.newForType

/**
 * Test helper for creating [ObjectEngineResultImpl] instances from map data.
 *
 * This is useful for testing resolvers and other code that consumes ObjectEngineResult
 * without needing to go through the full execution pipeline.
 *
 * NOTE: This is for testing only. Production code should not use these helpers.
 */
object ObjectEngineResultTestHelper {
    /**
     * Creates an ObjectEngineResultImpl from a map of data.
     *
     * This is a testing utility for creating ObjectEngineResult instances from
     * fully resolved data (e.g., from a test fixture or mock response).
     *
     * @param type The GraphQLObjectType that the data represents
     * @param data The data for this object
     * @param errors The errors from executing, represented as Pair(<path>, <Throwable>)
     * @param currentPath The current path of this object in the response
     * @param schema The schema that the query was executed against
     * @param selectionSet The selection set being resolved
     */
    fun newFromMap(
        type: GraphQLObjectType,
        data: Map<String, Any?>,
        errors: MutableList<Pair<String, Throwable>>,
        currentPath: List<String> = emptyList(),
        schema: ViaductSchema,
        selectionSet: RawSelectionSet,
    ): ObjectEngineResultImpl =
        newFromMap(
            type = type,
            data = data.rekey(type, selectionSet),
            errors = errors.map { ObjectEngineResult.Key(it.first) to it.second }.toMutableList(),
            currentPath = currentPath,
            schema = schema,
            selectionSet = selectionSet,
        )

    @JvmName("newFromMap2")
    fun newFromMap(
        type: GraphQLObjectType,
        data: Map<ObjectEngineResult.Key, Any?>,
        errors: MutableList<Pair<ObjectEngineResult.Key, Throwable>>,
        currentPath: List<String> = emptyList(),
        schema: ViaductSchema,
        selectionSet: RawSelectionSet
    ): ObjectEngineResultImpl {
        val result = newForType(type)
        // Since this OER is created from existing data, its resolution is already complete
        result.fieldResolutionState.complete(Unit)

        data.forEach { (key, value) ->
            val field = schema.schema.getFieldDefinition((type.name to key.name).gj)
            result.computeIfAbsent(key) { slotSetter ->
                val rawValue =
                    if (value == null) {
                        val pathString = currentPath.joinToString(".")
                        // Find matching errors for this path
                        val matchingErrors = errors.filter {
                            it.first.name.startsWith(pathString)
                        }

                        if (matchingErrors.isNotEmpty()) { // Complete with first matching error
                            Value.fromThrowable<Nothing>(matchingErrors.first().second).also {
                                errors.removeAll(matchingErrors)
                            }
                        } else { // Complete with null value
                            Value.fromValue(null)
                        }
                    } else {
                        Value.fromValue(
                            convertFieldValue(
                                key,
                                type,
                                field.type,
                                value,
                                errors,
                                currentPath + listOf(key.name),
                                schema,
                                selectionSet
                            )
                        )
                    }

                // This function is actually used for completed values, which we'll
                // store in the raw slot
                slotSetter.set(RAW_VALUE_SLOT, rawValue)
                slotSetter.set(ACCESS_CHECK_SLOT, Value.fromValue(null))
            }
        }
        return result
    }

    private fun convertFieldValue(
        key: ObjectEngineResult.Key,
        parentType: GraphQLOutputType,
        fieldType: GraphQLOutputType,
        value: Any?,
        errors: MutableList<Pair<ObjectEngineResult.Key, Throwable>>,
        currentPath: List<String>,
        schema: ViaductSchema,
        selectionSet: RawSelectionSet
    ): Any? {
        if (value == null) return null

        return when (val unwrappedType = GraphQLTypeUtil.unwrapNonNull(fieldType)) {
            // null, scalar and enum values pass through directly
            is GraphQLScalarType,
            is GraphQLEnumType -> value

            // Lists need each element converted
            is GraphQLList -> {
                val elementType = GraphQLTypeUtil.unwrapOne(unwrappedType) as GraphQLOutputType
                (value as List<*>).mapIndexed { idx, element ->
                    val rawValue = Value.fromValue(
                        convertFieldValue(
                            key,
                            parentType,
                            elementType,
                            element,
                            errors,
                            currentPath + listOf(idx.toString()),
                            schema,
                            selectionSet
                        )
                    )
                    newCell { slotSetter ->
                        slotSetter.set(RAW_VALUE_SLOT, rawValue)
                        slotSetter.set(ACCESS_CHECK_SLOT, Value.fromValue(null))
                    }
                }
            }

            // Objects become nested ObjectEngineResults
            is GraphQLObjectType -> {
                val subSelectionSet = selectionSet.selectionSetForSelection(
                    (parentType as GraphQLCompositeType).name,
                    key.alias ?: key.name
                )
                newFromMap(
                    unwrappedType,
                    (value as Map<*, Any?>).rekey(unwrappedType, subSelectionSet),
                    errors,
                    currentPath,
                    schema,
                    subSelectionSet
                )
            }

            // Interfaces and unions need concrete type resolution
            is GraphQLInterfaceType,
            is GraphQLUnionType -> {
                @Suppress("UNCHECKED_CAST")
                val valueMap = value as Map<String, Any?>
                val typeName = valueMap["__typename"] as String
                val concreteType = schema.schema.getObjectType(typeName)
                val subSelectionSet = selectionSet.selectionSetForSelection(
                    (parentType as GraphQLCompositeType).name,
                    key.alias ?: key.name
                )

                newFromMap(
                    concreteType,
                    valueMap.rekey(concreteType, subSelectionSet),
                    errors,
                    currentPath,
                    schema,
                    subSelectionSet
                )
            }

            else -> throw IllegalStateException(
                "Unexpected type ${GraphQLTypeUtil.simplePrint(unwrappedType)}"
            )
        }
    }

    /**
     * Rekeys a map of data to [ObjectEngineResult.Key]s based on the provided selection set and object type.
     * This allows us to handle field aliases and arguments correctly; ignoring this and simply using the key name
     * can lead to mismatched keys, which in turn can lead to the engine resolution hanging.
     *
     * Note that this rekeys just the top-level keys of the map; nested objects will not be rekeyed.
     */
    private fun Map<*, Any?>.rekey(
        type: GraphQLObjectType,
        selectionSet: RawSelectionSet
    ): Map<ObjectEngineResult.Key, Any?> {
        if (keys.all { it is ObjectEngineResult.Key }) {
            @Suppress("UNCHECKED_CAST")
            return this as Map<ObjectEngineResult.Key, Any?>
        }

        val selectionsByName = selectionSet.selections().associateBy { it.selectionName }

        val map = mutableMapOf<ObjectEngineResult.Key, Any?>()
        forEach { (key, value) ->
            val keyString = requireNotNull(key as? String) {
                "Cannot rekey a map with keys of type ${key?.javaClass?.name}"
            }
            selectionsByName[keyString]?.let { sel ->
                val arguments = selectionSet.argumentsOfSelection(type.name, sel.selectionName) ?: emptyMap()
                val objectEngineResultKey = ObjectEngineResult.Key(name = sel.fieldName, alias = sel.selectionName, arguments = arguments)
                map[objectEngineResultKey] = value
            }
        }
        return map.toMap()
    }
}
