package viaduct.graphql.schema

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import kotlin.reflect.KClass

val baseGraphqlScalarTypeMapping = mapOf<String, KClass<*>>(
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
