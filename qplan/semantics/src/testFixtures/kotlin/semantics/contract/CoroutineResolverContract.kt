@file:Suppress("ForbiddenImport")

package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResult
import model.EngineResultCell
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.SelectionForest
import model.UncompletedPromiseException
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.operationSelectionsFrom
import model.outputValue
import model.registry.FieldValueResolver
import model.registry.ResolverFragmentTemplates
import model.registry.ResolverRegistry
import model.requireField
import model.requireObjectField
import model.requireQueryTypeDef
import model.sameCompletedResultAs
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.toCanonicalMaterializeSelectionForest
import semantics.shared.CycleCheckState
import semantics.shared.CycleSlot
import semantics.shared.CycleTask
import semantics.shared.ResolverInvocationObservation
import semantics.shared.ResolverObserver
import semantics.shared.ResolverReadCycleException
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/** Shared subject adapter and request helpers for coroutine resolver contracts. Contains no tests. */
abstract class CoroutineResolverTestSubject {
    open val selectiveResolvers: Boolean
        get() = true
    open val coalescesGroundedKeys: Boolean get() = true
    open val usesSingularQueryOER: Boolean
        get() = false

    val coroutineResolverSubject: CoroutineResolverTestSubject
        get() = this

    /** Starts a request under [requestScope] and exposes its live root for lifecycle assertions. */
    abstract fun startResolution(
        operation: SharedOperationContext<*>,
        requestScope: CoroutineScope,
        selections: SelectionForest,
        cycleChecker: CycleCheckState,
    ): ObjectEngineResult

    fun resolve(
        operation: SharedOperationContext<*>,
        selections: SelectionForest,
        cycleChecker: CycleCheckState = CycleCheckState.create(),
    ): ObjectEngineResult =
        runBlocking {
            withTimeout(5_000) {
                coroutineScope { startResolution(operation, this, selections, cycleChecker) }
            }
        }
}

