package viaduct.tenant.runtime.featuretests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.api.context.FieldExecutionContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.tenant.runtime.featuretests.fixtures.Bar
import viaduct.tenant.runtime.featuretests.fixtures.Baz
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestSchemaFixture
import viaduct.tenant.runtime.featuretests.fixtures.Query
import viaduct.tenant.runtime.featuretests.fixtures.get

@ExperimentalCoroutinesApi
class RequiredSelectionsTest {
    @Test
    fun `required selections use deep aliases`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver(
                "Query" to "string1",
                { ctx: FieldExecutionContext<Query, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    val value = ctx.objectValue.getBar("aliasedBar")?.getValue("aliasedValue")
                    "A:$value"
                },
                objectValueFragment = "aliasedBar: bar { aliasedValue: value }"
            )
            .resolver("Query" to "bar") { Bar.Builder(it).value("B").build() }
            .build()
            .assertJson("{data: {string1: \"A:B\"}}", "{string1}")

    @Test
    fun `resolve field with queryValueFragment and objectValueFragment together`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl + "\nextend type Query { globalConfig: String }")
            .resolver("Query" to "globalConfig") { "Premium" }
            .resolver("Query" to "baz") { Baz.Builder(it).id(it.globalIDFor(Baz.Reflection, "baz1")).x(100).build() }
            .resolver(
                "Baz" to "y",
                { ctx: FieldExecutionContext<Baz, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    val config = ctx.queryValue.get<String>("globalConfig", String::class)
                    val x = ctx.objectValue.getX()
                    "$config item with value $x"
                },
                objectValueFragment = "x",
                queryValueFragment = "globalConfig"
            )
            .build()
            .assertJson(
                "{data: {baz: {y: \"Premium item with value 100\"}}}",
                "{baz { y }}"
            )

    @Test
    fun `resolve mutation with queryValueFragment`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver("Query" to "string1") { "InitialValue" }
            .resolver(
                "Mutation" to "string1",
                { ctx: FieldExecutionContext<Query, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    val currentValue = ctx.queryValue.getString1()
                    "Mutated from: $currentValue"
                },
                queryValueFragment = "string1"
            )
            .build()
            .assertJson(
                "{data: {string1: \"Mutated from: InitialValue\"}}",
                "mutation { string1 }"
            )

    @Test
    fun `resolve field with queryValueFragment - nested object access`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver(
                "Baz" to "y",
                { ctx: FieldExecutionContext<Baz, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    val barValue = ctx.queryValue.getBar()?.getValue()
                    "Baz sees bar value: $barValue"
                },
                queryValueFragment = "bar { value }"
            )
            .resolver("Query" to "bar") { Bar.Builder(it).build() }
            .resolver("Bar" to "value") { "BarValue" }
            .resolver("Query" to "baz") { Baz.Builder(it).id(it.globalIDFor(Baz.Reflection, "")).x(10).build() }
            .build()
            .assertJson("{data: {baz: {y: \"Baz sees bar value: BarValue\"}}}", "{baz { y }}")
}
