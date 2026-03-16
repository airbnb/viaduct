package viaduct.tenant.runtime.featuretests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.UntypedFieldContext
import viaduct.tenant.runtime.featuretests.fixtures.get

@ExperimentalCoroutinesApi
class TrivialFragmentResolverTest {
    @Test
    fun `fragment resolver processes a sibling field`() =
        FeatureTestBuilder("extend type Query { foo: String bar: String }")
            .resolver("Query" to "foo") { "fooResult" }
            .resolver(
                "Query" to "bar",
                { ctx: UntypedFieldContext ->
                    val foo = ctx.objectValue.get<String>("foo")
                    "resolved: $foo"
                },
                objectValueFragment = "foo"
            )
            .build()
            .assertJson("{data: {bar: \"resolved: fooResult\"}}", "{bar}")
}
