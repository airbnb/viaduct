package viaduct.graphql.schema.graphqljava

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.InputValueWithState
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import viaduct.graphql.schema.ViaductSchema

// =========================================================================
// Conversion functions between graphql.language.Value and ViaductSchema.Literal
// =========================================================================

/**
 * Converts a graphql-java [Value] to a [ViaductSchema.Literal].
 */
fun Value<*>.toViaductValue(): ViaductSchema.Literal =
    when (this) {
        is NullValue -> ViaductSchema.NULL
        is BooleanValue -> if (isValue) ViaductSchema.TRUE else ViaductSchema.FALSE
        is StringValue -> ViaductSchema.StringLiteral.of(value)
        is IntValue -> ViaductSchema.IntLiteral.of(value.toString())
        is FloatValue -> ViaductSchema.FloatLiteral.of(value.toString())
        is EnumValue -> ViaductSchema.EnumLit.of(name)
        is ArrayValue -> ViaductSchema.ListLiteral.of(values.map { it.toViaductValue() })
        is ObjectValue -> ViaductSchema.ObjectLiteral.of(
            objectFields.associate { it.name to it.value.toViaductValue() }
        )
        else -> throw IllegalArgumentException("Unknown Value type: ${this::class.java}")
    }

/**
 * Converts a [ViaductSchema.Literal] to a graphql-java [Value].
 */
fun ViaductSchema.Literal.toGraphQLJavaValue(): Value<*> =
    when (this) {
        is ViaductSchema.NullLiteral -> NullValue.of()
        is ViaductSchema.TrueLiteral -> BooleanValue.newBooleanValue(true).build()
        is ViaductSchema.FalseLiteral -> BooleanValue.newBooleanValue(false).build()
        is ViaductSchema.StringLiteral -> StringValue.newStringValue(value).build()
        is ViaductSchema.IntLiteral -> IntValue.newIntValue(value).build()
        is ViaductSchema.FloatLiteral -> FloatValue.newFloatValue(value).build()
        is ViaductSchema.EnumLit -> EnumValue.newEnumValue(value).build()
        is ViaductSchema.ListLiteral -> ArrayValue.newArrayValue()
            .values(map { it.toGraphQLJavaValue() })
            .build()
        is ViaductSchema.ObjectLiteral -> ObjectValue.newObjectValue()
            .objectFields(entries.map { ObjectField(it.key, it.value.toGraphQLJavaValue()) })
            .build()
    }

// =========================================================================
// ValueConverter - converts InputValueWithState to ViaductSchema.Literal
// =========================================================================

/**
 * Converts graphql-java [InputValueWithState] and [Value] objects found in schema definitions
 * (default values, applied-directive arguments) into [ViaductSchema.Literal] representations.
 */
internal object ValueConverter {
    /**
     * Convert an [InputValueWithState] to a [ViaductSchema.Literal].
     * Handles literal values, external values, and unset values.
     *
     * Returns null for unset values (indicating no default), or a [ViaductSchema.Literal] for set values.
     */
    fun convert(
        type: ViaductSchema.TypeExpr<*>,
        valueWithState: InputValueWithState
    ): ViaductSchema.Literal? =
        when {
            valueWithState.isNotSet -> null
            valueWithState.isLiteral ->
                // GJ uses "Literal" values for describing values that have been parsed into a
                // [graphql.language.Value]. A common source for these values are values that
                // are defined in SDL, such as default values for arguments and input fields.
                (valueWithState.value as Value<*>).toViaductValue()
            valueWithState.isExternal ->
                // Map external values to Value before passing to [convert]
                externalToViaductValue(type, valueWithState.value)
            else -> throw IllegalArgumentException("unsupported value state")
        }

    /**
     * Convert a literal graphql-java [Value] to a [ViaductSchema.Literal].
     */
    fun convert(
        @Suppress("UNUSED_PARAMETER") type: ViaductSchema.TypeExpr<*>,
        value: Value<*>
    ): ViaductSchema.Literal = value.toViaductValue()

