@file:Suppress("MatchingDeclarationName")

package model.parsing

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value as GraphQLValue
import graphql.language.VariableReference
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLScalarType
import graphql.schema.InputValueWithState
import model.ArgumentResolutionError
import model.Arguments
import model.EngineInputData
import model.EngineInputObjectData
import model.EngineSimpleData
import model.coerceArgumentExpression
import model.registry.ResolverTarget
import model.requireType
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.BitVector

internal data object ErroneousVariableValue

internal fun decodeInputValue(
    type: GraphQLInputType,
    value: InputValueWithState,
    variableValues: Map<String, EngineInputData?>,
    schema: ViaductSchema,
    variableTarget: ResolverTarget? = null,
): Any? =
    if (value.isLiteral) {
        decodeLiteral(
            type,
            value.value as GraphQLValue<*>,
            variableValues,
            schema,
            variableTarget,
        )
    } else {
        coerceArgumentExpression(
            decodeModelInputType(type, schema),
            decodeExternal(type, value.value, variableValues, schema),
        )
    }

internal fun decodeLiteral(
    type: GraphQLInputType,
    value: GraphQLValue<*>,
    variableValues: Map<String, EngineInputData?>,
    schema: ViaductSchema,
    variableTarget: ResolverTarget? = null,
): Any? {
    if (value is VariableReference) {
        return if (variableValues.containsKey(value.name)) {
            val bound = variableValues.getValue(value.name)
            if (bound === ErroneousVariableValue) {
                ArgumentResolutionError
            } else {
                coerceArgumentExpression(decodeModelInputType(type, schema), bound)
            }
        } else {
            requireNotNull(variableTarget) {
                "Unbound operation variable \$${value.name}"
            }
            Arguments.Variable.of(variableTarget, value.name)
        }
    }
    if (value is NullValue) return null

    return when (type) {
        is GraphQLNonNull ->
            decodeLiteral(
                type.wrappedType as GraphQLInputType,
                value,
                variableValues,
                schema,
                variableTarget,
            )
        is GraphQLList -> {
            val values = if (value is ArrayValue) value.values else listOf(value)
            coerceArgumentExpression(
                decodeModelInputType(type, schema),
                values.map {
                    decodeLiteral(
                        type.wrappedType as GraphQLInputType,
                        it,
                        variableValues,
                        schema,
                        variableTarget,
                    )
                },
            )
        }
        is GraphQLScalarType ->
            coerceArgumentExpression(
                decodeModelInputType(type, schema),
                decodeScalarLiteral(type.name, value),
            )
        is GraphQLEnumType ->
            coerceArgumentExpression(
                decodeModelInputType(type, schema),
                (value as EnumValue).name,
            )
        is GraphQLInputObjectType ->
            decodeObjectLiteral(type, value as ObjectValue, variableValues, schema, variableTarget)
        else -> error("Unexpected input type: $type")
    }
}

private fun decodeScalarLiteral(
    scalarName: String,
    value: GraphQLValue<*>,
): EngineSimpleData =
    when (scalarName) {
        "Int" -> (value as IntValue).value.intValueExact()
        "Float" ->
            when (value) {
                is FloatValue -> value.value.toDouble()
                is IntValue -> value.value.toDouble()
                else -> error("Invalid Float literal: $value")
            }
        "String" -> (value as StringValue).value!!
        "Boolean" -> (value as BooleanValue).isValue
        "ID" ->
            when (value) {
                is StringValue -> value.value!!
                is IntValue -> value.value.toString()
                else -> error("Invalid ID literal: $value")
            }
        else -> error("Unsupported scalar: $scalarName")
    }

private inline fun decodeInputObjectFields(
    type: GraphQLInputObjectType,
    isFieldSupplied: (String) -> Boolean,
    decodeSupplied: (GraphQLInputType, String) -> Any?,
    variableValues: Map<String, EngineInputData?>,
    schema: ViaductSchema,
): Any {
    val fields =
        buildMap<String, Any?> {
            type.fieldDefinitions.forEach { field ->
                when {
                    isFieldSupplied(field.name) ->
                        put(field.name, decodeSupplied(field.type, field.name))
                    field.hasSetDefaultValue() ->
                        put(
                            field.name,
                            decodeInputValue(
                                field.type,
                                field.inputFieldDefaultValue,
                                variableValues,
                                schema,
                            ),
                        )
                }
            }
        }
    return requireNotNull(
        coerceArgumentExpression(
            ViaductSchema.TypeExpr(
                schema.requireType(type.name) as ViaductSchema.Input,
            ),
            fields,
        ),
    )
}