/** Common promise lifecycle and failure protocol for Resolver21-23 and Resolver26. */
interface CoroutineResolverContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `installs every local promise before any local producer starts`() {
        val registeredKeys = linkedSetOf<ObjectEngineResult.GroundKey>()
        var producerStarts = 0
        val invocationObserver = object : ResolverObserver {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                producerStarts += 1
                assertEquals(setOf("first", "second"), registeredKeys.map { it.field.name }.toSet())
            }
        }
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { first: Int!, second: Int! }",
                selectiveResolvers = selectiveResolvers,
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "first") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> 1 },
                        schema.requireField("Query", "second") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> 2 },
                    )
                },
            )
        val world = testWorld.assumptions
        val expectedKeys =
            setOf(
                world.schema.groundKey("Query", "first"),
                world.schema.groundKey("Query", "second"),
            )
        val cycleChecker =
            object : CycleCheckState {
                override fun registerWriter(
                    slot: CycleSlot,
                    writer: CycleTask,
                ) {
                    registeredKeys += writer.path.last() as ObjectEngineResult.GroundKey
                }

                override fun cycleCheck(
                    reader: CycleTask,
                    slot: CycleSlot,
                ) {}
            }
        val selections =
            world.fragmentFrom("fragment ignored on Query { first second }").subselections

        resolve(SharedOperationContext.create(world, resolverObserver = invocationObserver), selections, cycleChecker)

        assertEquals(expectedKeys, registeredKeys)
        assertEquals(2, producerStarts)
    }

    @Test
    fun `installs active child promises before publishing their ancestor value`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Child { first: Int!, second: Int! }
                    type Query { child: Child! }
                    """.trimIndent(),
                selectiveResolvers = selectiveResolvers,
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "child") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("Child") },
                        schema.requireField("Child", "first") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Child"),
                            ) { _, _ -> 1 },
                        schema.requireField("Child", "second") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Child"),
                            ) { _, _ -> 2 },
                    )
                },
            )
        val world = testWorld.assumptions
        val childKey = world.schema.groundKey("Query", "child")
        val expectedChildKeys =
            setOf(
                world.schema.groundKey("Child", "first"),
                world.schema.groundKey("Child", "second"),
            )
        val expectedChildResultKeys = expectedChildKeys
        var rootCell: EngineResultCell? = null
        val childRegistrations = linkedSetOf<ObjectEngineResult.GroundKey>()
        val cycleChecker =
            object : CycleCheckState {
                override fun registerWriter(
                    slot: CycleSlot,
                    writer: CycleTask,
                ) {
                    if (writer.path.size == 1) {
                        rootCell = requireNotNull(slot.cellOwner)
                    } else {
                        assertFailsWith<UncompletedPromiseException> {
                            assertNotNull(rootCell).value.get()
                        }
                        childRegistrations += writer.path.last() as ObjectEngineResult.GroundKey
                    }
                }

                override fun cycleCheck(
                    reader: CycleTask,
                    slot: CycleSlot,
                ) {}
            }
        val selections =
            world
                .fragmentFrom("fragment ignored on Query { child { first second } }")
                .subselections

        val result = resolve(SharedOperationContext.create(world), selections, cycleChecker)

        assertEquals(expectedChildKeys, childRegistrations)
        val child = assertIs<ObjectEngineResult>(result.getCell(childKey).value.get())
        assertEquals(expectedChildResultKeys, child.keys)
    }

    @Test
    fun `resolver read cycles become field errors before timeout`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { first: Int!, second: Int! }",
                selectiveResolvers = selectiveResolvers,
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "first") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { second }",
                                ),
                            ) { _, _ -> 1 },
                        schema.requireField("Query", "second") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> 2 },
                    )
                },
            )
        val first = testWorld.schema.requireObjectField("Query", "first")
        val second = testWorld.schema.requireObjectField("Query", "second")
        // Bypass registry cycle validation, while keeping each resolver's field identity valid.
        val cyclicResolver = FieldValueResolver.of(
            field = second,
            fragmentTemplates =
                ResolverFragmentTemplates(
                    objectFragmentTemplate =
                        testWorld.resolverRegistry
                            .resolver(first)
                            .objectFragment
                            .toCanonicalMaterializeSelectionForest(),
                    queryFragmentTemplate =
                        testWorld.resolverRegistry
                            .resolver(second)
                            .queryFragment
                            .toCanonicalMaterializeSelectionForest(),
                ),
            queryType = testWorld.schema.requireQueryTypeDef(),
            function = { _, _, _, _ -> 2 },
        )
        val malformedRegistry =
            registryOverride(testWorld.resolverRegistry) { field, delegate ->
                when (field) {
                    first -> delegate.resolver(first)
                    second -> cyclicResolver
                    else -> null
                }
            }
        val world =
            Assumptions.of(
                schema = testWorld.schema,
                resolverRegistry = malformedRegistry,
                selectiveResolvers = selectiveResolvers,
            )
        val selections =
            world.fragmentFrom("fragment ignored on Query { first }").subselections

        val result = resolve(SharedOperationContext.create(world), selections)
        val error = assertIs<ErrorEngineResult>(result.getCell(second.groundKey()).get())
        val failure = assertIs<ResolverReadCycleException>(error.errorData.cause)

        assertEquals(failure.cycle.first(), failure.cycle.last())
        assertTrue(failure.cycle.flatMap { it.path }.contains(second.groundKey()))
    }

    @Test
    fun `resolver failure becomes an error consumed by waiting siblings`() {
        val failure = IllegalStateException("resolver failed")
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { failed: Int!, waiting: Int! }",
                selectiveResolvers = selectiveResolvers,
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "failed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> throw failure },
                        schema.requireField("Query", "waiting") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { failed }",
                                ),
                            ) { input, _ ->
                                input.selectionValues().getValue("failed")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val selections =
            world.fragmentFrom("fragment ignored on Query { waiting }").subselections

        val result = resolve(SharedOperationContext.create(world), selections)
        for (fieldName in listOf("failed", "waiting")) {
            val error = assertIs<ErrorEngineResult>(result.getCell(world.schema.groundKey("Query", fieldName)).get())
            assertSame(failure, error.errorData.cause)
        }
        assertCompletedAndWriteOnce(result)
    }

    @Test
    fun `field and reference exceptions leave unrelated fields running`() {
        for (failure in listOf(IllegalStateException("resolver failed"), CancellationException("local cancellation"))) {
            val world = fieldFailureWorld(selectiveResolvers, failure).assumptions
            val result = resolve(SharedOperationContext.create(world), world.operationSelectionsFrom("{ failed reference items healthy }"))
            for (name in listOf("failed", "reference")) {
                assertSame(failure, assertIs<ErrorEngineResult>(result.getCell(world.schema.groundKey("Query", name)).get()).errorData.cause)
            }
            val items = assertIs<ListEngineResult>(result.getCell(world.schema.groundKey("Query", "items")).get())
            assertSame(failure, assertIs<ErrorEngineResult>(items[0].get()).errorData.cause)
            assertEquals(7, items[1].get())
            assertEquals(42, result.getCell(world.schema.groundKey("Query", "healthy")).get())
            assertCompletedAndWriteOnce(result)
        }
    }

    @Test
    fun `orchestration exceptions fail the request including Query fragment orchestration`() =
        runBlocking {
            for (failedField in listOf("consumer", "dependency")) {
                val failure = IllegalStateException("Failed to install $failedField")
                var consumerInvoked = false
                val world = queryFailureWorld(selectiveResolvers) { consumerInvoked = true }.assumptions
                val cycleChecker = object : CycleCheckState {
                    override fun registerWriter(
                        slot: CycleSlot,
                        writer: CycleTask
                    ) {
                        if ((writer.path.last() as ObjectEngineResult.ObjectKey).field.name == failedField) throw failure
                    }

                    override fun cycleCheck(
                        reader: CycleTask,
                        slot: CycleSlot
                    ) {}
                }
                val observed = CompletableDeferred<Throwable>()
                val requestJob = Job()
                val requestScope = CoroutineScope(
                    coroutineContext + requestJob + CoroutineExceptionHandler { _, cause -> observed.complete(cause) },
                )
                try {
                    startResolution(SharedOperationContext.create(world), requestScope, world.operationSelectionsFrom("{ consumer }"), cycleChecker)
                    assertSame(failure, withTimeout(5_000) { observed.await() })
                    assertTrue(requestJob.isCancelled)
                    assertFalse(consumerInvoked)
                } finally {
                    requestJob.cancelAndJoin()
                }
            }
        }

    @Test
    fun `JVM Error escapes unchanged`() =
        runBlocking {
            val failure = Error("process-level failure")
            val world = fieldFailureWorld(selectiveResolvers, failure).assumptions
            val observed = CompletableDeferred<Throwable>()
            val requestJob = Job()
            val requestScope = CoroutineScope(
                coroutineContext + requestJob + CoroutineExceptionHandler { _, cause -> observed.complete(cause) },
            )
            try {
                startResolution(SharedOperationContext.create(world), requestScope, world.operationSelectionsFrom("{ failed }"), CycleCheckState.create())
                assertSame(failure, withTimeout(5_000) { observed.await() })
                assertFalse(requestJob.isActive)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `Query producer failures become field errors without stopping unrelated fields`() {
        for (failure in listOf(IllegalStateException("Query producer failed"), CancellationException("local cancellation"))) {
            var consumerInvoked = false
            val world =
                queryFailureWorld(
                    selective = selectiveResolvers,
                    dependencyFailure = failure.takeIf { usesSingularQueryOER },
                ) { consumerInvoked = true }.assumptions
            if (usesSingularQueryOER) {
                val result = resolve(
                    SharedOperationContext.create(world),
                    world.operationSelectionsFrom("{ consumer reference healthy }"),
                )
                for (name in listOf("consumer", "reference")) {
                    assertSame(
                        failure,
                        assertIs<ErrorEngineResult>(
                            result.getCell(world.schema.groundKey("Query", name)).get(),
                        ).errorData.cause,
                    )
                }
                assertTrue(consumerInvoked)
                assertEquals(42, result.getCell(world.schema.groundKey("Query", "healthy")).get())
                assertCompletedAndWriteOnce(result)
                continue
            }
            val observer = object : ResolverObserver {
                override fun onQueryFragmentPrepared(
                    resolverOccurrenceId: ResolverOccurrenceId,
                    result: ObjectEngineResult
                ): Nothing = throw failure
            }
            val result = resolve(
                SharedOperationContext.create(world, resolverObserver = observer),
                world.operationSelectionsFrom("{ consumer reference healthy }"),
            )
            for (name in listOf("consumer", "reference")) {
                assertSame(failure, assertIs<ErrorEngineResult>(result.getCell(world.schema.groundKey("Query", name)).get()).errorData.cause)
            }
            assertFalse(consumerInvoked)
            assertEquals(42, result.getCell(world.schema.groundKey("Query", "healthy")).get())
            assertCompletedAndWriteOnce(result)
        }
    }

    @Test
    fun `independent reference Query preparation failures remain field local`() {
        for (
        failure in
        listOf(
            IllegalStateException("preparation failed"),
            CancellationException("local preparation cancellation"),
        )
        ) {
            var targetInvocations = 0
            var dependencyInvocations = 0
            val testWorld =
                TestWorld.fromSDL(
                    schemaSDL =
                        "type Query { first: Int!, second: Int!, target: Int!, dependency: Int!, healthy: Int! }",
                    selectiveResolvers = selectiveResolvers,
                    fieldResolvers = { schema ->
                        val empty = schema.emptyFragmentOf("Query")
                        val target = schema.requireObjectField("Query", "target")
                        mapOf(
                            schema.requireObjectField("Query", "first") to
                                fieldResolverOf(empty) { _, _ ->
                                    RootFieldReferenceData.of(listOf(target), emptyMap())
                                },
                            schema.requireObjectField("Query", "second") to
                                fieldResolverOf(empty) { _, _ ->
                                    RootFieldReferenceData.of(listOf(target), emptyMap())
                                },
                            target to
                                fieldResolverOf(
                                    empty,
                                    schema.fragmentFrom(
                                        "fragment TargetQuery on Query { dependency }",
                                    ),
                                ) { _, query, _ ->
                                    targetInvocations += 1
                                    query.outputValue("dependency")
                                },
                            schema.requireObjectField("Query", "dependency") to
                                fieldResolverOf(empty) { _, _ ->
                                    dependencyInvocations += 1
                                    7
                                },
                            schema.requireObjectField("Query", "healthy") to
                                fieldResolverOf(empty) { _, _ -> 42 },
                        )
                    },
                )
            val world = testWorld.assumptions
            val observer = object : ResolverObserver {
                override fun onQueryFragmentPrepared(
                    resolverOccurrenceId: ResolverOccurrenceId,
                    result: ObjectEngineResult,
                ): Nothing = throw failure
            }

            val result =
                resolve(
                    SharedOperationContext.create(world, resolverObserver = observer),
                    world.operationSelectionsFrom("{ first second healthy }"),
                )

            for (name in listOf("first", "second")) {
                assertSame(
                    failure,
                    assertIs<ErrorEngineResult>(
                        result.getCell(world.schema.groundKey("Query", name)).get(),
                    ).errorData.cause,
                )
            }
            assertEquals(0, targetInvocations)
            assertEquals(0, dependencyInvocations)
            assertEquals(42, result.getCell(world.schema.groundKey("Query", "healthy")).get())
        }
    }

    @Test
    fun `request cancellation cancels promises before field entry and during Query production`() =
        runBlocking {
            if (usesSingularQueryOER) {
                assertSharedQueryCancellation()
                return@runBlocking
            }
            for (cancelBeforeEntry in listOf(true, false)) {
                val requestJob = Job()
                val requestScope = CoroutineScope(coroutineContext + requestJob)
                val cancellation = CancellationException("request cancelled")
                var producerEntered = false
                var consumerInvoked = false
                val world = queryFailureWorld(selectiveResolvers) { consumerInvoked = true }.assumptions
                val observer = object : ResolverObserver {
                    override fun onQueryFragmentPrepared(
                        resolverOccurrenceId: ResolverOccurrenceId,
                        result: ObjectEngineResult
                    ): Nothing {
                        producerEntered = true
                        requestJob.cancel(cancellation)
                        throw cancellation
                    }
                }
                try {
                    val result = startResolution(
                        SharedOperationContext.create(world, resolverObserver = observer),
                        requestScope,
                        world.operationSelectionsFrom("{ consumer }"),
                        CycleCheckState.create(),
                    )
                    if (cancelBeforeEntry) requestJob.cancel(cancellation)
                    withTimeout(5_000) { requestJob.join() }
                    val failure = assertFailsWith<CancellationException> {
                        withTimeout(5_000) {
                            result.getCell(world.schema.groundKey("Query", "consumer")).value.await()
                        }
                    }
                    assertEquals(cancellation.message, failure.message)
                    assertEquals(!cancelBeforeEntry, producerEntered)
                    assertFalse(consumerInvoked)
                } finally {
                    requestJob.cancelAndJoin()
                }
            }
        }

    private suspend fun assertSharedQueryCancellation() {
        for (cancelBeforeEntry in listOf(true, false)) {
            val requestJob = Job()
            val requestScope = CoroutineScope(kotlin.coroutines.coroutineContext + requestJob)
            val cancellation = CancellationException("request cancelled")
            val producerEntered = CompletableDeferred<Unit>()
            val producerExited = CompletableDeferred<Unit>()
            var producerInvocations = 0
            var ownerInvocations = 0
            val testWorld =
                TestWorld.fromSDL(
                    selectiveResolvers = selectiveResolvers,
                    schemaSDL =
                        "type Query { dependency: Int!, first: Int!, second: Int! }",
                    fieldResolvers = { schema ->
                        val empty = schema.emptyFragmentOf("Query")
                        val ownerQuery =
                            schema.fragmentFrom(
                                "fragment OwnerQuery on Query { dependency }",
                            )
                        mapOf(
                            schema.requireObjectField("Query", "dependency") to
                                fieldResolverOf(empty) { _, _ ->
                                    producerInvocations += 1
                                    producerEntered.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        producerExited.complete(Unit)
                                    }
                                },
                            schema.requireObjectField("Query", "first") to
                                fieldResolverOf(empty, ownerQuery) { _, _, _ ->
                                    ownerInvocations += 1
                                    1
                                },
                            schema.requireObjectField("Query", "second") to
                                fieldResolverOf(empty, ownerQuery) { _, _, _ ->
                                    ownerInvocations += 1
                                    2
                                },
                        )
                    },
                )
            val world = testWorld.assumptions
            try {
                val result =
                    startResolution(
                        SharedOperationContext.create(world),
                        requestScope,
                        world.operationSelectionsFrom("{ first second }"),
                        CycleCheckState.create(),
                    )
                if (cancelBeforeEntry) {
                    requestJob.cancel(cancellation)
                } else {
                    withTimeout(5_000) { producerEntered.await() }
                    requestJob.cancel(cancellation)
                }
                withTimeout(5_000) { requestJob.join() }
                for (name in listOf("first", "second")) {
                    val failure = assertFailsWith<CancellationException> {
                        withTimeout(5_000) {
                            result
                                .getCell(world.schema.groundKey("Query", name))
                                .value
                                .await()
                        }
                    }
                    assertEquals(cancellation.message, failure.message)
                }
                assertEquals(if (cancelBeforeEntry) 0 else 1, producerInvocations)
                assertEquals(0, ownerInvocations)
                if (!cancelBeforeEntry) withTimeout(5_000) { producerExited.await() }
            } finally {
                requestJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `successful return is quiescent with write-once completed promises`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item { value: Int! }
                    type Query { items: [Item!]! }
                    """.trimIndent(),
                selectiveResolvers = selectiveResolvers,
                fieldResolvers = { schema ->
                    val items = schema.requireField("Query", "items")
                    mapOf(
                        items to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                listOf(
                                    schema.objectOf("Item"),
                                    schema.objectOf("Item"),
                                )
                            },
                        schema.requireField("Item", "value") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Item"),
                            ) { _, _ -> 7 },
                    )
                },
            )
        val world = testWorld.assumptions
        val selections =
            world.fragmentFrom("fragment ignored on Query { items { value } }").subselections

        val result = resolve(SharedOperationContext.create(world), selections)

        assertCompletedAndWriteOnce(result)
        assertTrue(result.sameCompletedResultAs(result))
    }
}

