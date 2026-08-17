package viaduct.graphql.schema

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import java.util.Collections
import kotlin.jvm.javaObjectType
import kotlin.reflect.KClass
import viaduct.apiannotations.InternalApi

val baseGraphqlScalarTypeMapping = mapOf<String, KClass<*>>(
    "BigDecimal" to BigDecimal::class,
    "BigInteger" to BigInteger::class,
    "Boolean" to Boolean::class,
    "Byte" to Byte::class,
    "Date" to LocalDate::class,
    "DateTime" to Instant::class,
    "Float" to Double::class,
    "Int" to Int::class,
    "JSON" to Any::class,
    "Long" to Long::class,
    "Short" to Short::class,
    "String" to String::class,
    "Time" to OffsetTime::class,
)

/** Java-friendly boxed classes for the canonical GraphQL scalar mapping. */
@InternalApi
val baseGraphqlScalarJavaTypeMapping: Map<String, Class<*>> =
    Collections.unmodifiableMap(
        baseGraphqlScalarTypeMapping.mapValues { (_, type) -> type.javaObjectType }
    )
