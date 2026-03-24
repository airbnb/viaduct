package viaduct.engine.runtime.graphql_java

import graphql.execution.values.InputInterceptor
import graphql.introspection.Introspection
import graphql.parser.ParserOptions
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GraphQLJavaConfigTest {
    @Test
    fun `asMap`() {
        // empty
        assertTrue(GraphQLJavaConfig.none.asMap().isEmpty())

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
                ),
                ctx.asMap()
            )
        }
    }
}