private fun assertCompletedAndWriteOnce(result: EngineResult?) {
    when (result) {
        null,
        is ErrorEngineResult,
        -> Unit
        is ListEngineResult ->
            result.indices.forEach { index ->
                assertCompletedAndWriteOnce(result[index].value.get())
            }
        is ObjectEngineResult ->
            result.keys.forEach { key ->
                val promise = result.getCell(key).value
                val value = promise.get()
                assertFalse(promise.complete(value))
                if (key !is ObjectEngineResult.ParentKey) {
                    assertCompletedAndWriteOnce(value)
                }
            }
        else -> Unit
    }
}

private fun registryOverride(
    delegate: ResolverRegistry,
    resolver: (ViaductSchema.ObjectField, ResolverRegistry) -> FieldValueResolver?,
): ResolverRegistry =
    object : ResolverRegistry {
        override fun createRootQueryInput(): EngineObjectData.Sync = delegate.createRootQueryInput()

        override fun contains(field: ViaductSchema.ObjectField): Boolean = resolver(field, delegate) != null

        override fun resolver(field: ViaductSchema.ObjectField): FieldValueResolver =
            resolver(field, delegate)
                ?: error(
                    "Missing overridden resolver: " +
                        "${field.containingDef.name}.${field.name}",
                )

        override fun mayDemandFrom(field: ViaductSchema.ObjectField): Set<ViaductSchema.ObjectField> = delegate.mayDemandFrom(field)
    }

