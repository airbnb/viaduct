package viaduct.tenant.runtime.jvm

import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import java.util.Collections
import viaduct.apiannotations.InternalApi

@InternalApi
object InputDataValidator {
    fun interface InputDataAdapter {
        fun inputDataOrNull(value: Any): Map<String, Any?>?
    }

    @JvmStatic
    fun validateOneOf(
        typeName: String,
        data: Map<String, Any?>
    ) {
        check(data.size == 1) {
            "Exactly one field must be set for @oneOf type $typeName, but ${data.size} were: ${data.keys.toList()}"
        }
        val only = data.entries.first()
        check(only.value != null) {
            "Field '${only.key}' for @oneOf type $typeName must have a non-null value"
        }
    }

    @JvmStatic
    fun validateFields(
        type: GraphQLInputObjectType,
        data: Map<String, Any?>,
        path: String = type.name
    ) {
        if (type.isOneOf) {
            validateOneOf(path, data.filterKeys { type.getField(it) != null })
        }
        type.fields.forEach { field ->
            if (GraphQLTypeUtil.isNonNull(field.type)) {
                if (!data.containsKey(field.name)) {
                    check(field.hasSetDefaultValue()) { "Field $path.${field.name} is required" }
                } else {
                    check(data[field.name] != null) { "Field $path.${field.name} is required" }
                }
            }
        }
    }

    // Defaults stay omitted, and scalar/enum/ID values stay in their original representation.
    @JvmStatic
    fun validateAndCopy(
        type: GraphQLInputObjectType,
        data: Map<String, Any?>,
        adapter: InputDataAdapter
    ): Map<String, Any?> = copyInput(type, data, type.name, adapter)

    private fun copyInput(
        type: GraphQLInputObjectType,
        data: Map<String, Any?>,
        path: String,
        adapter: InputDataAdapter
    ): Map<String, Any?> {
        validateFields(type, data, path)
        return Collections.unmodifiableMap(
            data.mapValues { (name, value) ->
                val field = type.getField(name)
                if (field == null) value else copyValue(field.type, value, "$path.$name", adapter)
            }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyValue(
        type: GraphQLType,
        value: Any?,
        path: String,
        adapter: InputDataAdapter
    ): Any? {
        if (value == null) {
            check(!GraphQLTypeUtil.isNonNull(type)) {
                "Field $path must not be null (type ${GraphQLTypeUtil.simplePrint(type)})"
            }
            return null
        }
        return when (val unwrapped = GraphQLTypeUtil.unwrapNonNull(type)) {
            is GraphQLList -> {
                check(value is List<*>) { "Field $path must be a list" }
                Collections.unmodifiableList(
                    value.mapIndexed { index, element ->
                        copyValue(unwrapped.wrappedType, element, "$path[$index]", adapter)
                    }
                )
            }
            is GraphQLInputObjectType -> {
                val data = adapter.inputDataOrNull(value) ?: (value as? Map<String, Any?>)
                check(data != null) { "Field $path must be an input of type ${unwrapped.name}" }
                copyInput(unwrapped, data, path, adapter)
            }
            else -> value
        }
    }
}
