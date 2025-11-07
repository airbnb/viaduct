package viaduct.tenant.runtime.featuretests

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.context.NodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.engine.api.instrumentation.resolver.CheckerFunction
import viaduct.engine.api.instrumentation.resolver.FetchFunction
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.tenant.runtime.featuretests.fixtures.Baz
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestSchemaFixture
import viaduct.tenant.runtime.featuretests.fixtures.Foo
import viaduct.tenant.runtime.featuretests.fixtures.UntypedFieldContext
import viaduct.tenant.runtime.featuretests.fixtures.assertJson
import viaduct.tenant.runtime.featuretests.fixtures.get

@OptIn(ExperimentalCoroutinesApi::class)
class ResolverInstrumentationTest {
    @Test
    fun `test field resolver should invoke instrumentation`() {
        val resolverToField = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        val instrumentation = InMemoryRecordingResolverInstrumentation(resolverToField)

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

    fun `test node resolver should invoke instrumentation`() {
        val resolverToField = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        val instrumentation = InMemoryRecordingResolverInstrumentation(resolverToField)

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, resolverInstrumentation = instrumentation)
            .nodeResolver("Baz", resolverName = "baz-node-resolver") { ctx: NodeExecutionContext<Baz> ->
                Baz.Builder(ctx).build()
            }
            .build()
            .execute("query testQuery { node(id: \"QmF6OjE=\") { ... on Baz { id } } }")
            .assertJson("{data: {node: {id: \"QmF6OjE=\"}}}")

        assertEquals(2, resolverToField.keys.size) {
            "Resolver instrumentation should be invoked once for the query node resolver and once for the baz node resolver"
        }
        assertContains(resolverToField.keys, "baz-node-resolver", "baz-node-resolver should exists but found ${resolverToField.keys()}")
    }

    @Test
    fun `test field checker should invoke instrumentation`() {
        val checkerFieldAccessMap = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        val instrumentation = InMemoryRecordingCheckerInstrumentation(checkerFieldAccessMap)

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, resolverInstrumentation = instrumentation)
            .resolver("Query" to "string1") { "string1" }
            .resolver("Query" to "string2") { "string2" }
            .fieldChecker(
                "Query" to "string1",
                "query-string1-field-checker",
                { _, objectDataMap ->
                    objectDataMap["key"]?.fetch("string2")
                },
                Triple("key", "Query", "string2")
            )
            .build()
            .execute("query { string1 }")
            .assertJson("{data: {\"string1\": \"string1\"}}")

        // Verify checker instrumentation was invoked
        assertEquals(setOf("query-string1-field-checker:Query.string1"), checkerFieldAccessMap.keys)
        assertEquals(setOf("string2"), checkerFieldAccessMap.get("query-string1-field-checker:Query.string1")?.toSet())
    }

    @Test
    fun `test type checker should invoke instrumentation`() {
        val checkerFieldAccessMap = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        val instrumentation = InMemoryRecordingCheckerInstrumentation(checkerFieldAccessMap)

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, resolverInstrumentation = instrumentation)
            .resolver("Query" to "foo") { Foo.Builder(it).valueWithoutResolver("valueWithoutResolver").build() }
            .resolver("Foo" to "value") { "foo-value" }
            .resolver("Query" to "string2") { "string2" }
            .typeChecker(
                "Foo",
                "foo-type-checker",
                { _, objectDataMap ->
                    objectDataMap["key"]?.fetch("value")
                },
                Triple("key", "Foo", "value")
            )
            .build()
            .execute("query { foo { value } }")
            .assertJson("{data: {foo: {value: \"foo-value\"}}}")

        // Verify checker instrumentation was invoked
        assertEquals(setOf("foo-type-checker:Foo"), checkerFieldAccessMap.keys)
        assertEquals(setOf("value"), checkerFieldAccessMap.get("foo-type-checker:Foo")?.toSet())
    }

    class InMemoryRecordingResolverInstrumentation(
        val resolverToFieldMap: ConcurrentHashMap<String, CopyOnWriteArrayList<String>>
    ) : ViaductResolverInstrumentation {
        data class State(
            var currentResolverName: String? = null,
        ) : ViaductResolverInstrumentation.InstrumentationState

        override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState {
            return State()
        }

        override fun <T> instrumentResolverExecution(
            resolver: ResolverFunction<T>,
            parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
            state: ViaductResolverInstrumentation.InstrumentationState?
        ): ResolverFunction<T> {
            state as State
            state.currentResolverName = parameters.resolverMetadata.name
            resolverToFieldMap.computeIfAbsent(parameters.resolverMetadata.name) { CopyOnWriteArrayList() }
            return resolver
        }

        override fun <T> instrumentFetchSelection(
            fetchFn: FetchFunction<T>,
            parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
            state: ViaductResolverInstrumentation.InstrumentationState?
        ): FetchFunction<T> {
            state as State
            val resolverName = state.currentResolverName!!

            assertTrue { resolverToFieldMap.containsKey(resolverName) }

            resolverToFieldMap.get(resolverName)?.add(parameters.selection)
            return fetchFn
        }
    }

    class InMemoryRecordingCheckerInstrumentation(
        val checkerMetadataList: ConcurrentHashMap<String, CopyOnWriteArrayList<String>>
    ) : ViaductResolverInstrumentation {
        data class State(
            var currentCheckerName: String? = null
        ) : ViaductResolverInstrumentation.InstrumentationState

        override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState {
            return State()
        }

        override fun <T> instrumentFetchSelection(
            fetchFn: FetchFunction<T>,
            parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
            state: ViaductResolverInstrumentation.InstrumentationState?
        ): FetchFunction<T> {
            state as State
            val checkerName = state.currentCheckerName
            if (checkerName != null) {
                assertTrue { checkerMetadataList.containsKey(checkerName) }
                checkerMetadataList.get(checkerName)?.add(parameters.selection)
            }
            return fetchFn
        }

        override fun <T> instrumentAccessChecker(
            checker: CheckerFunction<T>,
            parameters: ViaductResolverInstrumentation.InstrumentExecuteCheckerParameters,
            state: ViaductResolverInstrumentation.InstrumentationState?
        ): CheckerFunction<T> {
            state as State
            state.currentCheckerName = parameters.checkerMetadata.toTagString()
            checkerMetadataList.computeIfAbsent(parameters.checkerMetadata.toTagString()) { CopyOnWriteArrayList() }
            return checker
        }
    }
}