    /**
     * Returns the expected Java class for a converted value of the given type.
     * Used by [GJSchemaCheck] to validate that conversions produce expected types.
     *
     * Returns ViaductSchema.Literal classes rather than graphql.language.Value classes.
     */
    fun javaClassFor(type: ViaductSchema.TypeExpr<*>): Class<*> =
        when {
            type.isList -> ViaductSchema.ListLiteral::class.java
            type.baseTypeDef is ViaductSchema.Enum -> ViaductSchema.EnumLit::class.java
            type.baseTypeDef is ViaductSchema.Input -> ViaductSchema.ObjectLiteral::class.java
            type.baseTypeDef is ViaductSchema.Scalar -> scalarValueClass(type.baseTypeDef.name)
            else -> throw IllegalArgumentException("Bad type for default (${type.baseTypeDef}).")
        }

    /**
     * Returns the expected ViaductSchema.Literal subclass for a scalar type name.
     *
     * For built-in GraphQL scalars, returns the specific value class.
     * For custom scalars, returns [ViaductSchema.Literal] as the base class since custom
     * scalars can use any GraphQL literal syntax in SDL - not just scalar
     * literals (Int, Float, String, Boolean) but also Object literals
     * (for JSON-like scalars), Array literals, and Enum literals.
     */
    private fun scalarValueClass(scalarName: String): Class<out ViaductSchema.Literal> =
        when (scalarName) {
            // Built-in GraphQL scalars have specific value types
            "Boolean" -> ViaductSchema.BooleanLiteral::class.java
            "Float" -> ViaductSchema.FloatLiteral::class.java
            "Int" -> ViaductSchema.IntLiteral::class.java
            "ID", "String" -> ViaductSchema.StringLiteral::class.java
            // Custom scalars can use any GraphQL literal syntax
            else -> ViaductSchema.Literal::class.java
        }

    /**
     * Maps "external" values (programmatically defined, not parsed from SDL)
     * to [ViaductSchema.Literal]. Used when handling [InputValueWithState.isExternal].
     *
     * GJ uses "External" values to describe values that have not been parsed into a
     * [graphql.language.Value]. A common source for these are input values
     * that have been defined "programmatically", like with
     * [graphql.schema.GraphQLArgument.Builder.defaultValueProgrammatic].
     * In particular, these values appear around arguments to built-in
     * directives, which may not be defined by SDL.
     */
    internal fun externalToViaductValue(
        type: ViaductSchema.TypeExpr<*>,
        value: Any?,
        listDepth: Int = 0,
    ): ViaductSchema.Literal {
        if (value == null) {
            require(type.nullableAtDepth(listDepth)) {
                "$type not nullable at depth $listDepth"
            }
            return ViaductSchema.NULL
        }

        if (listDepth < type.listDepth) {
            require(value is List<*>) {
                "$type not List at depth $listDepth ($value)"
            }
            return ViaductSchema.ListLiteral.of(
                value.map { externalToViaductValue(type, it, listDepth + 1) }
            )
        }

        return when (val bt = type.baseTypeDef) {
            is ViaductSchema.Scalar ->
                when (bt.name) {
                    "Boolean" -> if (value as Boolean) ViaductSchema.TRUE else ViaductSchema.FALSE
                    "Date" -> ViaductSchema.StringLiteral.of((value as LocalDate).toString())
                    "DateTime" -> ViaductSchema.StringLiteral.of((value as Instant).toString())
                    "Float" -> ViaductSchema.FloatLiteral.of(BigDecimal(value.toString()).toString())
                    "Int", "Long", "Short" -> ViaductSchema.IntLiteral.of(BigInteger(value.toString()).toString())
                    "ID" -> ViaductSchema.StringLiteral.of(value as String)
                    "String" -> ViaductSchema.StringLiteral.of(value as String)
                    "Time" -> ViaductSchema.StringLiteral.of((value as OffsetTime).toString())
                    else -> throw UnsupportedOperationException("Can't convert $value to $type")
                }
            else -> throw UnsupportedOperationException("Can't convert $value to $type")
        }
    }

    // Legacy function for backward compatibility during migration
    internal fun externalToLiteral(
        type: ViaductSchema.TypeExpr<*>,
        value: Any?,
        listDepth: Int = 0,
    ): Value<*> = externalToViaductValue(type, value, listDepth).toGraphQLJavaValue()
}
