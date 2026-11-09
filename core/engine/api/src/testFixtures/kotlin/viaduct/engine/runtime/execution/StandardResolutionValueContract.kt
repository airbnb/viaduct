package viaduct.engine.runtime.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.engine.api.StandardResolutionValue
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.graphql.test.assertJson

@ExperimentalCoroutinesApi
interface StandardResolutionValueContract : QueryExecutionContract {
    companion object {
        private val SDL = """
            extend type Query {
                foo: Foo
            }

            type Foo {
                bar: String
            }
        """.trimIndent()
    }

    @Test
    fun `resolver returning StandardResolutionValue unwraps value and resolves children with standard policy`() {
        EngineTestModule(SDL) {
            field("Query" to "foo") {
                resolver {
                    // Returning via Any? boxes the @JvmInline value class
                    fn { _, _, _, _, _ -> StandardResolutionValue(mapOf("bar" to "map-value")) }
                }
            }
            fieldWithValue("Foo" to "bar", "resolver-value")
        }.runContractTest {
            // Child resolver runs (STANDARD policy), not the map value
            runQuery("{ foo { bar } }")
                .assertJson("""{ data: { foo: { bar: "resolver-value" } } }""")
        }
    }
}
