package viaduct.engine.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.StandardResolutionValue
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.mocks.runFeatureTest

@ExperimentalCoroutinesApi
class StandardResolutionValueTest {
    companion object {
        val SDL = """
            extend type Query {
                foo: Foo
            }

            type Foo {
                bar: String
            }
        """.trimIndent()

        private val schema = createSchemaWithWiring(SDL)
    }

    @Test
    fun `StandardResolutionValue throws when wrapped multiple times`() {
        val inner = StandardResolutionValue(null)
        assertThrows<IllegalArgumentException> {
            StandardResolutionValue(inner)
        }
    }

    @Test
    fun `resolver returning StandardResolutionValue unwraps value and resolves children with standard policy`() {
        MockTenantModuleBootstrapper(schema) {
            field("Query" to "foo") {
                resolver {
                    // Returning via Any? boxes the @JvmInline value class
                    fn { _, _, _, _, _ -> StandardResolutionValue(mapOf("bar" to "map-value")) }
                }
            }
            field("Foo" to "bar") {
                resolver {
                    fn { _, _, _, _, _ -> "resolver-value" }
                }
            }
        }.runFeatureTest {
            // Child resolver runs (STANDARD policy), not the map value
            runQuery("{ foo { bar } }")
                .assertJson("""{ data: { foo: { bar: "resolver-value" } } }""")
        }
    }
}
