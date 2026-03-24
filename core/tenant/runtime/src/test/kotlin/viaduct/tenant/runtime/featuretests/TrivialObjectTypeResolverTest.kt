package viaduct.tenant.runtime.featuretests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestSchemaFixture
import viaduct.tenant.runtime.featuretests.fixtures.Foo

@ExperimentalCoroutinesApi
class TrivialObjectTypeResolverTest {
    @Test
    fun `trivial resolver returns an object value`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver("Query" to "foo") { Foo.Builder(it).value("VALUE").build() }
            .build()
            .assertJson(
                "{data: {foo: {value: \"VALUE\"}}}",
                "{foo {value}}"
            )

    @Test
    fun `trivial resolver returns an object value with interface`() =
        FeatureTestBuilder(
            """
                    extend type Query { iface: Interface }
                    interface Interface { value: String }
                    type Foo implements Interface { value: String }
            """.trimIndent()
        )
            .resolver("Query" to "iface") { Foo.Builder(it).value("VALUE").build() }
            .build()
            .assertJson(
                "{data: {iface: {value: \"VALUE\"}}}",
                "{iface { ... on Foo {value}}}"
            )

    @Test
    fun `trivial resolver returns an object value with union`() =
        FeatureTestBuilder(
            """
                    extend type Query { union_: Union }
                    union Union = Foo | Bar
                    type Foo { value: String }
                    type Bar { value: String }
            """.trimIndent()
        )
            .resolver("Query" to "union_") { Foo.Builder(it).value("VALUE").build() }
            .build()
            .assertJson(
                "{data: {union_: {value: \"VALUE\"}}}",
                "{ union_ { ... on Foo {value}}}",
            )
}