private fun ViaductSchema.groundKey(
    typeName: String,
    fieldName: String,
): ObjectEngineResult.GroundKey =
    ObjectEngineResult.GroundKey.of(
        requireObjectField(typeName, fieldName),
        emptyMap(),
    )

private fun ViaductSchema.ObjectField.groundKey(): ObjectEngineResult.GroundKey = ObjectEngineResult.GroundKey.of(this, emptyMap())

// Throwing fixtures cannot use correctResolution, which re-invokes the resolver relation.
private fun CoroutineResolverContract.resolve(
    operation: SharedOperationContext<*>,
    selections: SelectionForest,
    cycleChecker: CycleCheckState = CycleCheckState.create(),
): ObjectEngineResult = coroutineResolverSubject.resolve(operation, selections, cycleChecker)

private val CoroutineResolverContract.selectiveResolvers: Boolean
    get() = coroutineResolverSubject.selectiveResolvers

private val CoroutineResolverContract.usesSingularQueryOER: Boolean
    get() = coroutineResolverSubject.usesSingularQueryOER

private fun CoroutineResolverContract.startResolution(
    operation: SharedOperationContext<*>,
    requestScope: CoroutineScope,
    selections: SelectionForest,
    cycleChecker: CycleCheckState,
): ObjectEngineResult = coroutineResolverSubject.startResolution(operation, requestScope, selections, cycleChecker)

