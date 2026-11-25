@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.execution.DataFetcherResult
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.FeatureTest
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.EngineExecutionContextExtensions.dataFetchingEnvironment
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.engine.runtime.observability.ResolverOutputMissingFieldException
import viaduct.graphql.test.StreamErrorComparison
import viaduct.graphql.test.assertJson
import viaduct.graphql.test.assertJsonStream
import viaduct.graphql.test.assertValidDeferStream
import viaduct.service.api.ExecutionResult
import viaduct.service.api.IncrementalEntry
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

class DeferExecutionTest {
    @Nested
    inner class MutationExecutionTests {
        @Test
        fun `deferred mutation payload does not block the next mutation`() {
            assertMutationDelivery(namespaced = false)
        }

        @Test
        fun `deferred namespaced mutation payload does not block the next mutation`() {
            assertMutationDelivery(namespaced = true)
        }

        @Test
        fun `deferred mutation list items do not block the next mutation`() {
            assertMutationDelivery(namespaced = false, listPayload = true)
        }

        @Test
        fun `deferred namespaced mutation list items do not block the next mutation`() {
            assertMutationDelivery(namespaced = true, listPayload = true)
        }

        private fun assertMutationDelivery(
            namespaced: Boolean,
            listPayload: Boolean = false
        ) {
            val finishSlow = CompletableDeferred<Unit>()
            val mutations = CopyOnWriteArrayList<String>()
            val mutationType = if (namespaced) "Actions" else "Mutation"
            val payloadType = if (listPayload) "[Payload]" else "Payload"
            val schema = if (namespaced) {
                """
                    extend type Mutation { actions: Actions }
                    type Actions @namespaceType { first: $payloadType, second: $payloadType }
                    type Payload { id: ID, slow: Int }
                """.trimIndent()
            } else {
                """
                    extend type Mutation { first: $payloadType, second: $payloadType }
                    type Payload { id: ID, slow: Int }
                """.trimIndent()
            }
            val selections = "first { id ... @defer { slow } } second { id }"
            val query = if (namespaced) "mutation { actions { $selections } }" else "mutation { $selections }"
            val payload = listOf("first", "second").associateWith { name ->
                if (listPayload) listOf(mapOf("id" to name), mapOf("id" to name)) else mapOf("id" to name)
            }
            val initialData = if (namespaced) mapOf("actions" to payload) else payload

            MockTenantModuleBootstrapper(schema) {
                for (name in listOf("first", "second")) {
                    field(mutationType to name) {
                        resolver {
                            fn { _, _, _, _, _ ->
                                mutations += name
                                if (listPayload) {
                                    List(2) { createEngineObjectData("Payload", mapOf("id" to name)) }
                                } else {
                                    createEngineObjectData("Payload", mapOf("id" to name))
                                }
                            }
                        }
                    }
                }
                field("Payload" to "slow") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            finishSlow.await()
                            42
                        }
                    }
                }
            }.runFeatureTest {
                val results = runBlocking {
                    withTimeout(5.seconds) {
                        reactiveQuery(query).onEach {
                            if (it is ExecutionResult.Initial) {
                                assertEquals(initialData, it.getData())
                                assertTrue(it.errors.isEmpty(), it.errors.toString())
                                assertTrue(it.hasNext)
                                assertEquals(listOf("first", "second"), mutations)
                                assertFalse(finishSlow.isCompleted)
                                finishSlow.complete(Unit)
                            }
                        }.toList()
                    }
                }
                assertInstanceOf(ExecutionResult.Initial::class.java, results.first())
                val updates = results.drop(1).map { assertInstanceOf(ExecutionResult.Update::class.java, it) }
                assertTrue(updates.isNotEmpty())
                assertFalse(updates.last().hasNext)
                val patches = updates.flatMap { it.incremental }
                assertEquals(if (listPayload) 2 else 1, patches.size)
                patches.forEach {
                    assertEquals(mapOf("slow" to 42), assertInstanceOf(IncrementalEntry.Object::class.java, it).data)
                    assertTrue(it.errors.isEmpty(), it.errors.toString())
                }
                updates.forEach { update ->
                    assertTrue(update.completed.all { it.errors.isEmpty() }, update.toSpecification().toString())
                }
            }
        }
    }

    private data class CollectedResults(
        val initial: ExecutionResult.Initial,
        val updates: List<ExecutionResult.Update>,
    ) {
        fun assertJsonStream(expected: String) {
            (listOf(initial.toSpecification()) + updates.map { it.toSpecification() }).assertJsonStream(expected, StreamErrorComparison.PATHS)
        }
    }

    private fun FeatureTest.collectResults(
        query: String,
        onInitial: suspend (ExecutionResult.Initial) -> Unit = {},
    ): CollectedResults =
        runBlocking {
            withTimeout(5.seconds) {
                val results = reactiveQuery(query).onEach { if (it is ExecutionResult.Initial) onInitial(it) }.toList()
                results.map { it.toSpecification() }.assertValidDeferStream()
                CollectedResults(results.first() as ExecutionResult.Initial, results.drop(1).map { it as ExecutionResult.Update })
            }
        }

    @Test
    fun `demo`() {
        MockTenantModuleBootstrapper("extend type Query { x:Int }") {
            field("Query" to "x") {
                resolver {
                    fn { _, _, _, _, _ ->
                        delay(1.seconds)
                        1
                    }
                }
            }
        }.runFeatureTest {
            reactiveQuery("{ ... @defer { x } }")
                .assertJsonStream(
                    """
                        |[
                        |  {
                        |    data: {},
                        |    pending: [{id:"0", path:[]}],
                        |    hasNext: true
                        |  },
                        |  {
                        |    incremental: [
                        |      {
                        |        id: "0",
                        |        data: { x:1 },
                        |      }
                        |    ],
                        |    completed: [{id: "0"}],
                        |    hasNext: false
                        |  }
                        |]
                    """.trimMargin()
                )
        }
    }

    @Test
    fun `demo with selective resolver fields in separate defer groups`() {
        MockTenantModuleBootstrapper(
            """
                extend type Query { foo: Foo }
                type Foo { x: Int, y: Int }
            """.trimIndent()
        ) {
            field("Query" to "foo") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            createEngineObjectData(
                                "Foo",
                                buildMap {
                                    if (selections!!.containsField("Foo", "x")) put("x", 1)
                                    if (selections.containsField("Foo", "y")) put("y", 2)
                                }
                            )
                        }
                    )
                }
            }
        }.runFeatureTest {
            (
                reactiveQuery(
                    """
                    {
                      foo {
                        ... @defer(label: "X") {
                          x
                          ... @defer(label: "Y") { y }
                        }
                      }
                    }
                    """.trimIndent(),
                )
            ).assertJsonStream(
                """
                    [
                      {
                        data: {foo: {}},
                        pending: [{id: "0", path: ["foo"], label: "X"}],
                        hasNext: true
                      },
                      {
                        incremental: [{id: "0", data: {x: 1}}],
                        pending: [{id: "1", path: ["foo"], label: "Y"}],
                        completed: [{id: "0"}],
                        hasNext: true
                      },
                      {
                        incremental: [{id: "1", data: {y: 2}}],
                        completed: [{id: "1"}],
                        hasNext: false
                      }
                    ]
                """.trimIndent()
            )
        }
    }

    private data class DemoContext(val name: String)

    @Nested
    inner class NonIncrementalExecutionTests {
        @Test
        fun `execute completes nested defer selections in lists when incremental execution is disabled`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { items: [Item] }
                    type Item { value: Int }
                """.trimIndent()
            ) {
                field("Query" to "items") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            listOf(1, 2).map { createEngineObjectData("Item", mapOf("value" to it)) }
                        }
                    }
                }
            }.runFeatureTest {
                runExecutionTest {
                    val result = engine.execute(
                        ExecutionInput(
                            operationText = """
                                query(${'$'}defer: Boolean!) {
                                    ... @defer(if: ${'$'}defer) {
                                        items { __typename ...Details @defer(if: ${'$'}defer) }
                                    }
                                }
                                fragment Details on Item { value }
                            """.trimIndent(),
                            variables = mapOf("defer" to true),
                            requestContext = Any(),
                        ),
                        incrementalExecution = false,
                    )
                    result.assertJson("""{data: {items: [{__typename: "Item", value: 1}, {__typename: "Item", value: 2}]}}""")
                }
            }
        }

        @Test
        fun `execute respects request opt-out when reusing a cached operation`() {
            MockTenantModuleBootstrapper("extend type Query { value: Int }") {
                fieldWithValue("Query" to "value", 1)
            }.runFeatureTest {
                runExecutionTest {
                    for (requested in listOf(false, true, false)) {
                        val result = engine.execute(
                            ExecutionInput(
                                operationText = "{ __typename ... @defer { value } }",
                                requestContext = Any(),
                            ),
                            incrementalExecution = requested,
                        )
                        if (requested) {
                            val initial = assertInstanceOf(IncrementalExecutionResult::class.java, result)
                            try {
                                assertEquals(mapOf("__typename" to "Query"), initial.getData<Map<String, Any?>>())
                                assertFalse(initial.updates.toList().last().hasNext)
                            } finally {
                                initial.cancel()
                            }
                        } else {
                            assertFalse(result is IncrementalExecutionResult)
                            result.assertJson("""{data: {__typename: "Query", value: 1}}""")
                        }
                    }
                }
            }
        }

        @Test
        fun `ordinary execution bubbles nulls from deferred non-null fields`() {
            MockTenantModuleBootstrapper(
                """
                    extend type Query { item: Item }
                    type Item { value: Int! }
                """.trimIndent()
            ) {
                field("Query" to "item") {
                    resolver { fn { _, _, _, _, _ -> createEngineObjectData("Item", mapOf("value" to null)) } }
                }
            }.runFeatureTest {
                val result = runQuery("{ __typename item { ... @defer { value } } }")
                assertEquals(mapOf("__typename" to "Query", "item" to null), result.getData())
                assertEquals(listOf("item", "value"), result.errors.single().path)
            }
        }
    }

    @Nested
    inner class ViaductFeatureTests {
        @Test
        fun `deferred RSS materializes missing selective output`() {
            selectiveRssFixture().runFeatureTest {
                collectResults("{ foo { id ... @defer { derived } } }").assertJsonStream(
                    """
                        [
                          {data: {foo: {id: "foo"}}, pending: [{id: "0", path: ["foo"]}], hasNext: true},
                          {incremental: [{id: "0", data: {derived: 42}}], completed: [{id: "0"}], hasNext: false}
                        ]
                    """.trimIndent()
                )
            }
        }

        @Test
        fun `initial data does not wait for deferred RSS materialization`() {
            val finishMaterialization = CompletableDeferred<Unit>()
            selectiveRssFixture { finishMaterialization.await() }.runFeatureTest {
                val results = collectResults("{ foo { id ... @defer { derived } } }") {
                    assertEquals(mapOf("foo" to mapOf("id" to "foo")), it.getData())
                    assertFalse(finishMaterialization.isCompleted)
                    finishMaterialization.complete(Unit)
                }
                assertInitial(results.initial, mapOf("foo" to mapOf("id" to "foo")))
                assertEquals(mapOf("derived" to 42), (updates(results).flatMap { it.incremental }.single() as IncrementalEntry.Object).data)
                assertNoErrors(results)
            }
        }

        @Test
        fun `deferred selective materialization failure preserves initial data`() {
            selectiveRssFixture { error("selective refetch failed") }.runFeatureTest {
                val results = collectResults("{ foo { id ... @defer { derived } } }")
                assertInitial(results.initial, mapOf("foo" to mapOf("id" to "foo")))
                assertEquals(mapOf("derived" to null), (updates(results).flatMap { it.incremental }.single() as IncrementalEntry.Object).data)
                assertDeferredError(results, listOf("foo", "derived"), "selective refetch failed")
            }
        }

        private fun selectiveRssFixture(materialize: suspend () -> Unit = {}): MockTenantModuleBootstrapper =
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { id: ID, hidden: Int, derived: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(isSelective = true, resolverId = resolverId) { _, _, _, selections, _ ->
                            val data = mutableMapOf<String, Any?>("id" to "foo")
                            if (selections!!.containsField("Foo", "hidden")) {
                                materialize()
                                data["hidden"] = 21
                            }
                            createEngineObjectData("Foo", data)
                        }
                    }
                }
                field("Foo" to "derived") {
                    resolver {
                        objectSelections("hidden")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("hidden") * 2 }
                    }
                }
            }

        @Test
        fun `batched selective nodes preserve different shapes across defer groups`() {
            assertBatchedNodes(failArgument = null)
        }

        @Test
        fun `deferred batched node selector failure does not poison the same id at another path`() {
            assertBatchedNodes(failArgument = 2)
        }

        private fun assertBatchedNodes(failArgument: Int?) {
            batchedNodeFixture(failArgument).runFeatureTest {
                val results = collectResults(
                    """
                        {
                          first: item { id ... @defer(label: "first") { derived(a: 1) } }
                          second: item { id ... @defer(label: "second") { derived(a: 2) } }
                        }
                    """.trimIndent()
                )
                assertInitial(results.initial, mapOf("first" to mapOf("id" to "same"), "second" to mapOf("id" to "same")))
                val paths = results.initial.pending.associate { it.id to it.path }
                val patches = updates(results).flatMap { it.incremental }.associate { paths.getValue(it.id) to (it as IncrementalEntry.Object).data }
                assertEquals(
                    mapOf(
                        listOf("first") to mapOf("derived" to 10),
                        listOf("second") to mapOf("derived" to if (failArgument == null) 20 else null),
                    ),
                    patches,
                )
                if (failArgument == null) {
                    assertNoErrors(results)
                } else {
                    assertDeferredError(results, listOf("second", "derived"), "selector 2 failed")
                }
            }
        }

        @Test
        fun `repeated node ids in a list have independent deferred response paths`() {
            batchedNodeFixture().runFeatureTest {
                val results = collectResults("{ items { id ... @defer { derived(a: 1) } } }")
                assertInitial(results.initial, mapOf("items" to listOf(mapOf("id" to "same"), mapOf("id" to "same"))))
                val pending = results.initial.pending
                assertEquals(setOf(listOf("items", 0), listOf("items", 1)), pending.map { it.path }.toSet())
                assertEquals(2, pending.map { it.id }.toSet().size)
                val patches = updates(results).flatMap { it.incremental }
                assertEquals(pending.map { it.id }.toSet(), patches.map { it.id }.toSet())
                assertTrue(patches.all { (it as IncrementalEntry.Object).data == mapOf("derived" to 10) })
                assertNoErrors(results)
            }
        }

        private fun batchedNodeFixture(failArgument: Int? = null): MockTenantModuleBootstrapper =
            MockTenantModuleBootstrapper(
                """
                    extend type Query { item: Item, items: [Item] }
                    type Item implements Node { id: ID!, hidden(a: Int!): Int, derived(a: Int!): Int }
                """.trimIndent()
            ) {
                field("Query" to "item") {
                    valueFromContext { it.createNodeReference("same", schema.schema.getObjectType("Item")) }
                }
                field("Query" to "items") {
                    valueFromContext { ctx -> List(2) { ctx.createNodeReference("same", schema.schema.getObjectType("Item")) } }
                }
                field("Item" to "derived") {
                    resolver {
                        argumentVariables("a" to "a")
                        objectSelections("hidden(a: \$a)")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("hidden") }
                    }
                }
                type("Item") {
                    nodeBatchedExecutor(selective = true) { selectors, _ ->
                        selectors.associateWith { selector ->
                            runCatching {
                                val hidden = selector.selections.selections().singleOrNull { it.fieldName == "hidden" }
                                val data = mutableMapOf<String, Any?>("id" to selector.id)
                                if (hidden != null) {
                                    val a = selector.selections.argumentsOfSelection("Item", hidden.selectionName)!!.getValue("a") as Int
                                    if (a == failArgument) error("selector $a failed")
                                    data["hidden"] = a * 10
                                }
                                createEngineObjectData(objectType, data)
                            }
                        }
                    }
                }
            }

        @Test
        fun `deferred field waits for its checker without blocking initial data`() {
            assertDelayedFieldCheck(denied = false)
        }

        @Test
        fun `late field checker denial cannot expose an already resolved deferred value`() {
            assertDelayedFieldCheck(denied = true)
        }

        private fun assertDelayedFieldCheck(denied: Boolean) {
            val finishCheck = CompletableDeferred<Unit>()
            val rawValueReady = CompletableDeferred<Unit>()
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { public: Int, secret: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolver { fn { _, _, _, _, _ -> createEngineObjectData("Foo", mapOf("public" to 1)) } }
                }
                field("Foo" to "secret") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            rawValueReady.complete(Unit)
                            42
                        }
                    }
                    checker {
                        fn { _, _ ->
                            finishCheck.await()
                            if (denied) noAccess("secret denied")
                        }
                    }
                }
            }.runFeatureTest {
                val results = collectResults("{ foo { public ... @defer { secret } } }") {
                    assertEquals(mapOf("foo" to mapOf("public" to 1)), it.getData())
                    rawValueReady.await()
                    assertFalse(finishCheck.isCompleted)
                    finishCheck.complete(Unit)
                }
                assertInitial(results.initial, mapOf("foo" to mapOf("public" to 1)))
                assertEquals(mapOf("secret" to if (denied) null else 42), (updates(results).flatMap { it.incremental }.single() as IncrementalEntry.Object).data)
                if (denied) {
                    assertDeferredError(results, listOf("foo", "secret"), "secret denied")
                } else {
                    assertNoErrors(results)
                }
            }
        }

        @Test
        fun `denied deferred object does not announce its nested defer`() {
            typeCheckedFixture().runFeatureTest {
                val results = collectResults("{ __typename ... @defer { denied { id ... @defer { secret } } } }")
                assertInitial(results.initial, mapOf("__typename" to "Query"))
                assertEquals(mapOf("denied" to null), (updates(results).flatMap { it.incremental }.single() as IncrementalEntry.Object).data)
                assertTrue(updates(results).all { it.pending.isEmpty() })
                assertDeferredError(results, listOf("denied"), "item denied")
            }
        }

        @Test
        fun `denied list item does not retain deferred work while its sibling completes`() {
            typeCheckedFixture().runFeatureTest {
                val results = collectResults("{ items { id ... @defer { secret } } }")
                val initial = results.initial
                assertEquals(mapOf("items" to listOf(null, mapOf("id" to "allowed"))), initial.getData())
                assertEquals(listOf(listOf("items", 0)), initial.errors.map { it.path })
                assertEquals(listOf(listOf("items", 1)), initial.pending.map { it.path })
                assertEquals(mapOf("secret" to 42), (updates(results).flatMap { it.incremental }.single() as IncrementalEntry.Object).data)
                assertTrue(
                    updates(results).all { update ->
                        update.pending.isEmpty() && update.completed.all { it.errors.isEmpty() } && update.incremental.all { it.errors.isEmpty() }
                    }
                )
            }
        }

        private fun typeCheckedFixture(): MockTenantModuleBootstrapper =
            MockTenantModuleBootstrapper(
                """
                    extend type Query { denied: Item, items: [Item] }
                    type Item { id: ID, secret: Int }
                """.trimIndent()
            ) {
                field("Query" to "denied") {
                    resolver { fn { _, _, _, _, _ -> createEngineObjectData("Item", mapOf("id" to "denied", "secret" to 99)) } }
                }
                field("Query" to "items") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            listOf(
                                createEngineObjectData("Item", mapOf("id" to "denied", "secret" to 99)),
                                createEngineObjectData("Item", mapOf("id" to "allowed", "secret" to 42)),
                            )
                        }
                    }
                }
                type("Item") {
                    checker {
                        objectSelections("identity", "id")
                        fn { _, data ->
                            if (data.getValue("identity").fetchAs<String>("id") == "denied") throw SecurityException("item denied")
                        }
                    }
                }
            }

        @Test
        fun `deferred checker RSS materializes selective output without leaking it`() {
            val finishCheckData = CompletableDeferred<Unit>()
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { id: ID, allowed: Boolean, secret: Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(isSelective = true, resolverId = resolverId) { _, _, _, selections, _ ->
                            val data = mutableMapOf<String, Any?>("id" to "foo")
                            if (selections!!.containsField("Foo", "allowed")) {
                                finishCheckData.await()
                                data["allowed"] = false
                            }
                            createEngineObjectData("Foo", data)
                        }
                    }
                }
                field("Foo" to "secret") {
                    resolver { fn { _, _, _, _, _ -> 42 } }
                    checker {
                        objectSelections("policy", "allowed")
                        fn { _, data ->
                            if (!data.getValue("policy").fetchAs<Boolean>("allowed")) noAccess("policy denied")
                        }
                    }
                }
            }.runFeatureTest {
                val results = collectResults("{ foo { id ... @defer { secret } } }") {
                    assertEquals(mapOf("foo" to mapOf("id" to "foo")), it.getData())
                    finishCheckData.complete(Unit)
                }
                assertInitial(results.initial, mapOf("foo" to mapOf("id" to "foo")))
                assertEquals(mapOf("secret" to null), (updates(results).flatMap { it.incremental }.single() as IncrementalEntry.Object).data)
                assertDeferredError(results, listOf("foo", "secret"), "policy denied")
            }
        }

        @Test
        fun `initial resolver can require a client deferred field without delivering it early`() {
            rssFixture().runFeatureTest {
                collectResults("{ foo { derived(a: 1) ... @defer { raw(a: 1) } } }").assertJsonStream(
                    """
                        [
                          {data: {foo: {derived: 20}}, pending: [{id: "0", path: ["foo"]}], hasNext: true},
                          {incremental: [{id: "0", data: {raw: 10}}], completed: [{id: "0"}], hasNext: false}
                        ]
                    """.trimIndent()
                )
            }
        }

        @Test
        fun `deferred RSS reuses initial data and keeps argument variables separate`() {
            rssFixture().runFeatureTest {
                collectResults("{ foo { raw(a: 1) ... @defer { one: derived(a: 1) two: derived(a: 2) } } }").assertJsonStream(
                    """
                        [
                          {data: {foo: {raw: 10}}, pending: [{id: "0", path: ["foo"]}], hasNext: true},
                          {incremental: [{id: "0", data: {one: 20, two: 40}}], completed: [{id: "0"}], hasNext: false}
                        ]
                    """.trimIndent()
                )
            }
        }

        @Test
        fun `RSS failure is attributed to the aliased deferred consumer`() {
            rssFixture(failArgument = 2).runFeatureTest {
                val results = collectResults("{ foo { raw(a: 1) ... @defer { broken: derived(a: 2) } } }")
                assertInitial(results.initial, mapOf("foo" to mapOf("raw" to 10)))
                assertEquals(mapOf("broken" to null), (updates(results).flatMap { it.incremental }.single() as IncrementalEntry.Object).data)
                assertDeferredError(results, listOf("foo", "broken"), "dependency 2 failed")
            }
        }

        private fun rssFixture(failArgument: Int? = null): MockTenantModuleBootstrapper =
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { raw(a: Int!): Int, derived(a: Int!): Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolver { fn { _, _, _, _, _ -> createEngineObjectData("Foo") } }
                }
                field("Foo" to "raw") {
                    resolver {
                        fn { args, _, _, _, _ ->
                            val a = args.getValue("a") as Int
                            if (a == failArgument) error("dependency $a failed")
                            a * 10
                        }
                    }
                }
                field("Foo" to "derived") {
                    resolver {
                        argumentVariables("a" to "a")
                        objectSelections("raw(a: \$a)")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("raw") * 2 }
                    }
                }
            }

        @Test
        fun `deferred parent RSS and sibling local contexts remain isolated`() {
            parentContextFixture().runFeatureTest {
                val results = collectResults(
                    """
                        {
                          first: company(name: "first") { user { id ... @defer { ancestor context } } }
                          second: company(name: "second") { user { id ... @defer { ancestor context } } }
                        }
                    """.trimIndent()
                )
                assertInitial(
                    results.initial,
                    mapOf("first" to mapOf("user" to mapOf("id" to "user")), "second" to mapOf("user" to mapOf("id" to "user"))),
                )
                val paths = results.initial.pending.associate { it.id to it.path }
                assertEquals(
                    mapOf(
                        listOf("first", "user") to mapOf("ancestor" to "first", "context" to "first"),
                        listOf("second", "user") to mapOf("ancestor" to "second", "context" to "second"),
                    ),
                    updates(results).flatMap { it.incremental }.associate { paths.getValue(it.id) to (it as IncrementalEntry.Object).data },
                )
                assertNoErrors(results)
            }
        }

        @Test
        fun `deferred parent traversal preserves list item identity and context`() {
            parentContextFixture().runFeatureTest {
                val results = collectResults("{ companies { name user { id ... @defer { ancestor context } } } }")
                assertInitial(
                    results.initial,
                    mapOf(
                        "companies" to listOf(
                            mapOf("name" to "first", "user" to mapOf("id" to "user")),
                            mapOf("name" to "second", "user" to mapOf("id" to "user")),
                        )
                    ),
                )
                val paths = results.initial.pending.associate { it.id to it.path }
                assertEquals(
                    mapOf(
                        listOf("companies", 0, "user") to mapOf("ancestor" to "first", "context" to "first"),
                        listOf("companies", 1, "user") to mapOf("ancestor" to "second", "context" to "second"),
                    ),
                    updates(results).flatMap { it.incremental }.associate { paths.getValue(it.id) to (it as IncrementalEntry.Object).data },
                )
                assertNoErrors(results)
            }
        }

        @Test
        fun `deferred parent RSS errors use the client visible consumer path`() {
            parentContextFixture(failParent = true).runFeatureTest {
                val results = collectResults("{ company(name: \"first\") { user { id ... @defer { alias: ancestor } } } }")
                assertInitial(results.initial, mapOf("company" to mapOf("user" to mapOf("id" to "user"))))
                assertEquals(mapOf("alias" to null), (updates(results).flatMap { it.incremental }.single() as IncrementalEntry.Object).data)
                assertDeferredError(results, listOf("company", "user", "alias"), "parent name failed")
            }
        }

        private fun parentContextFixture(failParent: Boolean = false): MockTenantModuleBootstrapper =
            MockTenantModuleBootstrapper(
                """
                    extend type Query { company(name: String!): Company, companies: [Company] }
                    type Company { name: String, checkedName: String, user: User }
                    type User { id: ID, parent: Company @parent, ancestor: String, context: String }
                """.trimIndent()
            ) {
                field("Query" to "company") {
                    resolver {
                        fn { args, _, _, _, ctx ->
                            val name = args.getValue("name") as String
                            val localContext = ctx.dataFetchingEnvironment!!.getLocalContext<CompositeLocalContext>()!!
                                .addOrUpdate(DemoContext(name))
                            DataFetcherResult.newResult<EngineObjectData>()
                                .data(createEngineObjectData("Company", mapOf("name" to name)))
                                .localContext(localContext)
                                .build()
                        }
                    }
                }
                field("Query" to "companies") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            listOf("first", "second").map { createEngineObjectData("Company", mapOf("name" to it)) }
                        }
                    }
                }
                field("Company" to "user") {
                    resolver {
                        objectSelections("name")
                        fn { _, obj, _, _, ctx ->
                            val name = obj.fetchAs<String>("name")
                            val localContext = ctx.dataFetchingEnvironment!!.getLocalContext<CompositeLocalContext>()!!
                            DataFetcherResult.newResult<EngineObjectData>()
                                .data(createEngineObjectData("User", mapOf("id" to "user")))
                                .localContext(localContext.addOrUpdate(DemoContext(name)))
                                .build()
                        }
                    }
                }
                field("Company" to "checkedName") {
                    resolver {
                        objectSelections("name")
                        fn { _, obj, _, _, _ ->
                            if (failParent) error("parent name failed")
                            obj.fetchAs<String>("name")
                        }
                    }
                }
                field("User" to "ancestor") {
                    resolver {
                        objectSelections("parent { checkedName }")
                        fn { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("checkedName") }
                    }
                }
                field("User" to "context") {
                    resolver {
                        fn { _, _, _, _, ctx -> ctx.dataFetchingEnvironment!!.getLocalContextForType<DemoContext>()!!.name }
                    }
                }
            }

        @Test
        fun `deferred resolver starts a subquery after initial delivery with its variables and request context`() {
            val startSubquery = CompletableDeferred<Unit>()
            val contexts = CopyOnWriteArrayList<Any?>()
            val instrumentation = RecordingInstrumentation()
            subqueryFixture(startSubquery, contexts = contexts).runFeatureTest(
                engineConfig = EngineConfiguration.featureTestDefault.copy(additionalInstrumentation = instrumentation),
            ) {
                val results = collectResults("{ foo { id ... @defer { derived(a: 21) } } }") {
                    assertEquals(mapOf("foo" to mapOf("id" to "foo")), it.getData())
                    assertTrue(
                        instrumentation.fieldFetchingContexts.none { context ->
                            (context.parameters as InstrumentationFieldFetchParameters).executionStepInfo.fieldDefinition.name == "value"
                        }
                    )
                    startSubquery.complete(Unit)
                }
                assertInitial(results.initial, mapOf("foo" to mapOf("id" to "foo")))
                assertEquals(mapOf("derived" to 42), (updates(results).flatMap { it.incremental }.single() as IncrementalEntry.Object).data)
                assertNoErrors(results)
            }
            assertEquals(2, contexts.size)
            assertSame(contexts[0], contexts[1])
            val subqueryContext = instrumentation.fieldFetchingContexts.single {
                (it.parameters as InstrumentationFieldFetchParameters).executionStepInfo.fieldDefinition.name == "value"
            }
            assertTrue(subqueryContext.onDispatchedCalled.get())
            assertTrue(subqueryContext.onCompletedCalled.get())
            assertEquals(null, subqueryContext.completedException)
        }

        @Test
        fun `post initial subquery failure belongs to its deferred consumer`() {
            val startSubquery = CompletableDeferred<Unit>()
            subqueryFixture(startSubquery, fetch = { error("subquery failed") }).runFeatureTest {
                val results = collectResults("{ foo { id ... @defer { derived(a: 21) } } }") {
                    startSubquery.complete(Unit)
                }
                assertInitial(results.initial, mapOf("foo" to mapOf("id" to "foo")))
                assertEquals(mapOf("derived" to null), (updates(results).flatMap { it.incremental }.single() as IncrementalEntry.Object).data)
                assertDeferredError(results, listOf("foo", "derived"), "subquery failed")
            }
        }

        @Test
        fun `cancelling deferred delivery cancels an outstanding subquery`() {
            val startSubquery = CompletableDeferred<Unit>()
            val subqueryStarted = CompletableDeferred<Unit>()
            val subqueryCancelled = CompletableDeferred<Unit>()
            subqueryFixture(
                startSubquery,
                fetch = {
                    try {
                        subqueryStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        subqueryCancelled.complete(Unit)
                    }
                },
            ).runFeatureTest {
                runBlocking {
                    withTimeout(5.seconds) {
                        val initial = CompletableDeferred<ExecutionResult.Initial>()
                        val updates = mutableListOf<ExecutionResult.Update>()
                        val collection = launch {
                            reactiveQuery("{ foo { id ... @defer { derived(a: 21) } } }").collect {
                                when (it) {
                                    is ExecutionResult.Initial -> {
                                        initial.complete(it)
                                        startSubquery.complete(Unit)
                                    }
                                    is ExecutionResult.Update -> updates.add(it)
                                    is ExecutionResult.Standard -> error("Expected incremental execution")
                                }
                            }
                        }
                        subqueryStarted.await()
                        assertInitial(initial.await(), mapOf("foo" to mapOf("id" to "foo")))
                        assertFalse(subqueryCancelled.isCompleted)
                        collection.cancelAndJoin()
                        subqueryCancelled.await()
                        assertTrue(updates.isEmpty())
                    }
                }
            }
        }

        private fun subqueryFixture(
            start: CompletableDeferred<Unit>,
            contexts: MutableList<Any?> = CopyOnWriteArrayList(),
            fetch: suspend (Int) -> Int = { it * 2 },
        ): MockTenantModuleBootstrapper =
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo, value(a: Int!): Int }
                    type Foo { id: ID, derived(a: Int!): Int }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolver { fn { _, _, _, _, _ -> createEngineObjectData("Foo", mapOf("id" to "foo")) } }
                }
                field("Query" to "value") {
                    resolver {
                        fn { args, _, _, _, ctx ->
                            contexts += ctx.requestContext
                            fetch(args.getValue("a") as Int)
                        }
                    }
                }
                field("Foo" to "derived") {
                    resolver {
                        fn { args, _, _, _, ctx ->
                            start.await()
                            contexts += ctx.requestContext
                            val selectionSet = ctx.engineSelectionSetFactory.engineSelectionSet(
                                "Query",
                                "value(a: \$a)",
                                mapOf("a" to args.getValue("a")),
                            )
                            ctx.query(selectionSet).fetchAs<Int>("value")
                        }
                    }
                }
            }

        @Test
        fun `missing nullable resolver output is reported only in the deferred patch`() {
            assertMissingOutput(nonNull = false)
        }

        @Test
        fun `missing non null resolver output completes the defer with an error`() {
            assertMissingOutput(nonNull = true)
        }

        private fun assertMissingOutput(nonNull: Boolean) {
            val valueType = if (nonNull) "Int!" else "Int"
            MockTenantModuleBootstrapper(
                """
                    extend type Query { foo: Foo }
                    type Foo { id: ID, missing: $valueType }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            ResolvedEngineObjectData.Builder(schema.schema.getObjectType("Foo")).put("id", "foo").build()
                        }
                    }
                }
            }.runFeatureTest {
                val results = collectResults("{ foo { id ... @defer { missing } } }")
                assertInitial(results.initial, mapOf("foo" to mapOf("id" to "foo")))
                val updates = updates(results)
                val errors = if (nonNull) {
                    assertTrue(updates.flatMap { it.incremental }.isEmpty())
                    updates.flatMap { it.completed }.single().errors
                } else {
                    val patch = updates.flatMap { it.incremental }.single() as IncrementalEntry.Object
                    assertEquals(mapOf("missing" to null), patch.data)
                    assertTrue(updates.flatMap { it.completed }.all { it.errors.isEmpty() })
                    patch.errors
                }
                assertEquals(1, errors.size)
                assertEquals(listOf("foo", "missing"), errors.single().path)
                assertEquals(ResolverOutputMissingFieldException.GRAPHQL_ERROR_CODE, errors.single().extensions["code"])
            }
        }

        private fun assertInitial(
            initial: ExecutionResult.Initial,
            data: Map<String, Any?>
        ) {
            assertEquals(data, initial.getData())
            assertTrue(initial.errors.isEmpty(), initial.errors.toString())
            assertTrue(initial.hasNext)
        }

        private fun updates(results: CollectedResults): List<ExecutionResult.Update> {
            val updates = results.updates
            assertTrue(updates.isNotEmpty())
            assertFalse(updates.last().hasNext)
            return updates
        }

        private fun assertNoErrors(results: CollectedResults) {
            assertTrue(results.initial.errors.isEmpty())
            for (update in updates(results)) {
                assertTrue(update.incremental.all { it.errors.isEmpty() }, update.toSpecification().toString())
                assertTrue(update.completed.all { it.errors.isEmpty() }, update.toSpecification().toString())
            }
        }

        private fun assertDeferredError(
            results: CollectedResults,
            path: List<Any>,
            message: String
        ) {
            val errors = updates(results).flatMap { update ->
                update.incremental.flatMap { it.errors } + update.completed.flatMap { it.errors }
            }
            assertEquals(1, errors.size, errors.toString())
            assertEquals(path, errors.single().path)
            assertTrue(errors.single().message.contains(message), errors.single().message)
        }
    }

    // These cases are adapted from graphql-js.
    // https://github.com/graphql/graphql-js/blob/ee5ce41d4b68d1852306d3b56dba2cbbb6c43fea/src/execution/incremental/__tests__/defer-test.ts
    @Nested
    inner class GraphQLJsTests {
        @Test
        fun `disabled framework flags do not disable explicit incremental execution`() {
            fixture().assertQuery(
                query = """
                    query(${'$'}enabled: Boolean!) {
                      ... @defer {
                        hero {
                          id
                          ...Name @defer(if: true)
                          ... @defer(if: ${'$'}enabled) {
                            friends {
                              id
                              ... @defer { name }
                            }
                          }
                          ... @defer(if: false) { immediate: name }
                          ... @defer @skip(if: true) { skipped: name }
                        }
                      }
                    }
                    fragment Name on Hero { name }
                """.trimIndent(),
                variables = mapOf("enabled" to true),
                flagManager = FlagManager.Disabled,
                expected = """
                    [
                      {data: {}, pending: [{id: "root", path: []}], hasNext: true},
                      {
                        incremental: [{id: "root", data: {hero: {id: "1", immediate: "Luke"}}}],
                        pending: [{id: "name", path: ["hero"]}, {id: "friends", path: ["hero"]}],
                        completed: [{id: "root"}], hasNext: true
                      },
                      {
                        incremental: [
                          {id: "name", data: {name: "Luke"}},
                          {id: "friends", data: {friends: [{id: "2"}, {id: "3"}, {id: "4"}]}}
                        ],
                        pending: [
                          {id: "friend0", path: ["hero", "friends", 0]},
                          {id: "friend1", path: ["hero", "friends", 1]},
                          {id: "friend2", path: ["hero", "friends", 2]}
                        ],
                        completed: [{id: "name"}, {id: "friends"}], hasNext: true
                      },
                      {
                        incremental: [
                          {id: "friend0", data: {name: "Han"}},
                          {id: "friend1", data: {name: "Leia"}},
                          {id: "friend2", data: {name: "C-3PO"}}
                        ],
                        completed: [{id: "friend0"}, {id: "friend1"}, {id: "friend2"}], hasNext: false
                      }
                    ]
                """.trimIndent(),
            )
        }

        @Test
        fun `default defer flag permits single-result execution of deferred queries`() {
            fixture().runFeatureTest(engineConfig = EngineConfiguration.featureTestDefault.copy(flagManager = FlagManager.Default)) {
                runQuery("{ hero { id ... @defer { name } } }")
                    .assertJson("""{"data":{"hero":{"id":"1","name":"Luke"}}}""")
            }
        }

        @Test
        fun `initial response does not wait for unrelated deferred resolution`() {
            val finishName = CompletableDeferred<String>()
            fixture(resolvers = mapOf(("Hero" to "name") to suspend { finishName.await() })).assertQuery(
                query = "{ hero { id ... @defer { name } } }",
                expected = """
                    [
                      {
                        "data": {"hero": {"id": "1"}},
                        "pending": [{"id": "0", "path": ["hero"]}],
                        "hasNext": true
                      },
                      {
                        "incremental": [{"id": "0", "data": {"name": "Luke"}}],
                        "completed": [{"id": "0"}],
                        "hasNext": false
                      }
                    ]
                """.trimIndent(),
                onInitial = {
                    assertFalse(finishName.isCompleted)
                    finishName.complete("Luke")
                },
            )
            assertTrue(finishName.isCompleted)
        }

        @Test
        fun `defers a named fragment containing scalar fields`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    id
                    ...NameFragment @defer
                  }
                }
                fragment NameFragment on Hero {
                  name
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"id": "1"}},
                    "pending": [{"id": "0", "path": ["hero"]}],
                    "hasNext": true
                  },
                  {
                    "incremental": [{"data": {"name": "Luke"}, "id": "0"}],
                    "completed": [{"id": "0"}],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `disables defer with a false if argument`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    id
                    ...NameFragment @defer(if: false)
                  }
                }
                fragment NameFragment on Hero {
                  name
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"id": "1", "name": "Luke"}}
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `keeps defer enabled when the nullable if variable is omitted`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery(${'$'}shouldDefer: Boolean) {
                  hero {
                    id
                    ...NameFragment @defer(if: ${'$'}shouldDefer)
                  }
                }
                fragment NameFragment on Hero {
                  name
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"id": "1"}},
                    "pending": [{"id": "0", "path": ["hero"]}],
                    "hasNext": true
                  },
                  {
                    "incremental": [{"data": {"name": "Luke"}, "id": "0"}],
                    "completed": [{"id": "0"}],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `defers a labeled fragment at the query root`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  ...QueryFragment @defer(label: "DeferQuery")
                }
                fragment QueryFragment on Query {
                  hero {
                    id
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {},
                    "pending": [{"id": "0", "path": [], "label": "DeferQuery"}],
                    "hasNext": true
                  },
                  {
                    "incremental": [{"data": {"hero": {"id": "1"}}, "id": "0"}],
                    "completed": [{"id": "0"}],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `delivers nullable errors in a deferred root fragment`() {
            fixture(resolvers = mapOf(("Hero" to "name") to suspend { error("bad") })).assertQuery(
                query = """
                query HeroNameQuery {
                  ...QueryFragment @defer(label: "DeferQuery")
                }
                fragment QueryFragment on Query {
                  hero {
                    name
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {},
                    "pending": [
                      {
                        "id": "0",
                        "path": [],
                        "label": "DeferQuery"
                      }
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {
                        "data": {
                          "hero": {
                            "name": null
                          }
                        },
                        "errors": [
                          {
                            "path": ["hero", "name"]
                          }
                        ],
                        "id": "0"
                      }
                    ],
                    "completed": [
                      {
                        "id": "0"
                      }
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `defers a fragment inside another deferred fragment`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    ...TopFragment @defer(label: "DeferTop")
                  }
                }
                fragment TopFragment on Hero {
                  id
                  ...NestedFragment @defer(label: "DeferNested")
                }
                fragment NestedFragment on Hero {
                  friends {
                    name
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {}},
                    "pending": [{"id": "0", "path": ["hero"], "label": "DeferTop"}],
                    "hasNext": true
                  },
                  {
                    "pending": [{"id": "1", "path": ["hero"], "label": "DeferNested"}],
                    "incremental": [
                      {"data": {"id": "1"}, "id": "0"},
                      {"data": {"friends": [{"name": "Han"}, {"name": "Leia"}, {"name": "C-3PO"}]}, "id": "1"}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
                deliveryOrder = listOf(listOf("hero", "id") to listOf("hero", "friends")),
            )
        }

        @Test
        fun `deduplicates a fragment when its deferred spread comes first`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    ...TopFragment @defer(label: "DeferTop")
                    ...TopFragment
                  }
                }
                fragment TopFragment on Hero {
                  name
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"name": "Luke"}}
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `deduplicates a fragment when its non-deferred spread comes first`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    ...TopFragment
                    ...TopFragment @defer(label: "DeferTop")
                  }
                }
                fragment TopFragment on Hero {
                  name
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"name": "Luke"}}
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `defers an inline fragment`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    id
                    ... on Hero @defer(label: "InlineDeferred") {
                      name
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"id": "1"}},
                    "pending": [{"id": "0", "path": ["hero"], "label": "InlineDeferred"}],
                    "hasNext": true
                  },
                  {
                    "incremental": [{"data": {"name": "Luke"}, "id": "0"}],
                    "completed": [{"id": "0"}],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `does not announce an empty deferred fragment`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    ... @defer {
                      name @skip(if: true)
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {}}
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `emits children of an empty deferred fragment`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    ... @defer {
                      ... @defer {
                        name
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {}},
                    "pending": [{"id": "0", "path": ["hero"]}],
                    "hasNext": true
                  },
                  {
                    "incremental": [{"data": {"name": "Luke"}, "id": "0"}],
                    "completed": [{"id": "0"}],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `delivers separate labeled fragments with different scalar fields`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    ... @defer(label: "DeferID") {
                      id
                    }
                    ... @defer(label: "DeferName") {
                      name
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {}},
                    "pending": [
                      {"id": "0", "path": ["hero"], "label": "DeferID"},
                      {"id": "1", "path": ["hero"], "label": "DeferName"}
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {"data": {"id": "1"}, "id": "0"},
                      {"data": {"name": "Luke"}, "id": "1"}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `delivers different subfields of a shared object in separate defers`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  ... @defer(label: "DeferID") {
                    hero {
                      id
                    }
                  }
                  ... @defer(label: "DeferName") {
                    hero {
                      name
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {},
                    "pending": [
                      {"id": "0", "path": [], "label": "DeferID"},
                      {"id": "1", "path": [], "label": "DeferName"}
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {"data": {"hero": {}}, "id": "0"},
                      {"data": {"id": "1"}, "id": "0", "subPath": ["hero"]},
                      {"data": {"name": "Luke"}, "id": "1", "subPath": ["hero"]}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `delivers suspending subfields of a shared object in separate defers`() {
            fixture(
                resolvers = mapOf(
                    ("Hero" to "id") to suspend {
                        yield()
                        "1"
                    },
                    ("Hero" to "name") to suspend {
                        yield()
                        "Luke"
                    },
                ),
            ).assertQuery(
                query = """
                query HeroNameQuery {
                  ... @defer(label: "DeferID") {
                    hero {
                      id
                    }
                  }
                  ... @defer(label: "DeferName") {
                    hero {
                      name
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {},
                    "pending": [
                      {"id": "0", "path": [], "label": "DeferID"},
                      {"id": "1", "path": [], "label": "DeferName"}
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {"data": {"hero": {}}, "id": "0"},
                      {"data": {"id": "1"}, "id": "0", "subPath": ["hero"]},
                      {"data": {"name": "Luke"}, "id": "1", "subPath": ["hero"]}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `delivers sibling defers at different response depths`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    ... @defer(label: "DeferID") {
                      id
                    }
                  }
                  ... @defer(label: "DeferName") {
                    hero {
                      name
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {}},
                    "pending": [
                      {"id": "0", "path": ["hero"], "label": "DeferID"},
                      {"id": "1", "path": [], "label": "DeferName"}
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {"data": {"id": "1"}, "id": "0"},
                      {"data": {"name": "Luke"}, "id": "1", "subPath": ["hero"]}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `delivers nested defers at different response depths`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  ... @defer(label: "DeferName") {
                    hero {
                      name
                      ... @defer(label: "DeferID") {
                        id
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {},
                    "pending": [{"id": "0", "path": [], "label": "DeferName"}],
                    "hasNext": true
                  },
                  {
                    "pending": [{"id": "1", "path": ["hero"], "label": "DeferID"}],
                    "incremental": [
                      {"data": {"hero": {"name": "Luke"}}, "id": "0"},
                      {"data": {"id": "1"}, "id": "1"}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `deduplicates nested fragments separately for each list item`() {
            fixture().assertQuery(
                query = """
                query {
                  hero {
                    friends {
                      ... @defer {
                        ...FriendFrag
                        ... @defer {
                          ...FriendFrag
                          ... @defer {
                            ...FriendFrag
                            ... @defer {
                              ...FriendFrag
                            }
                          }
                        }
                      }
                    }
                  }
                }

                fragment FriendFrag on Friend {
                  id
                  name
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"friends": [{}, {}, {}]}},
                    "pending": [
                      {"id": "0", "path": ["hero", "friends", 0]},
                      {"id": "1", "path": ["hero", "friends", 1]},
                      {"id": "2", "path": ["hero", "friends", 2]}
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {"data": {"id": "2", "name": "Han"}, "id": "0"},
                      {"data": {"id": "3", "name": "Leia"}, "id": "1"},
                      {"data": {"id": "4", "name": "C-3PO"}, "id": "2"}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"},
                      {"id": "2"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `deduplicates fields already present in the initial result`() {
            fixture().assertQuery(
                query = """
                query {
                  hero {
                    nestedObject {
                      deeperObject {
                        foo
                      }
                    }
                    anotherNestedObject {
                      deeperObject {
                        foo
                      }
                    }
                    ... @defer {
                      nestedObject {
                        deeperObject {
                          bar
                        }
                      }
                      anotherNestedObject {
                        deeperObject {
                          foo
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"nestedObject": {"deeperObject": {"foo": "foo"}}, "anotherNestedObject": {"deeperObject": {"foo": "foo"}}}},
                    "pending": [{"id": "0", "path": ["hero"]}],
                    "hasNext": true
                  },
                  {
                    "incremental": [{"data": {"bar": "bar"}, "id": "0", "subPath": ["nestedObject", "deeperObject"]}],
                    "completed": [{"id": "0"}],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `deduplicates fields already present in a parent defer result`() {
            fixture().assertQuery(
                query = """
                query {
                  hero {
                    ... @defer {
                      nestedObject {
                        deeperObject {
                          foo
                          ... @defer {
                            foo
                            bar
                          }
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {}},
                    "pending": [{"id": "0", "path": ["hero"]}],
                    "hasNext": true
                  },
                  {
                    "pending": [{"id": "1", "path": ["hero", "nestedObject", "deeperObject"]}],
                    "incremental": [
                      {"data": {"nestedObject": {"deeperObject": {"foo": "foo"}}}, "id": "0"},
                      {"data": {"bar": "bar"}, "id": "1"}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `deduplicates fields across several nested defer levels`() {
            fixture().assertQuery(
                query = """
                query {
                  hero {
                    nestedObject {
                      deeperObject {
                        foo
                      }
                    }
                    ... @defer {
                      nestedObject {
                        deeperObject {
                          foo
                          bar
                        }
                        ... @defer {
                          deeperObject {
                            foo
                            bar
                            baz
                            ... @defer {
                              foo
                              bar
                              baz
                              bak
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"nestedObject": {"deeperObject": {"foo": "foo"}}}},
                    "pending": [{"id": "0", "path": ["hero"]}],
                    "hasNext": true
                  },
                  {
                    "pending": [
                      {"id": "1", "path": ["hero", "nestedObject"]},
                      {"id": "2", "path": ["hero", "nestedObject", "deeperObject"]}
                    ],
                    "incremental": [
                      {"data": {"bar": "bar"}, "id": "0", "subPath": ["nestedObject", "deeperObject"]},
                      {"data": {"baz": "baz"}, "id": "1", "subPath": ["deeperObject"]},
                      {"data": {"bak": "bak"}, "id": "2"}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"},
                      {"id": "2"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
                deliveryOrder = listOf(
                    listOf("hero", "nestedObject", "deeperObject", "bar") to listOf("hero", "nestedObject", "deeperObject", "baz"),
                    listOf("hero", "nestedObject", "deeperObject", "baz") to listOf("hero", "nestedObject", "deeperObject", "bak"),
                ),
            )
        }

        @Test
        fun `deduplicates fields in different branches at the same depth`() {
            fixture().assertQuery(
                query = """
                query {
                  hero {
                    nestedObject {
                      deeperObject {
                        ... @defer {
                          foo
                        }
                      }
                    }
                    ... @defer {
                      nestedObject {
                        deeperObject {
                          ... @defer {
                            foo
                            bar
                          }
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"nestedObject": {"deeperObject": {}}}},
                    "pending": [
                      {"id": "0", "path": ["hero", "nestedObject", "deeperObject"]},
                      {"id": "1", "path": ["hero", "nestedObject", "deeperObject"]}
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {"data": {"foo": "foo"}, "id": "0"},
                      {"data": {"bar": "bar"}, "id": "1"}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
                sharedFields = mapOf(listOf("hero", "nestedObject", "deeperObject", "foo") to setOf("0", "1")),
            )
        }

        @Test
        fun `deduplicates fields in different branches at different depths`() {
            fixture().assertQuery(
                query = """
                query {
                  a {
                    b {
                      c {
                        d
                      }
                      ... @defer {
                        e {
                          f
                        }
                      }
                    }
                  }
                  ... @defer {
                    a {
                      b {
                        e {
                          f
                        }
                      }
                    }
                    g {
                      h
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"a": {"b": {"c": {"d": "d"}}}},
                    "pending": [
                      {"id": "0", "path": ["a", "b"]},
                      {"id": "1", "path": []}
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {"data": {"e": {"f": "f"}}, "id": "0"},
                      {"data": {"g": {"h": "h"}}, "id": "1"}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `groups aliased fields by defer combination and removes masked defers`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  ... @defer(label: "ID") {
                    hero {
                      id
                    }
                  }
                  ... @defer(label: "Name") {
                    hero {
                      name
                      shouldBeWithNameDespiteAdditionalDefer: name
                      ... @defer {
                        shouldBeWithNameDespiteAdditionalDefer: name
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {},
                    "pending": [
                      {"id": "0", "path": [], "label": "ID"},
                      {"id": "1", "path": [], "label": "Name"}
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {"data": {"hero": {}}, "id": "0"},
                      {"data": {"id": "1"}, "id": "0", "subPath": ["hero"]},
                      {"data": {"name": "Luke", "shouldBeWithNameDespiteAdditionalDefer": "Luke"}, "id": "1", "subPath": ["hero"]}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `handles nulls crossing defer boundaries when the null selection comes first`() {
            fixture().assertQuery(
                query = """
                query {
                  ... @defer {
                    a {
                      someField
                      b {
                        c {
                          nonNullErrorField
                        }
                      }
                    }
                  }
                  a {
                    ... @defer {
                      b {
                        c {
                          d
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {
                      "a": {}
                    },
                    "pending": [
                      {
                        "id": "0",
                        "path": []
                      },
                      {
                        "id": "1",
                        "path": ["a"]
                      }
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {
                        "data": {
                          "b": {
                            "c": {}
                          }
                        },
                        "id": "1"
                      },
                      {
                        "data": {
                          "d": "d"
                        },
                        "id": "1",
                        "subPath": [
                          "b",
                          "c"
                        ]
                      }
                    ],
                    "completed": [
                      {
                        "id": "0",
                        "errors": [
                          {
                            "path": ["a", "b", "c", "nonNullErrorField"]
                          }
                        ]
                      },
                      {
                        "id": "1"
                      }
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `handles nulls crossing defer boundaries when the value selection comes first`() {
            fixture().assertQuery(
                query = """
                query {
                  ... @defer {
                    a {
                      b {
                        c {
                          d
                        }
                      }
                    }
                  }
                  a {
                    ... @defer {
                      someField
                      b {
                        c {
                          nonNullErrorField
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {
                      "a": {}
                    },
                    "pending": [
                      {
                        "id": "0",
                        "path": []
                      },
                      {
                        "id": "1",
                        "path": ["a"]
                      }
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {
                        "data": {
                          "b": {
                            "c": {}
                          }
                        },
                        "id": "1"
                      },
                      {
                        "data": {
                          "d": "d"
                        },
                        "id": "0",
                        "subPath": [
                          "a",
                          "b",
                          "c"
                        ]
                      }
                    ],
                    "completed": [
                      {
                        "id": "0"
                      },
                      {
                        "id": "1",
                        "errors": [
                          {
                            "path": ["a", "b", "c", "nonNullErrorField"]
                          }
                        ]
                      }
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `completes multiple failing deferred groups with their own errors`() {
            fixture().assertQuery(
                query = """
                    query {
                      ... @defer(label: "first") {
                        a { b { c { someError: nonNullErrorField } } }
                      }
                      ... @defer(label: "second") {
                        a { b { c { anotherError: nonNullErrorField } } }
                      }
                    }
                """.trimIndent(),
                expected = """
                    [
                      {data: {}, pending: [
                        {id: "0", path: [], label: "first"},
                        {id: "1", path: [], label: "second"}
                      ], hasNext: true},
                      {completed: [
                        {id: "0", errors: [{path: ["a", "b", "c", "someError"]}]},
                        {id: "1", errors: [{path: ["a", "b", "c", "anotherError"]}]}
                      ], hasNext: false}
                    ]
                """.trimIndent(),
            )
        }

        @Test
        fun `reports one completion when several field groups fail for the same fragment`() {
            fixture().runFeatureTest {
                val results = collectResults(
                    """
                        query {
                          ... @defer(label: "data") {
                            a { b { someC: c { d } anotherC: c { d } } }
                          }
                          ... @defer(label: "errors") {
                            a { b {
                              someC: c { someError: nonNullErrorField }
                              anotherC: c { anotherError: nonNullErrorField }
                            } }
                          }
                        }
                    """.trimIndent()
                )
                val failedId = results.initial.pending.single { it.label == "errors" }.id
                val errors = results.updates.flatMap { it.completed }.single { it.id == failedId }.errors
                val paths = errors.map { it.path }
                val allowed = setOf(listOf("a", "b", "someC", "someError"), listOf("a", "b", "anotherC", "anotherError"))
                // Either failure can end this fragment before the other failure is collected.
                assertTrue(paths.isNotEmpty())
                assertTrue(paths.all { it in allowed }, paths.toString())
                assertEquals(paths.size, paths.distinct().size)
                val dataOnly = listOf(results.initial.toSpecification()) + results.updates.map { update ->
                    update.toSpecification() + (
                        "completed" to update.completed.map { completion ->
                            if (completion.id == failedId) completion.toSpecification() - "errors" else completion.toSpecification()
                        }
                    )
                }
                dataOnly.assertJsonStream(
                    """
                        [
                          {data: {}, pending: [
                            {id: "0", path: [], label: "data"}, {id: "1", path: [], label: "errors"}
                          ], hasNext: true},
                          {incremental: [{id: "0", data: {a: {b: {someC: {d: "d"}, anotherC: {d: "d"}}}}}],
                           completed: [{id: "0"}, {id: "1"}], hasNext: false}
                        ]
                    """.trimIndent()
                )
            }
        }

        @Test
        fun `discards deferred work below an object nulled in the initial result`() {
            // This null-bubbling expectation also applies without graphql-js's enableEarlyExecution option.
            fixture().assertQuery(
                query = """
                query {
                  hero {
                    nonNullName
                  }
                  ... @defer {
                    hero {
                      name
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {
                      "hero": null
                    },
                    "errors": [
                      {
                        "path": ["hero", "nonNullName"]
                      }
                    ]
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `allows null bubbling within a deferred result`() {
            // This null-bubbling expectation also applies without graphql-js's enableEarlyExecution option.
            fixture().assertQuery(
                query = """
                query {
                  ... @defer {
                    hero {
                      nonNullName
                      name
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {},
                    "pending": [
                      {
                        "id": "0",
                        "path": []
                      }
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {
                        "data": {
                          "hero": null
                        },
                        "errors": [
                          {
                            "path": ["hero", "nonNullName"]
                          }
                        ],
                        "id": "0"
                      }
                    ],
                    "completed": [
                      {
                        "id": "0"
                      }
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `deduplicates list fields`() {
            fixture().assertQuery(
                query = """
                query {
                  hero {
                    friends {
                      name
                    }
                    ... @defer {
                      friends {
                        name
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"friends": [{"name": "Han"}, {"name": "Leia"}, {"name": "C-3PO"}]}}
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `deduplicates a list returned by a suspending resolver`() {
            // The upstream async iterable is adapted to a suspending list resolver; iterator behavior is not covered.
            fixture(
                resolvers = mapOf(
                    ("Hero" to "friends") to suspend {
                        yield()
                        FRIENDS.take(1)
                    }
                )
            ).assertQuery(
                query = """
                query {
                  hero {
                    friends {
                      name
                    }
                    ... @defer {
                      friends {
                        name
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"friends": [{"name": "Han"}]}}
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `deduplicates an empty list returned by a suspending resolver`() {
            // The upstream async iterable is adapted to a suspending list resolver; iterator behavior is not covered.
            fixture(
                resolvers = mapOf(
                    ("Hero" to "friends") to suspend {
                        yield()
                        emptyList<Any?>()
                    }
                )
            ).assertQuery(
                query = """
                query {
                  hero {
                    friends {
                      name
                    }
                    ... @defer {
                      friends {
                        name
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"friends": []}}
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `delivers distinct deferred list subfields with item subpaths`() {
            fixture().assertQuery(
                query = """
                query {
                  hero {
                    friends {
                      name
                    }
                    ... @defer {
                      friends {
                        id
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"friends": [{"name": "Han"}, {"name": "Leia"}, {"name": "C-3PO"}]}},
                    "pending": [{"id": "0", "path": ["hero"]}],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {"data": {"id": "2"}, "id": "0", "subPath": ["friends", 0]},
                      {"data": {"id": "3"}, "id": "0", "subPath": ["friends", 1]},
                      {"data": {"id": "4"}, "id": "0", "subPath": ["friends", 2]}
                    ],
                    "completed": [{"id": "0"}],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `deduplicates empty lists`() {
            fixture(hero = HERO + ("friends" to emptyList<Any?>())).assertQuery(
                query = """
                query {
                  hero {
                    friends {
                      name
                    }
                    ... @defer {
                      friends {
                        name
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"friends": []}}
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `deduplicates null objects`() {
            fixture(hero = HERO + ("nestedObject" to null)).assertQuery(
                query = """
                query {
                  hero {
                    nestedObject {
                      name
                    }
                    ... @defer {
                      nestedObject {
                        name
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"nestedObject": null}}
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `deduplicates an object returned by a suspending resolver`() {
            fixture(
                resolvers = mapOf(
                    ("Hero" to "nestedObject") to suspend {
                        yield()
                        mapOf("name" to "foo")
                    }
                )
            ).assertQuery(
                query = """
                query {
                  hero {
                    nestedObject {
                      name
                    }
                    ... @defer {
                      nestedObject {
                        name
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"nestedObject": {"name": "foo"}}}
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `delivers nullable resolver errors in the deferred patch`() {
            fixture(resolvers = mapOf(("Hero" to "name") to suspend { error("bad") })).assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    id
                    ...NameFragment @defer
                  }
                }
                fragment NameFragment on Hero {
                  name
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {
                      "hero": {
                        "id": "1"
                      }
                    },
                    "pending": [
                      {
                        "id": "0",
                        "path": ["hero"]
                      }
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {
                        "data": {
                          "name": null
                        },
                        "id": "0",
                        "errors": [
                          {
                            "path": ["hero", "name"]
                          }
                        ]
                      }
                    ],
                    "completed": [
                      {
                        "id": "0"
                      }
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `reports deferred non-null violations on completion`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    id
                    ...NameFragment @defer
                  }
                }
                fragment NameFragment on Hero {
                  nonNullName
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {
                      "hero": {
                        "id": "1"
                      }
                    },
                    "pending": [
                      {
                        "id": "0",
                        "path": ["hero"]
                      }
                    ],
                    "hasNext": true
                  },
                  {
                    "completed": [
                      {
                        "id": "0",
                        "errors": [
                          {
                            "path": ["hero", "nonNullName"]
                          }
                        ]
                      }
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `discards deferred work after an initial non-null violation`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    nonNullName
                    ...NameFragment @defer
                  }
                }
                fragment NameFragment on Hero {
                  id
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "errors": [
                      {
                        "path": ["hero", "nonNullName"]
                      }
                    ],
                    "data": {
                      "hero": null
                    }
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `reports suspending non-null violations on completion`() {
            fixture(
                resolvers = mapOf(
                    ("Hero" to "nonNullName") to suspend {
                        yield()
                        null
                    }
                )
            ).assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    id
                    ...NameFragment @defer
                  }
                }
                fragment NameFragment on Hero {
                  nonNullName
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {
                      "hero": {
                        "id": "1"
                      }
                    },
                    "pending": [
                      {
                        "id": "0",
                        "path": ["hero"]
                      }
                    ],
                    "hasNext": true
                  },
                  {
                    "completed": [
                      {
                        "id": "0",
                        "errors": [
                          {
                            "path": ["hero", "nonNullName"]
                          }
                        ]
                      }
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `delivers a suspending parent before its deferred list items`() {
            fixture(
                resolvers = mapOf(
                    ("Hero" to "name") to suspend {
                        yield()
                        "slow"
                    }
                )
            ).assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    id
                    ...NameFragment @defer
                  }
                }
                fragment NameFragment on Hero {
                  name
                  friends {
                    ...NestedFragment @defer
                  }
                }
                fragment NestedFragment on Friend {
                  name
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"id": "1"}},
                    "pending": [{"id": "0", "path": ["hero"]}],
                    "hasNext": true
                  },
                  {
                    "pending": [
                      {"id": "1", "path": ["hero", "friends", 0]},
                      {"id": "2", "path": ["hero", "friends", 1]},
                      {"id": "3", "path": ["hero", "friends", 2]}
                    ],
                    "incremental": [
                      {"data": {"name": "slow", "friends": [{}, {}, {}]}, "id": "0"},
                      {"data": {"name": "Han"}, "id": "1"},
                      {"data": {"name": "Leia"}, "id": "2"},
                      {"data": {"name": "C-3PO"}, "id": "3"}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"},
                      {"id": "2"},
                      {"id": "3"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
                deliveryOrder = listOf(listOf("hero", "friends") to listOf("hero", "friends", 0, "name")),
            )
        }

        @Test
        fun `delivers a synchronous parent before its deferred list items`() {
            fixture().assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    id
                    ...NameFragment @defer
                  }
                }
                fragment NameFragment on Hero {
                  name
                  friends {
                    ...NestedFragment @defer
                  }
                }
                fragment NestedFragment on Friend {
                  name
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"id": "1"}},
                    "pending": [{"id": "0", "path": ["hero"]}],
                    "hasNext": true
                  },
                  {
                    "pending": [
                      {"id": "1", "path": ["hero", "friends", 0]},
                      {"id": "2", "path": ["hero", "friends", 1]},
                      {"id": "3", "path": ["hero", "friends", 2]}
                    ],
                    "incremental": [
                      {"data": {"name": "Luke", "friends": [{}, {}, {}]}, "id": "0"},
                      {"data": {"name": "Han"}, "id": "1"},
                      {"data": {"name": "Leia"}, "id": "2"},
                      {"data": {"name": "C-3PO"}, "id": "3"}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"},
                      {"id": "2"},
                      {"id": "3"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
                deliveryOrder = listOf(listOf("hero", "friends") to listOf("hero", "friends", 0, "name")),
            )
        }

        @Test
        fun `discards deferred work for a nulled item in a suspending list`() {
            // The upstream async iterable is adapted to a suspending list resolver; iterator behavior is not covered.
            fixture(
                resolvers = mapOf(
                    ("Hero" to "friends") to suspend {
                        yield()
                        FRIENDS.take(1)
                    },
                    ("Friend" to "nonNullName") to suspend {
                        yield()
                        null
                    },
                ),
            ).assertQuery(
                query = """
                query {
                  hero {
                    friends {
                      nonNullName
                      ...NameFragment @defer
                    }
                  }
                }
                fragment NameFragment on Friend {
                  name
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {
                      "hero": {
                        "friends": [
                          null
                        ]
                      }
                    },
                    "errors": [
                      {
                        "path": ["hero", "friends", 0, "nonNullName"]
                      }
                    ]
                  }
                ]
                """.trimIndent(),
            )
        }

        @Test
        fun `keeps deferred data out of the initial response while initial fields suspend`() {
            val idStarted = CompletableDeferred<Unit>()
            val finishId = CompletableDeferred<Unit>()
            fixture(
                resolvers = mapOf(
                    ("Hero" to "id") to suspend {
                        idStarted.complete(Unit)
                        finishId.await()
                        "1"
                    },
                    ("Hero" to "name") to suspend {
                        "Luke"
                    },
                ),
            ).assertQuery(
                query = """
                query HeroNameQuery {
                  hero {
                    id
                    ...NameFragment @defer
                  }
                }
                fragment NameFragment on Hero {
                  name
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {"hero": {"id": "1"}},
                    "pending": [{"id": "0", "path": ["hero"]}],
                    "hasNext": true
                  },
                  {
                    "incremental": [{"data": {"name": "Luke"}, "id": "0"}],
                    "completed": [{"id": "0"}],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
                control = {
                    idStarted.await()
                    finishId.complete(Unit)
                },
            )
        }

        @Test
        fun `delivers nested defers only after their parent data`() {
            val slowField = CompletableDeferred<String>()
            fixture(
                resolvers = mapOf(
                    ("a" to "someField") to suspend { slowField.await() },
                    ("b" to "c") to suspend {
                        mapOf("d" to "d")
                    },
                    ("b" to "e") to suspend {
                        mapOf("f" to "f")
                    },
                ),
            ).assertQuery(
                query = """
                query {
                  ... @defer {
                    a {
                      ... @defer {
                        b {
                          c { d }
                        }
                      }
                    }
                  }
                  ... @defer {
                    a {
                      someField
                      ... @defer {
                        b {
                          e { f }
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {},
                    "pending": [
                      {"id": "0", "path": []},
                      {"id": "1", "path": []}
                    ],
                    "hasNext": true
                  },
                  {
                    "pending": [{"id": "2", "path": ["a"]}],
                    "incremental": [
                      {"data": {"a": {}}, "id": "0"},
                      {"data": {"b": {}}, "id": "2"},
                      {"data": {"c": {"d": "d"}}, "id": "2", "subPath": ["b"]}
                    ],
                    "completed": [
                      {"id": "0"},
                      {"id": "2"}
                    ],
                    "hasNext": true
                  },
                  {
                    "pending": [{"id": "3", "path": ["a"]}],
                    "incremental": [
                      {"data": {"someField": "someField"}, "id": "1", "subPath": ["a"]},
                      {"data": {"e": {"f": "f"}}, "id": "3", "subPath": ["b"]}
                    ],
                    "completed": [
                      {"id": "1"},
                      {"id": "3"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
                onInitial = {
                    slowField.complete("someField")
                },
            )
        }

        @Test
        fun `deduplicates shared fields independently of sibling resolver timing`() {
            val finishC = CompletableDeferred<Unit>()
            val cCalls = AtomicInteger()
            fixture(
                resolvers = mapOf(
                    ("b" to "c") to suspend {
                        cCalls.incrementAndGet()
                        finishC.await()
                        mapOf("d" to "d")
                    },
                    ("b" to "e") to suspend {
                        mapOf("f" to "f")
                    },
                ),
            ).assertQuery(
                query = """
                query {
                  ... @defer {
                    a {
                      ... @defer {
                        b {
                          c { d }
                        }
                      }
                    }
                  }
                  ... @defer {
                    a {
                      ... @defer {
                        b {
                          c { d }
                          e { f }
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {},
                    "pending": [
                      {"id": "0", "path": []},
                      {"id": "1", "path": []}
                    ],
                    "hasNext": true
                  },
                  {
                    "pending": [
                      {"id": "2", "path": ["a"]},
                      {"id": "3", "path": ["a"]}
                    ],
                    "incremental": [{"data": {"a": {}}, "id": "0"}],
                    "completed": [
                      {"id": "0"},
                      {"id": "1"}
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {"data": {"b": {"c": {"d": "d"}}}, "id": "2"},
                      {"data": {"e": {"f": "f"}}, "id": "3", "subPath": ["b"]}
                    ],
                    "completed": [
                      {"id": "2"},
                      {"id": "3"}
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
                sharedFields = mapOf(listOf("a", "b", "c", "d") to setOf("2", "3")),
                onInitial = {
                    finishC.complete(Unit)
                },
            )
            assertEquals(1, cCalls.get())
        }

        @Test
        fun `suppresses a null patch that would overwrite delivered data`() {
            val finishError = CompletableDeferred<Unit>()
            lateinit var successfulGroupId: String
            fixture(
                resolvers = mapOf(
                    ("c" to "nonNullErrorField") to suspend {
                        finishError.await()
                        null
                    },
                ),
            ).assertQuery(
                query = """
                query {
                  a {
                    ... @defer {
                      b {
                        c {
                          d
                        }
                      }
                    }
                  }
                  ... @defer {
                    a {
                      someField
                      b {
                        c {
                          nonNullErrorField
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
                expected = """
                [
                  {
                    "data": {
                      "a": {}
                    },
                    "pending": [
                      {
                        "id": "0",
                        "path": []
                      },
                      {
                        "id": "1",
                        "path": ["a"]
                      }
                    ],
                    "hasNext": true
                  },
                  {
                    "incremental": [
                      {
                        "data": {
                          "b": {
                            "c": {}
                          }
                        },
                        "id": "1"
                      },
                      {
                        "data": {
                          "d": "d"
                        },
                        "id": "1",
                        "subPath": [
                          "b",
                          "c"
                        ]
                      }
                    ],
                    "completed": [
                      {
                        "id": "1"
                      }
                    ],
                    "hasNext": true
                  },
                  {
                    "completed": [
                      {
                        "id": "0",
                        "errors": [
                          {
                            "path": ["a", "b", "c", "nonNullErrorField"]
                          }
                        ]
                      }
                    ],
                    "hasNext": false
                  }
                ]
                """.trimIndent(),
                onInitial = { initial ->
                    successfulGroupId = initial.pending.single { it.path == listOf("a") }.id
                },
                onUpdate = { update ->
                    if (update.completed.any { it.id == successfulGroupId }) {
                        finishError.complete(Unit)
                    }
                },
            )
            assertTrue(finishError.isCompleted)
        }

        @Test
        fun `shared fields can change delivery groups when a sibling defer fails`() {
            for (failureFirst in listOf(false, true)) {
                val finishId = CompletableDeferred<Unit>()
                val finishError = CompletableDeferred<Unit>()
                if (failureFirst) finishError.complete(Unit) else finishId.complete(Unit)
                var releaseOnId: String? = null
                fixture(
                    resolvers = mapOf(
                        ("Hero" to "id") to suspend {
                            finishId.await()
                            "1"
                        },
                        ("Hero" to "nonNullName") to suspend {
                            finishError.await()
                            null
                        },
                    ),
                ).assertQuery(
                    query = """
                        { hero {
                            ... @defer(label: "A") { id nonNullName }
                            ... @defer(label: "B") { id }
                        } }
                    """.trimIndent(),
                    expected = """
                        [
                          {data: {hero: {}}, pending: [
                            {id: "a", path: ["hero"], label: "A"},
                            {id: "b", path: ["hero"], label: "B"}
                          ], hasNext: true},
                          {incremental: [{id: "a", data: {id: "1"}}], completed: [{id: "b"}], hasNext: true},
                          {completed: [{id: "a", errors: [{path: ["hero", "nonNullName"]}]}], hasNext: false}
                        ]
                    """.trimIndent(),
                    sharedFields = mapOf(listOf("hero", "id") to setOf("a", "b")),
                    onInitial = { initial ->
                        val firstLabel = if (failureFirst) "A" else "B"
                        releaseOnId = initial.pending.single { it.label == firstLabel }.id
                    },
                    onUpdate = { update ->
                        if (update.completed.any { it.id == releaseOnId }) {
                            if (failureFirst) finishId.complete(Unit) else finishError.complete(Unit)
                        }
                    },
                )
                assertTrue(finishId.isCompleted && finishError.isCompleted)
            }
        }

        private fun fixture(
            hero: Map<String, Any?> = HERO,
            a: Map<String, Any?> = A,
            resolvers: Map<Pair<String, String>, suspend () -> Any?> = emptyMap(),
        ): EngineTestModule =
            EngineTestModule(SCHEMA) {
                @Suppress("UNCHECKED_CAST")
                fun wrap(
                    type: GraphQLType,
                    value: Any?
                ): Any? =
                    when {
                        value == null -> null
                        type is GraphQLNonNull -> wrap(type.wrappedType, value)
                        type is GraphQLList -> (value as List<*>).map { wrap(type.wrappedType, it) }
                        type is GraphQLObjectType -> createEngineObjectData(
                            type,
                            (value as Map<String, Any?>)
                                .filterKeys { (type.name to it) !in resolvers }
                                .mapValues { (name, fieldValue) -> wrap(type.getFieldDefinition(name).type, fieldValue) },
                        )
                        else -> value
                    }

                mapOf("hero" to hero, "a" to a, "g" to mapOf("h" to "h")).forEach { (name, value) ->
                    fieldWithValue("Query" to name, wrap(queryType.getFieldDefinition(name).type, value))
                }
                resolvers.forEach { (coordinate, resolve) ->
                    field(coordinate) {
                        resolver {
                            fn { _, _, _, _, _ -> wrap(fieldType, resolve()) }
                        }
                    }
                }
            }

        private fun EngineTestModule.assertQuery(
            query: String,
            expected: String,
            variables: Map<String, Any?> = emptyMap(),
            flagManager: FlagManager = MockFlagManager.Enabled,
            deliveryOrder: List<Pair<List<Any>, List<Any>>> = emptyList(),
            sharedFields: Map<List<Any>, Set<String>> = emptyMap(),
            onInitial: suspend (ExecutionResult.Initial) -> Unit = {},
            onUpdate: suspend (ExecutionResult.Update) -> Unit = {},
            control: suspend CoroutineScope.() -> Unit = {},
        ) = runFeatureTest(engineConfig = EngineConfiguration.featureTestDefault.copy(flagManager = flagManager)) {
            val results = runBlocking {
                withTimeout(5.seconds) {
                    val collection = async {
                        reactiveQuery(query, variables).onEach {
                            when (it) {
                                is ExecutionResult.Initial -> onInitial(it)
                                is ExecutionResult.Update -> onUpdate(it)
                                is ExecutionResult.Standard -> Unit
                            }
                        }.map { it.toSpecification() }.toList()
                    }
                    control()
                    collection.await()
                }
            }
            results.assertJsonStream(expected, StreamErrorComparison.PATHS, deliveryOrder, sharedFields)
        }

        val FRIENDS = listOf(
            mapOf("id" to "2", "name" to "Han", "nonNullName" to null),
            mapOf("id" to "3", "name" to "Leia", "nonNullName" to null),
            mapOf("id" to "4", "name" to "C-3PO", "nonNullName" to null),
        )
        val HERO = mapOf(
            "id" to "1",
            "name" to "Luke",
            "nonNullName" to null,
            "friends" to FRIENDS,
            "nestedObject" to mapOf("deeperObject" to mapOf("foo" to "foo", "bar" to "bar", "baz" to "baz", "bak" to "bak")),
            "anotherNestedObject" to mapOf("deeperObject" to mapOf("foo" to "foo")),
        )
        val A = mapOf(
            "b" to mapOf("c" to mapOf("d" to "d", "nonNullErrorField" to null), "e" to mapOf("f" to "f")),
            "someField" to "someField",
        )
        val SCHEMA = """
            extend type Query { hero: Hero, a: a, g: g }
            type Hero {
                id: ID
                name: String
                nonNullName: String!
                friends: [Friend]
                nestedObject: NestedObject
                anotherNestedObject: AnotherNestedObject
            }
            type Friend { id: ID, name: String, nonNullName: String! }
            type NestedObject { deeperObject: DeeperObject, name: String }
            type AnotherNestedObject { deeperObject: DeeperObject }
            type DeeperObject { foo: String, bar: String, baz: String, bak: String }
            type a { b: b, someField: String }
            type b { c: c, e: e }
            type c { d: String, nonNullErrorField: String! }
            type e { f: String }
            type g { h: String }
        """.trimIndent()
    }
}
