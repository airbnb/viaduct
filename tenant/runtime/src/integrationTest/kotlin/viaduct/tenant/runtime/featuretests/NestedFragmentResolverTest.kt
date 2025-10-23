package viaduct.tenant.runtime.featuretests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.api.context.FieldExecutionContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.tenant.runtime.featuretests.fixtures.Bar
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestSchemaFixture
import viaduct.tenant.runtime.featuretests.fixtures.Foo
import viaduct.tenant.runtime.featuretests.fixtures.Query
import viaduct.tenant.runtime.featuretests.fixtures.get

@ExperimentalCoroutinesApi
class NestedFragmentResolverTest {
    @Test
    fun `fragment resolver processes a sibling field`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver(
                "Query" to "string1",
                { ctx: FieldExecutionContext<Query, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    "Query.string1=[${ctx.objectValue.getFoo()?.getValue()}]"
                },
                "foo { value }"
            )
            .resolver("Query" to "foo") { Foo.Builder(it).build() }
            .resolver(
                "Foo" to "value",
                { ctx: FieldExecutionContext<Foo, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    "Foo.value=[${ctx.objectValue.getBar()?.getValue()}]"
                },
                "bar { value }"
            )
            .resolver("Foo" to "bar") { Bar.Builder(it).build() }
            .resolver("Bar" to "value") { "Bar.value=[VALUE]" }
            .build()
            .assertJson(
                "{data: {string1: \"Query.string1=[Foo.value=[Bar.value=[VALUE]]]\"}}",
                "{string1}",
            )
}
