package viaduct.engine.runtime.graphql_java

import graphql.execution.values.InputInterceptor
import graphql.introspection.Introspection
import graphql.normalized.NormalizedDocument
import graphql.parser.ParserOptions
import graphql.validation.QueryComplexityLimits
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GraphQLJavaConfigTest {
    @Test
    fun `asMap`() {
        // empty
        assertEquals(
            mapOf<Any, Any?>(
                QueryComplexityLimits.KEY to QueryComplexityLimits.NONE,
                NormalizedDocument.ENABLED to true,
            ),
            GraphQLJavaConfig.none.asMap()
        )

        // simple
        apply {
            val parserOptions = mockk<ParserOptions>()
            val inputInterceptor = mockk<InputInterceptor>()
            val ctx = GraphQLJavaConfig(parserOptions, inputInterceptor, false)
            assertEquals(
                mapOf<Any, Any?>(
                    ParserOptions::class.java to parserOptions,
                    InputInterceptor::class.java to inputInterceptor,
                    Introspection.INTROSPECTION_DISABLED to true,
                    QueryComplexityLimits.KEY to QueryComplexityLimits.NONE,
                    NormalizedDocument.ENABLED to true,
                ),
                ctx.asMap()
            )
        }
    }
}
