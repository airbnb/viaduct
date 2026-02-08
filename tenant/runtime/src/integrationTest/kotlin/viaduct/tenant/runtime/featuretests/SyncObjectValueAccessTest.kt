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

/**
 * Integration tests for synchronous object and query value access via
 * [FieldExecutionContext.getObjectValue] and [BaseFieldExecutionContext.getQueryValue].
 *
 * These methods provide synchronously-accessible versions of objectValue and queryValue
 * where all selections have been eagerly resolved upfront.
 */
@ExperimentalCoroutinesApi
class SyncObjectValueAccessTest {
    @Test
    fun `getObjectValue returns same data as objectValue for simple field`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver("Query" to "baz") { Baz.Builder(it).id(it.globalIDFor(Baz.Reflection, "baz1")).x(42).build() }
            .resolver(
                "Baz" to "y",
                { ctx: FieldExecutionContext<Baz, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    // Access x via the sync getObjectValue method
                    val syncObj = ctx.getObjectValue()
                    val xValue = syncObj.getX()
                    "Sync access: x=$xValue"
                },
                objectValueFragment = "x"
            )
            .build()
            .assertJson(
                "{data: {baz: {y: \"Sync access: x=42\"}}}",
                "{baz { y }}"
            )

    @Test
    fun `getQueryValue returns same data as queryValue for simple field`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl + "\nextend type Query { config: String }")
            .resolver("Query" to "config") { "SyncConfig" }
            .resolver("Query" to "baz") { Baz.Builder(it).id(it.globalIDFor(Baz.Reflection, "baz1")).x(1).build() }
            .resolver(
                "Baz" to "y",
                { ctx: FieldExecutionContext<Baz, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    // Access config via the sync getQueryValue method
                    val syncQuery = ctx.getQueryValue()
                    val configValue = syncQuery.get<String>("config", String::class)
                    "Sync query access: config=$configValue"
                },
                queryValueFragment = "config"
            )
            .build()
            .assertJson(
                "{data: {baz: {y: \"Sync query access: config=SyncConfig\"}}}",
                "{baz { y }}"
            )

    @Test
    fun `getObjectValue with nested object access`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver("Query" to "bar") { Bar.Builder(it).value("NestedValue").build() }
            .resolver(
                "Query" to "string1",
                { ctx: FieldExecutionContext<Query, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    // Access nested bar.value via sync getObjectValue
                    val syncObj = ctx.getObjectValue()
                    val barValue = syncObj.getBar()?.getValue()
                    "Sync nested: bar.value=$barValue"
                },
                objectValueFragment = "bar { value }"
            )
            .build()
            .assertJson(
                "{data: {string1: \"Sync nested: bar.value=NestedValue\"}}",
                "{string1}"
            )

    @Test
    fun `getObjectValue and getQueryValue together`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl + "\nextend type Query { multiplier: Int }")
            .resolver("Query" to "multiplier") { 10 }
            .resolver("Query" to "baz") { Baz.Builder(it).id(it.globalIDFor(Baz.Reflection, "baz1")).x(5).build() }
            .resolver(
                "Baz" to "y",
                { ctx: FieldExecutionContext<Baz, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    // Access both sync versions
                    val syncObj = ctx.getObjectValue()
                    val syncQuery = ctx.getQueryValue()
                    val x = syncObj.getX() ?: 0
                    val multiplier = syncQuery.get<Int>("multiplier", Int::class) ?: 1
                    "Sync combined: ${x * multiplier}"
                },
                objectValueFragment = "x",
                queryValueFragment = "multiplier"
            )
            .build()
            .assertJson(
                "{data: {baz: {y: \"Sync combined: 50\"}}}",
                "{baz { y }}"
            )

    @Test
    fun `getObjectValue with aliased field access`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver("Query" to "bar") { Bar.Builder(it).value("AliasedValue").build() }
            .resolver(
                "Query" to "string1",
                { ctx: FieldExecutionContext<Query, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    // Access aliased field via sync getObjectValue
                    val syncObj = ctx.getObjectValue()
                    val aliasedBar = syncObj.getBar("aliasedBar")
                    val value = aliasedBar?.getValue("aliasedValue")
                    "Sync alias: $value"
                },
                objectValueFragment = "aliasedBar: bar { aliasedValue: value }"
            )
            .build()
            .assertJson(
                "{data: {string1: \"Sync alias: AliasedValue\"}}",
                "{string1}"
            )
}
