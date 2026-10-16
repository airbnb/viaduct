package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.ExecutionStepInfo
import graphql.execution.NonNullableFieldWasNullError
import graphql.execution.NonNullableFieldWasNullException
import graphql.execution.ResultPath
import graphql.schema.GraphQLNonNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.IncrementalEntry
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.runtime.execution.CompletionErrors.FieldCompletionException
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

class ExecuteRootSelectionSetTest {
    private val schema = EngineSchema("type Query { value: Int }".asSchema)
    private val parameters = mkExecutionParameters().let { base ->
        base.copy(
            _engineExecutionContext = ContextMocks(
                myFullSchema = base.engineExecutionContext.fullSchema,
                myFlagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION),
            ).engineExecutionContext,
        )
    }
    private val ordinaryParameters = parameters.copy(
        _engineExecutionContext = ContextMocks(
            myFullSchema = parameters.engineExecutionContext.fullSchema,
            myFlagManager = MockFlagManager.Disabled,
        ).engineExecutionContext,
    )

    @Test
    fun `object completion preserves data and field errors`() {
        val data = mapOf("value" to null)
        val errors = listOf(error("field failed"))

        val result = ViaductExecutionStrategy.buildExecutionResult(
            Result.success(FieldCompletionResult.ObjectResult(data, false)),
            errors,
        )

        assertEquals(data, result.getData<Map<String, Any?>?>())
        assertEquals(errors, result.errors)
        assertTrue(result.isDataPresent)
    }

    @Test
    fun `without deferred work both execution modes return a complete response`() =
        runTest {
            val data = mapOf("value" to null)
            val errors = listOf(error("field failed"))
            val completion = FieldCompletionResult.ObjectResult(data, false)

            for (ctx in listOf(ordinaryParameters, parameters)) {
                val result = ExecuteRootSelectionSet.buildExecutionResults(ctx, Result.success(completion), errors)

                assertFalse(result is IncrementalExecutionResult)
                assertEquals(data, result.getData<Map<String, Any?>?>())
                assertEquals(errors, result.errors)
            }
        }

    @Test
    fun `disabled incremental execution rejects deferred groups or tasks`() =
        runTest {
            val group = DeferDeliveryGroup(ResultPath.rootPath(), "later", null)
            val task = ExecutionGroupTask(setOf(group), group.path) {
                kotlin.error("Deferred tasks must not run when incremental execution is disabled")
            }
            val workItems = listOf(
                Work(setOf(group), emptyList()),
                Work(emptySet(), listOf(task)),
            )

            for (work in workItems) {
                val completion = FieldCompletionResult.ObjectResult(emptyMap(), false, work)

                assertThrows<IllegalStateException> {
                    ExecuteRootSelectionSet.buildExecutionResults(ordinaryParameters, Result.success(completion), emptyList())
                }
            }
        }

    @Test
    fun `groups without tasks still produce a synchronous result`() =
        runTest {
            val group = DeferDeliveryGroup(ResultPath.rootPath(), "empty", null)
            val completion = FieldCompletionResult.ObjectResult(emptyMap(), false, Work(setOf(group), emptyList()))

            val result = ExecuteRootSelectionSet.buildExecutionResults(parameters, Result.success(completion), emptyList())

            assertFalse(result is IncrementalExecutionResult)
            assertEquals(emptyMap<String, Any?>(), result.getData<Map<String, Any?>?>())
            assertTrue(result.errors.isEmpty())
        }

    @Test
    fun `root completion yields pending work and keeps initial and deferred errors separate`() =
        runTest {
            val initialError = error("initial error")
            val fieldError = error("deferred field error")
            val groupError = error("deferred group failure")
            val a = DeferDeliveryGroup(ResultPath.rootPath(), "A", null)
            val b = DeferDeliveryGroup(ResultPath.rootPath(), "B", null)
            val work = Work(
                linkedSetOf(a, b),
                listOf(
                    ExecutionGroupTask(setOf(a), a.path) {
                        ExecutionGroupResult(mapOf("later" to null), listOf(fieldError), Work.empty)
                    },
                    ExecutionGroupTask(setOf(b), b.path) {
                        ExecutionGroupResult(null, listOf(groupError), Work.empty)
                    },
                ),
            )
            val completion = FieldCompletionResult.ObjectResult(mapOf("value" to 1), false, work)

            val initial = ExecuteRootSelectionSet.buildExecutionResults(parameters, Result.success(completion), listOf(initialError)) as IncrementalExecutionResult
            val updates = initial.updates.toList()

            assertEquals(mapOf("value" to 1), initial.getData<Map<String, Any?>?>())
            assertEquals(listOf(initialError), initial.errors)
            assertEquals(listOf("0", "1"), initial.pending.map { it.id })
            assertEquals(listOf("A", "B"), initial.pending.map { it.label })
            assertTrue(initial.hasNext)
            val incremental = updates.flatMap { it.incremental }.single() as IncrementalEntry.Object
            assertEquals("0", incremental.id)
            assertEquals(mapOf("later" to null), incremental.data)
            assertEquals(listOf(fieldError), incremental.errors)
            val completed = updates.flatMap { it.completed }.associateBy { it.id }
            assertEquals(emptyList<GraphQLError>(), completed.getValue("0").errors)
            assertEquals(listOf(groupError), completed.getValue("1").errors)
            assertFalse(updates.last().hasNext)
        }

    @Test
    fun `stopping incremental delivery cancels outstanding tasks`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val a = DeferDeliveryGroup(ResultPath.rootPath(), "A", null)
            val b = DeferDeliveryGroup(ResultPath.rootPath(), "B", null)
            val work = Work(
                linkedSetOf(a, b),
                listOf(
                    ExecutionGroupTask(setOf(a), a.path) {
                        started.await()
                        ExecutionGroupResult(mapOf("later" to 2), emptyList(), Work.empty)
                    },
                    ExecutionGroupTask(setOf(b), b.path) {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    },
                ),
            )
            val completion = FieldCompletionResult.ObjectResult(emptyMap(), false, work)

            val result = ExecuteRootSelectionSet.buildExecutionResults(parameters, Result.success(completion), emptyList()) as IncrementalExecutionResult
            val updates = result.updates.take(1).toList()

            assertEquals(1, updates.size)
            cancelled.await()
        }

    @Test
    fun `object and list results carry work from child values`() =
        runTest {
            val path = ResultPath.rootPath().segment("objects").segment(1).segment("child")
            val group = DeferDeliveryGroup(path, "child", null)
            val task = ExecutionGroupTask(setOf(group), path) {
                ExecutionGroupResult(mapOf("value" to 2), emptyList(), Work.empty)
            }
            val child = FieldCompletionResult.ObjectResult(emptyMap(), true, Work(setOf(group), listOf(task)))
            val stepInfo = ExecutionStepInfo.newExecutionStepInfo().type(schema.schema.queryType).path(ResultPath.rootPath()).build()
            val rootParameters = parameters.copy(executionStepInfo = stepInfo)
            val completedObject = ExecutionPlanResult.fromFieldResults(mapOf("child" to child, "value" to FieldCompletionResult.ScalarResult(1, true)))
            val obj = FieldCompletionResult.obj(completedObject.data, rootParameters, completedObject.work)
            val items = listOf(FieldCompletionResult.NullResult(true), obj)
            val list = FieldCompletionResult.list(items.map { it.value }, items, rootParameters)
            val completedRoot = ExecutionPlanResult.fromFieldResults(mapOf("objects" to list))
            val root = FieldCompletionResult.obj(completedRoot.data, rootParameters, completedRoot.work)

            val initial = ExecuteRootSelectionSet.buildExecutionResults(rootParameters, Result.success(root), emptyList()) as IncrementalExecutionResult

            assertEquals(mapOf("objects" to listOf(null, mapOf("child" to emptyMap<String, Any?>(), "value" to 1))), initial.getData<Map<String, Any?>?>())
            assertEquals(listOf(path), initial.pending.map { it.path })
            val updates = initial.updates.toList()
            assertEquals(mapOf("value" to 2), (updates.flatMap { it.incremental }.single() as IncrementalEntry.Object).data)
            assertEquals(listOf("0"), updates.flatMap { it.completed }.map { it.id })
            assertFalse(updates.last().hasNext)
        }

    @Test
    fun `null or failed root completion produces a synchronous result with errors`() =
        runTest {
            val error = error("root completion failed")
            val completions = listOf(
                Result.success(FieldCompletionResult.NullResult(false)),
                Result.failure<FieldCompletionResult>(FieldCompletionException(listOf(error))),
            )

            for (completion in completions) {
                val result = ExecuteRootSelectionSet.buildExecutionResults(parameters, completion, listOf(error))

                assertTrue(result.isDataPresent)
                assertNull(result.getData<Map<String, Any?>?>())
                assertEquals(listOf(error), result.errors)
                assertTrue(result.toSpecification().containsKey("data"))
            }
        }

    @Test
    fun `non-null failure reaching the root produces present null data`() {
        val root = ExecutionStepInfo.newExecutionStepInfo().type(schema.schema.queryType).path(ResultPath.rootPath()).build()
        val field = schema.schema.queryType.getFieldDefinition("value")
        val step = ExecutionStepInfo.newExecutionStepInfo()
            .type(GraphQLNonNull.nonNull(field.type))
            .fieldDefinition(field)
            .parentInfo(root)
            .path(root.path.segment("value"))
            .build()
        val exception = NonNullableFieldWasNullException(step, step.path)
        val errors = listOf(NonNullableFieldWasNullError(exception))

        val result = ViaductExecutionStrategy.buildExecutionResult(Result.failure(exception), errors)

        assertTrue(result.isDataPresent)
        assertNull(result.getData<Map<String, Any?>?>())
        assertEquals(errors, result.errors)
    }

    private fun error(message: String): GraphQLError = GraphQLError.newError().message(message).build()
}
