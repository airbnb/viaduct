package viaduct.engine.runtime.graphql_java

import graphql.GraphQLContext
import graphql.Scalars as GJScalars
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputType
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import viaduct.graphql.Scalars

class LegacyInputInterceptorTest {
    private fun assertIntercept(
        expected: Any?,
        originalValue: Any?,
        type: GraphQLInputType
    ) {
        val intercepted = LegacyInputInterceptor.intercept(originalValue, type, GraphQLContext.getDefault(), Locale.getDefault())
        assertEquals(expected, intercepted)
    }

    @Test
    fun `DateTime`() {
        assertIntercept(null, null, Scalars.DateTimeScalar)
        assertIntercept(
            "1970-01-01T00:00:00.000Z",
            Instant.EPOCH,
            Scalars.DateTimeScalar
        )

        assertIntercept(
            "1970-01-01T00:00:00.000Z",
            OffsetDateTime.of(LocalDateTime.ofEpochSecond(0L, 0, ZoneOffset.UTC), ZoneOffset.UTC),
            Scalars.DateTimeScalar
        )
    }

    @Test
    fun `Long`() {
        assertIntercept(null, null, Scalars.GraphQLLong)
        assertIntercept("0", 0L, Scalars.GraphQLLong)
        assertIntercept("9223372036854775807", Long.MAX_VALUE, Scalars.GraphQLLong)
    }

    private enum class Enum { A, B }

    @Test
    fun `enum`() {
        GraphQLEnumType.newEnum()
            .name("Enum")
            .value("A")
            .value("B")
            .build()
            .let { type ->
                assertIntercept("A", Enum.A, type)
                assertIntercept("B", Enum.B, type)
            }
    }

    @Test
    fun `builtins`() {
        assertIntercept(0, "0", GJScalars.GraphQLInt)
        assertIntercept(true, "true", GJScalars.GraphQLBoolean)
        assertIntercept(1.2, "1.2", GJScalars.GraphQLFloat)
    }
}
