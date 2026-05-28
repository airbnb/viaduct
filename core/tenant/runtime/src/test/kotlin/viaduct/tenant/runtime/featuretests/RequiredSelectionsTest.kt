package viaduct.tenant.runtime.featuretests

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import viaduct.api.context.FieldExecutionContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.tenant.runtime.featuretests.fixtures.Bar
import viaduct.tenant.runtime.featuretests.fixtures.Baz
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestSchemaFixture
import viaduct.tenant.runtime.featuretests.fixtures.Foo
import viaduct.tenant.runtime.featuretests.fixtures.Query
import viaduct.tenant.runtime.featuretests.fixtures.assertJson
import viaduct.tenant.runtime.featuretests.fixtures.get

@ExperimentalCoroutinesApi
class RequiredSelectionsTest {
    @Test
    fun `required selections use deep aliases`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver(
                "Query" to "string1",
                { ctx: FieldExecutionContext<Query, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    val value = ctx.getObjectValue().getBar("aliasedBar")?.getValue("aliasedValue")
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
                    val config = ctx.getQueryValue().get<String>("globalConfig", String::class)
                    val x = ctx.getObjectValue().getX()
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
                    val currentValue = ctx.getQueryValue().getString1()
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
                    val barValue = ctx.getQueryValue().getBar()?.getValue()
                    "Baz sees bar value: $barValue"
                },
                queryValueFragment = "bar { value }"
            )
            .resolver("Query" to "bar") { Bar.Builder(it).build() }
            .resolver("Bar" to "value") { "BarValue" }
            .resolver("Query" to "baz") { Baz.Builder(it).id(it.globalIDFor(Baz.Reflection, "")).x(10).build() }
            .build()
            .assertJson("{data: {baz: {y: \"Baz sees bar value: BarValue\"}}}", "{baz { y }}")

    /**
     * Regression test for the prod incident where a field-checker RSS attached at one
     * interface implementor (e.g. `OtherNode.id`) could leak into resolution of a sibling
     * implementor (`HiveTable.id`), causing `UnsetFieldException: ... missing from variable RSS`.
     *
     * Setup: register field-checker stubs on `Foo.value` and `Bar.value` (both `Interface`
     * implementors), with `Bar.value`'s RSS rooted on `Query` so the legacy `isRootType`
     * permissive filter would let it leak. Resolve `iface` to a `Foo` — only `Foo.value`'s
     * checker should be invoked at runtime; `Bar.value`'s checker must not run.
     */
    @Test
    fun `field-level RSS rooted on Query does not leak into sibling implementor`() {
        val checkerInvocations = ConcurrentHashMap<String, AtomicInteger>()

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver("Query" to "iface") { ctx ->
                Foo.Builder(ctx).value("foo-value").build()
            }
            .resolver("Foo" to "value") { "foo-value" }
            // Foo.value's checker has its RSS rooted on Foo (the concrete implementor).
            .fieldChecker(
                "Foo" to "value",
                "foo-value-checker",
                { _, _ -> checkerInvocations.computeIfAbsent("Foo.value") { AtomicInteger() }.incrementAndGet() },
                Triple("key", "Foo", "value")
            )
            // Bar.value's checker has its RSS rooted on Query — the prod-observed leaker shape.
            // Origin coordinate is (Bar, value); plan parent is Query (a root type that the
            // legacy filter would permit through). The new origin-coordinate filter must
            // drop this plan when the runtime type is Foo.
            .fieldChecker(
                "Bar" to "value",
                "bar-value-checker",
                { _, _ -> checkerInvocations.computeIfAbsent("Bar.value") { AtomicInteger() }.incrementAndGet() },
                Triple("key", "Query", "string1")
            )
            .resolver("Query" to "string1") { "irrelevant" }
            .build()
            .execute("{ iface { value } }")
            .assertJson("{data: {iface: {value: \"foo-value\"}}}")

        assertEquals(1, checkerInvocations["Foo.value"]?.get(), "Foo.value's checker should run exactly once")
        assertNull(checkerInvocations["Bar.value"], "Bar.value's checker must not run when resolving Foo.value")
    }
}
