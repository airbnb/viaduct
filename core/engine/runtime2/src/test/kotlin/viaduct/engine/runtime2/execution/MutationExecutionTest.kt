@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime2.execution

import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.invocationContextFor
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.mutation
import viaduct.engine.runtime.execution.query
import viaduct.engine.runtime2.execution.testing.runQPlanFeatureTest
import viaduct.graphql.test.assertJson
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

@OptIn(ExperimentalCoroutinesApi::class)
class MutationExecutionTest {
    @Test
    fun `the first payload read finishes before the next mutation writes to the same node`() =
        runTest(timeout = 10.seconds) {
            var storedVersion = 0
            var readCount = 0
            val writtenVersions = mutableListOf<Int>()
            val firstPayloadReadStarted = CompletableDeferred<Unit>()
            val allowFirstPayloadRead = CompletableDeferred<Unit>()
            val module = versionedProductMutations(
                // Both mutations return this product; its first version read must finish before the second write.
                nodeId = "same-product",
                writeVersion = { value ->
                    storedVersion = value
                    writtenVersions += value
                },
                readVersion = {
                    readCount += 1
                    if (readCount == 1) {
                        firstPayloadReadStarted.complete(Unit)
                        allowFirstPayloadRead.await()
                    }
                    storedVersion
                },
            )
            val execution = backgroundScope.async {
                module.engine2Viaduct(StandardTestDispatcher(testScheduler)).execute(
                    ExecutionInput.create(
                        """
                        mutation {
                          first: setVersion(value: 1) { version }
                          second: setVersion(value: 2) { version }
                        }
                        """.trimIndent(),
                    ),
                    SchemaId.Base,
                )
            }

            firstPayloadReadStarted.await()
            runCurrent()
            assertEquals(listOf(1), writtenVersions, "The second mutation must wait for the first payload")
            assertFalse(execution.isCompleted)

            allowFirstPayloadRead.complete(Unit)
            val result = execution.await()
            result.assertJson(
                """
                {"data": {
                    "first": {"version": 1},
                    "second": {"version": 2}
                }}
                """.trimIndent(),
            )
            assertEquals(listOf(1, 2), writtenVersions)
        }

