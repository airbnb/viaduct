package viaduct.tenant.runtime.featuretests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTest
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.Foo

@ExperimentalCoroutinesApi
class IdenticalQueryMultipleSchemasTest {
    private fun configure(sdl: String): FeatureTest =
        FeatureTestBuilder(sdl)
            .resolver("Query" to "foo") { Foo.Builder(it).value("fooValue").build() }
            .build()

    @Test
    fun `identical query returns correct result on interface`() =
        configure(
            """
                extend type Query { foo: Interface }
                interface Interface { value: String }
                type Foo implements Interface { value: String }
            """.trimIndent()
        ).assertJson("{data: {foo: {value: \"fooValue\"}}}", QUERY)

    @Test
    fun `identical query returns correct result on union`() =
        configure(
            """
                extend type Query { foo: Union }
                type Foo { value: String }
                union Union = Foo
            """.trimIndent()
        ).assertJson("{data: {foo: {value: \"fooValue\"}}}", QUERY)

    companion object {
        val QUERY = """
            query IdenticalQuery {
                foo {
                    ... on Foo {
                        value
                    }
                }
            }
        """.trimIndent()
    }
}
