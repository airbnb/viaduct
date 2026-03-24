package viaduct.tenant.runtime.featuretests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTest
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.get

@ExperimentalCoroutinesApi
class TrivialScalarTypeResolverWithArgumentTest {
    private fun configure(): FeatureTest =
        FeatureTestBuilder("extend type Query { foo(input: String): String }", useFakeGRTs = true)
            .resolver("Query" to "foo") { "resolved: ${it.arguments.get<String>("input")}" }
            .build()

    @Test
    fun `trivial resolver processes a literal input`() =
        configure()
            .assertJson(
                "{data: {foo: \"resolved: inputValue\"}}",
                "{ foo(input: \"inputValue\") }",
            )

    @Test
    fun `trivial resolver processes a variable input`() =
        configure()
            .assertJson(
                "{data: {foo: \"resolved: variableInputValue\"}}",
                """
                    query TestQuery(${'$'}variable: String) {
                        foo(input: ${'$'}variable)
                    }
                """.trimIndent(),
                mapOf("variable" to "variableInputValue")
            )
}