private fun fieldFailureWorld(
    selective: Boolean,
    failure: Throwable
): TestWorld =
    TestWorld.fromSDL(
        selectiveResolvers = selective,
        schemaSDL = "type Query { failed: Int!, reference: Int!, items: [Int!]!, healthy: Int! }",
        fieldResolvers = { schema ->
            val fragment = schema.emptyFragmentOf("Query")
            val failed = schema.requireObjectField("Query", "failed")
            val reference = RootFieldReferenceData.of(listOf(failed), emptyMap())
            mapOf(
                failed to fieldResolverOf(fragment) { _, _ -> throw failure },
                schema.requireObjectField("Query", "reference") to fieldResolverOf(fragment) { _, _ -> reference },
                schema.requireObjectField("Query", "items") to fieldResolverOf(fragment) { _, _ -> listOf(reference, 7) },
                schema.requireObjectField("Query", "healthy") to fieldResolverOf(fragment) { _, _ -> 42 },
            )
        },
    )

private fun queryFailureWorld(
    selective: Boolean,
    dependencyFailure: Throwable? = null,
    onConsumer: () -> Unit,
): TestWorld =
    TestWorld.fromSDL(
        selectiveResolvers = selective,
        schemaSDL = "type Query { consumer: Int!, reference: Int!, dependency: Int!, healthy: Int! }",
        fieldResolvers = { schema ->
            val fragment = schema.emptyFragmentOf("Query")
            val consumer = schema.requireObjectField("Query", "consumer")
            mapOf(
                consumer to fieldResolverOf(fragment, schema.fragmentFrom("fragment Input on Query { dependency }")) { _, query, _ ->
                    onConsumer()
                    query.outputValue("dependency")
                },
                schema.requireObjectField("Query", "reference") to fieldResolverOf(fragment) { _, _ ->
                    RootFieldReferenceData.of(listOf(consumer), emptyMap())
                },
                schema.requireObjectField("Query", "dependency") to fieldResolverOf(fragment) { _, _ ->
                    dependencyFailure?.let { throw it }
                    7
                },
                schema.requireObjectField("Query", "healthy") to fieldResolverOf(fragment) { _, _ -> 42 },
            )
        },
    )
