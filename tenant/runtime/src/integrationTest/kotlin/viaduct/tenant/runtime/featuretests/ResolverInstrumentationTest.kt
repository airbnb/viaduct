package viaduct.tenant.runtime.featuretests

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.globalid.GlobalID
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.tenant.runtime.featuretests.fixtures.Baz
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestSchemaFixture
import viaduct.tenant.runtime.featuretests.fixtures.UntypedFieldContext
import viaduct.tenant.runtime.featuretests.fixtures.assertJson
import viaduct.tenant.runtime.featuretests.fixtures.get

class ResolverInstrumentationTest {
    @Test
    fun `test field resolver should invoke instrumentation`() {
        val resolverToField = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        val instrumentation = TestResolverInstrumentation(resolverToField)

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, resolverInstrumentation = instrumentation)
            .resolver("Query" to "idField", resolverName = "query-id-field-resolver") { ctx ->
                // resolver returns a GRT value to the tenant runtime, which we expect to be unwrapped before
                // it's handed over to the engine
                ctx.globalIDFor(Baz.Reflection, "1")
            }
            .resolver(
                "Query" to "string1",
                objectValueFragment = "idField",
                resolveFn = { ctx: UntypedFieldContext ->
                    // using an UntypedFieldContext, peek at the engine data for the field to ensure that it's
                    // been unwrapped
                    val idFieldValue = ctx.objectValue.get<GlobalID<Baz>>("idField")
                    assertEquals(Baz.Reflection, idFieldValue.type)
                    assertEquals("1", idFieldValue.internalID)
                    idFieldValue.internalID
                },
                resolverName = "query-string1-resolver"
            )
            .build()
            .execute("query testQuery {string1}")
            .assertJson("{data: {string1: \"1\"}}")

        assertEquals(setOf("query-id-field-resolver", "query-string1-resolver"), resolverToField.keys)
        assertEquals(listOf("idField"), resolverToField.get("query-string1-resolver")?.toList())
    }

    class TestResolverInstrumentation(
        val resolverToFieldMap: ConcurrentHashMap<String, CopyOnWriteArrayList<String>>,
    ) : ViaductResolverInstrumentation {
        data class State(
            var currentResolverName: String? = null,
        ) : ViaductResolverInstrumentation.InstrumentationState

        override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState {
            return State()
        }

        override fun beginExecuteResolver(
            parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
            state: ViaductResolverInstrumentation.InstrumentationState?
        ): ViaductResolverInstrumentation.OnCompleted {
            state as State
            state.currentResolverName = parameters.resolverMetadata.name
            resolverToFieldMap.computeIfAbsent(parameters.resolverMetadata.name) { CopyOnWriteArrayList() }
            return ViaductResolverInstrumentation.NOOP_ON_COMPLETED
        }

        override fun beginFetchSelection(
            parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
            state: ViaductResolverInstrumentation.InstrumentationState?
        ): ViaductResolverInstrumentation.OnCompleted {
            state as State
            val resolverName = state.currentResolverName!!

            assertTrue { resolverToFieldMap.containsKey(resolverName) }

            resolverToFieldMap.get(resolverName)?.add(parameters.selection)
            return ViaductResolverInstrumentation.NOOP_ON_COMPLETED
        }
    }
}
