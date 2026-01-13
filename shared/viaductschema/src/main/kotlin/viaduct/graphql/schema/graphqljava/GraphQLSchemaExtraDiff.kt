package viaduct.graphql.schema.graphqljava

import graphql.language.AstPrinter
import graphql.language.Value
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import viaduct.invariants.InvariantChecker

/**
 * Compares graphql-java-specific runtime properties that aren't captured in ViaductSchema.
 *
 * This diff complements the ViaductSchema-based SchemaDiff by checking runtime properties
 * that graphql-java's schema builder sets but that aren't part of the abstract schema model:
 * - deprecationReason on fields, enum values, arguments, and input fields
 * - default values on arguments and input fields
 *
 * These properties are important for correct runtime behavior (e.g., introspection queries).
 */
class GraphQLSchemaExtraDiff(
    private val expected: GraphQLSchema,
    private val actual: GraphQLSchema,
    private val checker: InvariantChecker = InvariantChecker()
) {
    private var done = false

    fun diff(): InvariantChecker {
        if (!done) {
            compareTypes()
            done = true
        }
        return checker
    }

    private fun compareTypes() {
        // Get all type names from both schemas (excluding introspection types)
        val expectedTypes = expected.allTypesAsList.filter { !it.name.startsWith("__") }
        val actualTypes = actual.allTypesAsList.filter { !it.name.startsWith("__") }

        val expectedTypeMap = expectedTypes.associateBy { it.name }
        val actualTypeMap = actualTypes.associateBy { it.name }

        // Compare types that exist in both schemas
        val commonTypeNames = expectedTypeMap.keys.intersect(actualTypeMap.keys)

        for (typeName in commonTypeNames) {
            checker.pushContext(typeName)
            try {
                val expectedType = expectedTypeMap[typeName]!!
                val actualType = actualTypeMap[typeName]!!

                when (expectedType) {
                    is GraphQLObjectType -> {
                        if (actualType is GraphQLObjectType) {
                            compareObjectType(expectedType, actualType)
                        }
                    }
                    is GraphQLInterfaceType -> {
                        if (actualType is GraphQLInterfaceType) {
                            compareInterfaceType(expectedType, actualType)
                        }
                    }
                    is GraphQLInputObjectType -> {
                        if (actualType is GraphQLInputObjectType) {
                            compareInputObjectType(expectedType, actualType)
                        }
                    }
                    is GraphQLEnumType -> {
                        if (actualType is GraphQLEnumType) {
                            compareEnumType(expectedType, actualType)
                        }
                    }
                }
            } finally {
                checker.popContext()
            }
        }
    }

    private fun compareObjectType(
        expected: GraphQLObjectType,
        actual: GraphQLObjectType
    ) {
        val expectedFields = expected.fieldDefinitions.associateBy { it.name }
        val actualFields = actual.fieldDefinitions.associateBy { it.name }

        for (fieldName in expectedFields.keys.intersect(actualFields.keys)) {
            checker.pushContext(fieldName)
            try {
                compareFieldDefinition(expectedFields[fieldName]!!, actualFields[fieldName]!!)
            } finally {
                checker.popContext()
            }
        }
    }

    private fun compareInterfaceType(
        expected: GraphQLInterfaceType,
        actual: GraphQLInterfaceType
    ) {
        val expectedFields = expected.fieldDefinitions.associateBy { it.name }
        val actualFields = actual.fieldDefinitions.associateBy { it.name }

        for (fieldName in expectedFields.keys.intersect(actualFields.keys)) {
            checker.pushContext(fieldName)
            try {
                compareFieldDefinition(expectedFields[fieldName]!!, actualFields[fieldName]!!)
            } finally {
                checker.popContext()
            }
        }
    }

    private fun compareInputObjectType(
        expected: GraphQLInputObjectType,
        actual: GraphQLInputObjectType
    ) {
        val expectedFields = expected.fieldDefinitions.associateBy { it.name }
        val actualFields = actual.fieldDefinitions.associateBy { it.name }

        for (fieldName in expectedFields.keys.intersect(actualFields.keys)) {
            checker.pushContext(fieldName)
            try {
                compareInputObjectField(expectedFields[fieldName]!!, actualFields[fieldName]!!)
            } finally {
                checker.popContext()
            }
        }
    }

    private fun compareEnumType(
        expected: GraphQLEnumType,
        actual: GraphQLEnumType
    ) {
        val expectedValues = expected.values.associateBy { it.name }
        val actualValues = actual.values.associateBy { it.name }

        for (valueName in expectedValues.keys.intersect(actualValues.keys)) {
            checker.pushContext(valueName)
            try {
                compareEnumValue(expectedValues[valueName]!!, actualValues[valueName]!!)
            } finally {
                checker.popContext()
            }
        }
    }

    private fun compareFieldDefinition(
        expected: GraphQLFieldDefinition,
        actual: GraphQLFieldDefinition
    ) {
        // Compare deprecation
        checker.isEqualTo(
            expected.isDeprecated,
            actual.isDeprecated,
            "FIELD_IS_DEPRECATED"
        )
        checker.isEqualTo(
            expected.deprecationReason,
            actual.deprecationReason,
            "FIELD_DEPRECATION_REASON"
        )

        // Compare arguments
        val expectedArgs = expected.arguments.associateBy { it.name }
        val actualArgs = actual.arguments.associateBy { it.name }

        for (argName in expectedArgs.keys.intersect(actualArgs.keys)) {
            checker.pushContext(argName)
            try {
                compareArgument(expectedArgs[argName]!!, actualArgs[argName]!!)
            } finally {
                checker.popContext()
            }
        }
    }

    private fun compareArgument(
        expected: GraphQLArgument,
        actual: GraphQLArgument
    ) {
        // Compare deprecation
        checker.isEqualTo(
            expected.isDeprecated,
            actual.isDeprecated,
            "ARG_IS_DEPRECATED"
        )
        checker.isEqualTo(
            expected.deprecationReason,
            actual.deprecationReason,
            "ARG_DEPRECATION_REASON"
        )

        // Compare default values
        checker.isEqualTo(
            expected.hasSetDefaultValue(),
            actual.hasSetDefaultValue(),
            "ARG_HAS_DEFAULT_VALUE"
        )
        if (expected.hasSetDefaultValue() && actual.hasSetDefaultValue()) {
            // Compare the actual default values
            // Using argumentDefaultValue which returns InputValueWithState
            val expectedDefault = expected.argumentDefaultValue
            val actualDefault = actual.argumentDefaultValue
            compareDefaultValues(expectedDefault.value, actualDefault.value, "ARG_DEFAULT_VALUE")
        }
    }

    private fun compareInputObjectField(
        expected: GraphQLInputObjectField,
        actual: GraphQLInputObjectField
    ) {
        // Compare deprecation
        checker.isEqualTo(
            expected.isDeprecated,
            actual.isDeprecated,
            "INPUT_FIELD_IS_DEPRECATED"
        )
        checker.isEqualTo(
            expected.deprecationReason,
            actual.deprecationReason,
            "INPUT_FIELD_DEPRECATION_REASON"
        )

        // Compare default values
        checker.isEqualTo(
            expected.hasSetDefaultValue(),
            actual.hasSetDefaultValue(),
            "INPUT_FIELD_HAS_DEFAULT_VALUE"
        )
        if (expected.hasSetDefaultValue() && actual.hasSetDefaultValue()) {
            val expectedDefault = expected.inputFieldDefaultValue
            val actualDefault = actual.inputFieldDefaultValue
            compareDefaultValues(expectedDefault.value, actualDefault.value, "INPUT_FIELD_DEFAULT_VALUE")
        }
    }

    private fun compareEnumValue(
        expected: GraphQLEnumValueDefinition,
        actual: GraphQLEnumValueDefinition
    ) {
        // Compare deprecation
        checker.isEqualTo(
            expected.isDeprecated,
            actual.isDeprecated,
            "ENUM_VALUE_IS_DEPRECATED"
        )
        checker.isEqualTo(
            expected.deprecationReason,
            actual.deprecationReason,
            "ENUM_VALUE_DEPRECATION_REASON"
        )
    }

    /**
     * Compares two default values semantically.
     *
     * graphql-java AST Value objects like NullValue, ArrayValue, ObjectValue
     * don't implement proper equals, so we compare their string representations.
     */
    private fun compareDefaultValues(
        expected: Any?,
        actual: Any?,
        tag: String
    ) {
        val expectedStr = when (expected) {
            is Value<*> -> AstPrinter.printAst(expected)
            else -> expected?.toString()
        }
        val actualStr = when (actual) {
            is Value<*> -> AstPrinter.printAst(actual)
            else -> actual?.toString()
        }
        checker.isEqualTo(expectedStr, actualStr, tag)
    }
}
