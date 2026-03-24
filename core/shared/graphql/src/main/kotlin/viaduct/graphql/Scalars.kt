package viaduct.graphql

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.scalars.ExtendedScalars
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import java.lang.ArithmeticException
import java.lang.NumberFormatException
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * Definitions of non-standard scalars supported by Viaduct, plus the list
 * of all scalars support by viaduct ([viaductScalars]).
 */
object Scalars {
    /**
     * Extends DateTime from `graphql-extended-scalars` package to support Java `Instant`, which is used commonly
     * for date/time values.
     */
    val DateTimeScalar: GraphQLScalarType =
        GraphQLScalarType
            .newScalar()
            .name(ExtendedScalars.DateTime.name)
            .description(ExtendedScalars.DateTime.description)
            .coercing(
                object : Coercing<Any, Any> {
                    override fun serialize(
                        input: Any,
                        ctx: GraphQLContext,
                        locale: Locale
                    ): Any? =
                        if (input is Instant) {
                            ExtendedScalars.DateTime.coercing
                                .serialize(
                                    convertToOffsetDateTime(input),
                                    ctx,
                                    locale
                                )
                        } else {
                            ExtendedScalars.DateTime.coercing.serialize(input, ctx, locale)
                        }

                    override fun parseValue(
                        input: Any,
                        ctx: GraphQLContext,
                        locale: Locale
                    ): Any? =
                        if (input is Instant) {
                            ExtendedScalars.DateTime.coercing
                                .parseValue(
                                    convertToOffsetDateTime(input),
                                    ctx,
                                    locale
                                )
                        } else {
                            ExtendedScalars.DateTime.coercing.parseValue(input, ctx, locale)
                        }

                    override fun parseLiteral(
                        input: Value<*>,
                        coercedVariables: CoercedVariables,
                        ctx: GraphQLContext,
                        locale: Locale
                    ): Any? =
                        ExtendedScalars.DateTime.coercing.parseLiteral(
                            input,
                            coercedVariables,
                            ctx,
                            locale
                        )

                    private fun convertToOffsetDateTime(value: Any): OffsetDateTime = OffsetDateTime.ofInstant(value as Instant?, ZoneOffset.UTC)
                }
            ).build()

    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)

    /**
     * This represents the "Long" type which is a representation of `java.lang.Long`.
     *
     * This custom scalar is a fork of
     * https://github.com/graphql-java/graphql-java/blob/3ea1c1a643977bf173e2a835c86d6a14f9b51794/src/main/java/\
     *   graphql/Scalars.java#L324
     *
     * graphql-java chooses to represent longs as numbers in JSON. This works fine for many
     * languages, but not JavaScript. JavaScript will lose precision at ~2^52. We need to serialize long values to
     * strings instead.
     */
    val GraphQLLong: GraphQLScalarType =
        GraphQLScalarType
            .newScalar()
            .name("Long")
            .description("Long type that serializes/deserializes to/from a string.")
            .coercing(
                object : Coercing<Any?, Any?> {
                    private fun convertImpl(input: Any): Long? {
                        return when {
                            input is Long -> {
                                input
                            }
                            isNumberIsh(input) -> {
                                val value =
                                    try {
                                        BigDecimal(input.toString())
                                    } catch (_: NumberFormatException) {
                                        return null
                                    }
                                try {
                                    value.longValueExact()
                                } catch (_: ArithmeticException) {
                                    null
                                }
                            }
                            else -> {
                                null
                            }
                        }
                    }

                    override fun serialize(
                        input: Any,
                        ctx: GraphQLContext,
                        locale: Locale
                    ): String {
                        val result =
                            convertImpl(input)
                                ?: throw CoercingSerializeException(
                                    "Expected type 'Long' but was '" + typeName(input) + "'."
                                )
                        return result.toString()
                    }

                    override fun parseValue(
                        input: Any,
                        ctx: GraphQLContext,
                        locale: Locale
                    ): Long =
                        convertImpl(input)
                            ?: throw CoercingParseValueException(
                                "Expected type 'Long' but was '" + typeName(input) + "'."
                            )

                    override fun parseLiteral(
                        input: Value<*>,
                        coercedVariables: CoercedVariables,
                        ctx: GraphQLContext,
                        locale: Locale
                    ): Long {
                        fun fail(message: String): Nothing = throw CoercingParseLiteralException(message)

                        return when (input) {
                            is StringValue ->
                                input.value?.toLongOrNull()
                                    ?: fail("Expected value to be a Long but it was '${input.value}'")

                            is IntValue -> {
                                val big = input.value
                                if (big !in LONG_MIN..LONG_MAX) {
                                    fail("Expected value to be in the Long range but it was '$big'")
                                }
                                big.toLong()
                            }

                            else -> fail("Expected AST type 'IntValue' or 'StringValue' but was '${typeName(input)}'.")
                        }
                    }
                }
            ).build()

    /**
     * Custom scalar type for private fields in Viaduct Modern.
     * Exist here only for parsing the schema properly, especially in Viaduct Classic.
     * The coercing functions are no-op because the scalar type is not used in
     * Viaduct Classic, and the private fields don't need coercing in Viaduct Modern.
     */
    @Suppress("TooGenericExceptionThrown")
    val BackingData: GraphQLScalarType =
        GraphQLScalarType
            .newScalar()
            .name("BackingData")
            .description("Custom scalar type for private fields")
            .coercing(
                object : Coercing<Any?, Any?> {
                    override fun serialize(
                        input: Any,
                        ctx: GraphQLContext,
                        locale: Locale
                    ): Any = throw Exception("serialize should not be called for BackingData scalar type. This is a no-op.")

                    override fun parseValue(
                        input: Any,
                        ctx: GraphQLContext,
                        locale: Locale
                    ): Any = throw Exception("parseValue should not be called for BackingData scalar type. This is a no-op.")

                    override fun parseLiteral(
                        input: Value<*>,
                        coercedVariables: CoercedVariables,
                        ctx: GraphQLContext,
                        locale: Locale
                    ): Any = throw Exception("parseLiteral should not be called for BackingData scalar type. This is a no-op.")
                }
            ).build()

    /**
     * The list of scalars supported by Viaduct (not including built-in scalars).
     */
    val viaductStandardScalars = setOf(
        ExtendedScalars.Date,
        ExtendedScalars.GraphQLByte,
        ExtendedScalars.GraphQLShort,
        ExtendedScalars.Json,
        ExtendedScalars.Time,
        BackingData,
        DateTimeScalar,
        GraphQLLong,
    )

    private fun isNumberIsh(input: Any): Boolean = input is Number || input is String

    private fun typeName(input: Any?): String =
        if (input == null) {
            "null"
        } else {
            input.javaClass.simpleName
        }
}