    @Test
    fun `ctx mutation preserves namespace order aliases conditions and independent calls`() {
        var count = 0
        val events = mutableListOf<String>()
        EngineTestModule(
            """
            extend type Query { trigger: String @resolver }
            extend type Mutation { group: MutationGroup, update(amount: Int!): Int @resolver }
            type MutationGroup @namespaceType { update(amount: Int!): Int @resolver, nested: NestedMutations }
            type NestedMutations @namespaceType { update(amount: Int!): Int @resolver }
            """.trimIndent(),
        ) {
            for (type in listOf("Mutation", "MutationGroup", "NestedMutations")) {
                field(type to "update") {
                    resolver {
                        fn { arguments, _, _, _, _ ->
                            events += "start:$type"
                            yield()
                            count += arguments["amount"] as Int
                            events += "end:$type"
                            count
                        }
                    }
                }
            }
            field("Query" to "trigger") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val selections = ctx.engineSelectionSetFactory.engineSelectionSet(
                            "Mutation",
                            """
                            group {
                                first: update(amount: ${'$'}amount)
                                nested {
                                    skipped: update(amount: 99) @include(if: ${'$'}include)
                                    middle: update(amount: ${'$'}amount)
                                }
                                last: update(amount: ${'$'}amount)
                                first: update(amount: ${'$'}amount)
                            }
                            tail: update(amount: ${'$'}amount)
                            """.trimIndent(),
                            mapOf("amount" to 1, "include" to false),
                        )
                        (1..2).map {
                            val result = ctx.mutation(selections)
                            val group = result.fetchAs<EngineObjectData>("group")
                            val nested = group.fetchAs<EngineObjectData>("nested")
                            listOf(
                                group.fetchAs<Int>("first"),
                                nested.fetchAs<Int>("middle"),
                                group.fetchAs<Int>("last"),
                                result.fetchAs<Int>("tail"),
                            ).joinToString(":")
                        }.joinToString("|")
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ trigger }").assertJson("""{"data":{"trigger":"1:2:3:4|5:6:7:8"}}""")
        }
        val types = listOf("MutationGroup", "NestedMutations", "MutationGroup", "Mutation")
        assertEquals((types + types).flatMap { listOf("start:$it", "end:$it") }, events)
        assertEquals(8, count)
    }

    @Test
    fun `ctx mutation finishes payloads before advancing nested and outer mutations`() {
        var count = 0
        val events = mutableListOf<String>()
        EngineTestModule(
            """
            extend type Mutation { outer: String @resolver, update: Payload @resolver, later: Int @resolver }
            type Payload { observed: Int @resolver }
            """.trimIndent(),
        ) {
            field("Mutation" to "outer") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        events += "outer:start"
                        val selections = ctx.engineSelectionSetFactory.engineSelectionSet("Mutation", "first: update { observed } second: update { observed }", emptyMap())
                        val result = ctx.mutation(selections)
                        events += "outer:end"
                        val first = result.fetchAs<EngineObjectData>("first").fetchAs<Int>("observed")
                        val second = result.fetchAs<EngineObjectData>("second").fetchAs<Int>("observed")
                        "$first:$second"
                    }
                }
            }
            field("Mutation" to "update") {
                resolver {
                    fn { _, _, _, _, _ ->
                        events += "update:${++count}"
                        mapOf<String, Any?>()
                    }
                }
            }
            field("Payload" to "observed") {
                resolver {
                    fn { _, _, _, _, _ ->
                        yield()
                        events += "payload:$count"
                        count
                    }
                }
            }
            field("Mutation" to "later") {
                resolver {
                    fn { _, _, _, _, _ ->
                        events += "later"
                        ++count
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("mutation { outer later }").assertJson("""{"data":{"outer":"1:2","later":3}}""")
        }
        assertEquals(listOf("outer:start", "update:1", "payload:1", "update:2", "payload:2", "outer:end", "later"), events)
    }

    @Test
    fun `ctx mutation finishes later effects after nonnull failures`() {
        for (nullResult in listOf(false, true)) {
            var count = 0
            EngineTestModule("extend type Query { trigger: Int @resolver } extend type Mutation { update: Int! @resolver }") {
                field("Mutation" to "update") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            if (++count == 1) {
                                if (nullResult) null else error("nested mutation failed")
                            } else {
                                yield()
                                count
                            }
                        }
                    }
                }
                field("Query" to "trigger") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            val selections = ctx.engineSelectionSetFactory.engineSelectionSet("Mutation", "failed: update middle: update last: update", emptyMap())
                            ctx.mutation(selections).fetchAs<Int>("last")
                        }
                    }
                }
            }.runQPlanFeatureTest {
                runQuery("{ trigger }").assertJson("""{"data":{"trigger":3}}""")
            }
            assertEquals(3, count, "nullResult=$nullResult")
        }
    }

    @Test
    fun `a nonnull mutation failure preserves later mutation effects`() {
        for (nullResult in listOf(false, true)) {
            var calls = 0
            EngineTestModule("extend type Mutation { update: Int! @resolver }") {
                field("Mutation" to "update") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            if (++calls == 1) {
                                if (nullResult) null else error("mutation failed")
                            } else {
                                yield()
                                calls
                            }
                        }
                    }
                }
            }.runQPlanFeatureTest {
                val result = runQuery("mutation { first: update second: update }")
                assertNull(result.getData<Any?>())
                assertEquals(1, result.errors.size)
                assertEquals(listOf("first"), result.errors.first().path)
            }
            assertEquals(2, calls, "nullResult=$nullResult")
        }
    }

    @Test
    fun `nonnull failures preserve namespace and later mutation effects`() {
        var calls = 0
        EngineTestModule(
            """
                extend type Mutation { group: MutationGroup, update: Int @resolver }
                type MutationGroup @namespaceType { update: Int! @resolver }
            """.trimIndent(),
        ) {
            field("MutationGroup" to "update") {
                resolver {
                    fn { _, _, _, _, _ ->
                        if (++calls == 1) error("mutation failed")
                        yield()
                        calls
                    }
                }
            }
            field("Mutation" to "update") {
                resolver { fn { _, _, _, _, _ -> ++calls } }
            }
        }.runQPlanFeatureTest {
            val result = runQuery("mutation { group { first: update second: update } last: update }")
            assertEquals(mapOf("group" to null, "last" to 3), result.getData())
            assertEquals(1, result.errors.size)
            assertEquals(listOf("group", "first"), result.errors.single().path)
        }
        assertEquals(3, calls)
    }

    @Test
    fun `nonnull payload failures preserve later effects regardless of propagation`() {
        for (payloadType in listOf("Payload", "Payload!", "[Payload]", "[Payload!]", "[Payload!]!")) {
            var calls = 0
            EngineTestModule(
                """
                extend type Mutation { update: $payloadType @resolver }
                type Payload { value: Int! @resolver }
                """.trimIndent(),
            ) {
                field("Mutation" to "update") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            ++calls
                            if (payloadType.startsWith("[")) listOf(mapOf<String, Any?>()) else mapOf<String, Any?>()
                        }
                    }
                }
                field("Payload" to "value") {
                    resolver { fn { _, _, _, _, _ -> error("payload failed") } }
                }
            }.runQPlanFeatureTest {
                val result = runQuery("mutation { first: update { value } second: update { value } }")
                if (payloadType.endsWith("!")) assertNull(result.getData<Any?>())
                val firstPath: List<Any> = if (payloadType.startsWith("[")) listOf("first", 0, "value") else listOf("first", "value")
                assertEquals(firstPath, result.errors.first().path)
            }
            assertEquals(2, calls, payloadType)
        }
    }

    @Test
    fun `mutation aliases and nested namespaces complete in execution order`() {
        var count = 0
        val events = mutableListOf<String>()
        EngineTestModule(
            """
            extend type Mutation { group: MutationGroup, update: Int @resolver }
            type MutationGroup @namespaceType { update: Int @resolver, nested: NestedMutations }
            type NestedMutations @namespaceType { update: Int @resolver }
            """.trimIndent(),
        ) {
            for (type in listOf("Mutation", "MutationGroup", "NestedMutations")) {
                field(type to "update") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            events += "start:$type"
                            yield()
                            events += "end:$type"
                            ++count
                        }
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("mutation { group { a: update nested { update } b: update } first: update second: update first: update }")
                .assertJson("""{"data":{"group":{"a":1,"nested":{"update":2},"b":3},"first":4,"second":5}}""")
        }
        assertEquals(listOf("MutationGroup", "NestedMutations", "MutationGroup", "Mutation", "Mutation").flatMap { listOf("start:$it", "end:$it") }, events)
    }

    @Test
    fun `mutation resolvers can execute ctx query against current state`() {
        var count = 0
        EngineTestModule("extend type Query { current: Int @resolver } extend type Mutation { update: Int @resolver }") {
            field("Query" to "current") {
                resolver { fn { _, _, _, _, _ -> count } }
            }
            field("Mutation" to "update") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ++count
                        val selections = ctx.engineSelectionSetFactory.engineSelectionSet("Query", "current", emptyMap())
                        ctx.query(selectionSet = selections).fetchAs<Int>("current")
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("mutation { first: update second: update }")
                .assertJson("""{"data":{"first":1,"second":2}}""")
        }
    }

    @Test
    fun `a failing or null mutation does not prevent later mutations`() {
        var count = 0
        EngineTestModule("extend type Mutation { update: Int @resolver }") {
            field("Mutation" to "update") {
                resolver {
                    fn { _, _, _, _, _ ->
                        when (++count) {
                            1 -> error("mutation failed")
                            2 -> null
                            else -> count
                        }
                    }
                }
            }
        }.runQPlanFeatureTest {
            val result = runQuery("mutation { failed: update empty: update success: update }")
            assertEquals(mapOf("failed" to null, "empty" to null, "success" to 3), result.getData())
            assertEquals(1, result.errors.size)
            assertEquals(listOf("failed"), result.errors.single().path)
        }
        assertEquals(3, count)
    }

    private fun versionedProductMutations(
        nodeId: String,
        writeVersion: suspend (Int) -> Unit,
        readVersion: suspend () -> Int,
    ): EngineTestModule =
        EngineTestModule(
            """
            extend type Mutation {
                setVersion(value: Int!): Product @resolver
            }
            type Product implements Node {
                id: ID!
                version: Int
            }
            """.trimIndent(),
        ) {
            field("Mutation" to "setVersion") {
                resolver {
                    fn { selectors, context ->
                        selectors.associateWith { selector ->
                            writeVersion(selector.arguments.getValue("value") as Int)
                            val productReference = context.invocationContextFor(selector).createNodeReference(
                                nodeId,
                                schema.schema.getObjectType("Product"),
                            )
                            Result.success(productReference)
                        }
                    }
                }
            }
            type("Product") {
                nodeBatchedExecutor(selective = true) { selectors, _ ->
                    selectors.associateWith {
                        Result.success(createEngineObjectData("Product", mapOf("version" to readVersion())))
                    }
                }
            }
        }

    private fun EngineTestModule.engine2Viaduct(dispatcher: CoroutineDispatcher): StandardViaduct =
        StandardViaduct.Builder()
            .withSchemaConfiguration(SchemaConfiguration.fromSchema(fullSchema))
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(toModuleConfigSource()))
            .withLenientResolverValidation()
            .withFlagManager(object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
            })
            .withCoroutineInterop(object : CoroutineInterop by DefaultCoroutineInterop {
                override fun <T> enterThreadLocalCoroutineContext(
                    callerContext: CoroutineContext,
                    block: suspend CoroutineScope.() -> T,
                ): CompletableFuture<T> =
                    DefaultCoroutineInterop.enterThreadLocalCoroutineContext(callerContext) {
                        withContext(dispatcher, block)
                    }
            })
            .build()
}