private fun decodeObjectLiteral(
    type: GraphQLInputObjectType,
    value: ObjectValue,
    variableValues: Map<String, EngineInputData?>,
    schema: ViaductSchema,
    variableTarget: ResolverTarget?,
): Any {
    val suppliedFields = value.objectFields.associateBy { it.name }
    return decodeInputObjectFields(
        type = type,
        isFieldSupplied = suppliedFields::containsKey,
        decodeSupplied = { fieldType, fieldName ->
            decodeLiteral(
                fieldType,
                suppliedFields.getValue(fieldName).value,
                variableValues,
                schema,
                variableTarget,
            )
        },
        variableValues = variableValues,
        schema = schema,
    )
}

private fun decodeExternal(
    type: GraphQLInputType,
    value: Any?,
    variableValues: Map<String, EngineInputData?>,
    schema: ViaductSchema,
): EngineInputData? {
    if (value == null) return null

    return when (type) {
        is GraphQLNonNull ->
            decodeExternal(type.wrappedType as GraphQLInputType, value, variableValues, schema)
        is GraphQLList -> {
            val values = if (value is Iterable<*>) value.toList() else listOf(value)
            values.map {
                decodeExternal(
                    type.wrappedType as GraphQLInputType,
                    it,
                    variableValues,
                    schema,
                )
            }
        }
        is GraphQLScalarType -> decodeScalarExternal(type.name, value)
        is GraphQLEnumType -> value.toString()
        is GraphQLInputObjectType -> decodeObjectExternal(type, value, variableValues, schema)
        else -> error("Unexpected input type: $type")
    }
}

internal fun decodeExternalInputValue(
    type: GraphQLInputType,
    value: Any?,
    schema: ViaductSchema,
): Any? =
    coerceArgumentExpression(
        decodeModelInputType(type, schema),
        decodeExternal(type, value, emptyMap(), schema),
    )

private fun decodeScalarExternal(
    scalarName: String,
    value: Any,
): EngineSimpleData =
    when (scalarName) {
        "Int" -> (value as Number).toInt()
        "Float" -> (value as Number).toDouble()
        "String" -> value as String
        "Boolean" -> value as Boolean
        "ID" -> value.toString()
        else -> error("Unsupported scalar: $scalarName")
    }

private fun decodeModelInputType(
    type: GraphQLInputType,
    schema: ViaductSchema,
): ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef> {
    val listNullabilities = mutableListOf<Boolean>()
    var current: graphql.schema.GraphQLType = type
    var nullable = true
    while (true) {
        when (current) {
            is GraphQLNonNull -> {
                nullable = false
                current = current.wrappedType
            }
            is GraphQLList -> {
                listNullabilities += nullable
                nullable = true
                current = current.wrappedType
            }
            is GraphQLNamedType -> {
                val wrappers = BitVector(listNullabilities.size)
                listNullabilities.forEachIndexed { index, wrapperNullable ->
                    if (wrapperNullable) wrappers.set(index)
                }
                return ViaductSchema.TypeExpr(
                    schema.requireType(current.name) as ViaductSchema.InputTypeDef,
                    nullable,
                    wrappers,
                )
            }
            else -> error("Unexpected input type: $current")
        }
    }
}

private fun decodeObjectExternal(
    type: GraphQLInputObjectType,
    value: Any,
    variableValues: Map<String, EngineInputData?>,
    schema: ViaductSchema,
): EngineInputObjectData {
    val valueMap = value as Map<*, *>
    val decoded =
        decodeInputObjectFields(
            type = type,
            isFieldSupplied = valueMap::containsKey,
            decodeSupplied = { fieldType, fieldName ->
                coerceArgumentExpression(
                    decodeModelInputType(fieldType, schema),
                    decodeExternal(fieldType, valueMap[fieldName], variableValues, schema),
                )
            },
            variableValues = variableValues,
            schema = schema,
        )
    require(decoded is Map<*, *>)
    @Suppress("UNCHECKED_CAST")
    return decoded as EngineInputObjectData
}
