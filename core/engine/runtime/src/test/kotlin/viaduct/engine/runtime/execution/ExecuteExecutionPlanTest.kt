package viaduct.engine.runtime.execution

import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.NonNullableFieldWasNullException
import graphql.execution.ResultPath
import graphql.execution.SimpleDataFetcherExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.dfe.engineExecutionContext
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.mocks.MockFlagManager

class ExecuteExecutionPlanTest {
    private val completer = FieldCompleter(SimpleDataFetcherExceptionHandler(), false)

    @Test
    fun `object completion registers newly collected defers at the response path`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ aliasedObj: obj { ... @defer(label: \"A\") { broken: a } } }",
            )
            val usage = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).newDeferUsages.single()

            val deferMap = completeWithFieldFailure(ctx, "broken")

            val group = deferMap.getValue(usage)
            assertEquals(ResultPath.rootPath().segment("aliasedObj"), group.path)
            assertEquals("A", group.label)
            assertEquals(null, group.parent)
            assertTrue(ctx.deferMap.isEmpty())
        }

    @Test
    fun `object completion links new groups to inherited parents`() =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ ... @defer(label: \"outer\") { obj { ... @defer(label: \"inner\") { broken: a } } } }",
            )
            val parentUsage = checkNotNull(base.field).occurrences.single().deferUsage!!
            val parent = DeferDeliveryGroup(ResultPath.rootPath(), "outer", null)
            val ctx = base.copy(deferMap = mapOf(parentUsage to parent))
            val childUsage = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).newDeferUsages.single()

            val deferMap = completeWithFieldFailure(ctx, "broken")

            assertSame(parent, deferMap.getValue(parentUsage))
            assertSame(parent, deferMap.getValue(childUsage).parent)
            assertEquals(mapOf(parentUsage to parent), ctx.deferMap)
        }

    @Test
    fun `object completion preserves the defer map when incremental execution is disabled`() =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ ... @defer(label: \"outer\") { obj { ... @defer(label: \"inner\") { broken: a } } } }",
            )
            val parentUsage = checkNotNull(base.field).occurrences.single().deferUsage!!
            val inheritedDeferMap = mapOf(parentUsage to DeferDeliveryGroup(ResultPath.rootPath(), "outer", null))
            val ctx = base.copy(
                _engineExecutionContext = ContextMocks(
                    myFullSchema = base.engineExecutionContext.fullSchema,
                    myFlagManager = MockFlagManager.Disabled,
                ).engineExecutionContext,
                deferMap = inheritedDeferMap,
            )

            val deferMap = completeWithFieldFailure(ctx, "broken")

            assertSame(inheritedDeferMap, deferMap)
            assertSame(inheritedDeferMap, ctx.deferMap)
        }

    @Test
    fun `execution plans without deferred fields complete synchronously including empty plans`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { first: a } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromValue(resolved(1)))

            for (selected in listOf(emptyMap(), fields)) {
                val result = completer.executeExecutionPlan(ctx, selected, BuildExecutionPlan(selected))

                assertTrue(result is Value.Sync)
                assertEquals(if (selected.isEmpty()) emptyMap() else mapOf("first" to 1), result.getCompleted())
            }
        }

    @Test
    fun `execution plans wait for deferred fields and restore collection order across overlapping groups`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int, c: Int }",
                coordinate = "Query" to "obj",
                query = """
                    {
                        obj {
                            ... @defer(label: "A") { first: a shared: c }
                            b
                            ... @defer(label: "B") { shared: c }
                        }
                    }
                """.trimIndent(),
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            val deferred = CompletableDeferred<FieldResolutionResult>()
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromDeferred(deferred))
            setRawFieldValue(ctx, fields.getValue("b"), Value.fromValue(resolved(2)))
            setRawFieldValue(ctx, fields.getValue("shared"), Value.fromValue(resolved(3)))

            val completion = completer.executeExecutionPlan(ctx, fields, BuildExecutionPlan(fields))
            assertFalse(completion.asDeferred().isCompleted)
            deferred.complete(resolved(1))
            val result = completion.await()

            assertEquals(mapOf("first" to 1, "shared" to 3, "b" to 2), result)
            assertEquals(listOf("first", "shared", "b"), result.keys.toList())
        }

    @Test
    fun `a deferred non-null failure still fails the entire plan`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, required: Int! }",
                coordinate = "Query" to "obj",
                query = "{ obj { a ... @defer { broken: required } } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("a"), Value.fromValue(resolved(1)))
            val deferred = CompletableDeferred<FieldResolutionResult>()
            setRawFieldValue(ctx, fields.getValue("broken"), Value.fromDeferred(deferred))

            val completion = completer.executeExecutionPlan(ctx, fields, BuildExecutionPlan(fields))
            deferred.complete(resolved(null))

            assertThrows<NonNullableFieldWasNullException> { completion.await() }
            assertEquals(listOf(listOf("obj", "broken")), ctx.errorAccumulator.toList().map { it.path })
        }

    /** Adapted from graphql-js src/execution/__tests__/defer-test.ts (7fdd84e), asserting eager results. */
    @Nested
    inner class GraphQLJsTests {
        @Test
        fun `Can defer fragments containing scalar types`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { id: ID, name: String }",
                    coordinate = "Query" to "hero",
                    query = """
                        query HeroNameQuery {
                            hero {
                                id
                                ...NameFragment @defer
                            }
                        }
                        fragment NameFragment on Hero { name }
                    """.trimIndent(),
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
                setRawFieldValue(ctx, fields.getValue("id"), Value.fromValue(resolved("1")))
                setRawFieldValue(ctx, fields.getValue("name"), Value.fromValue(resolved("Luke")))
                val plan = BuildExecutionPlan(fields)
                assertEquals(1, plan.newCollectedFieldsMaps.size)

                val result = completer.executeExecutionPlan(ctx, fields, plan).await()

                assertEquals(mapOf("id" to "1", "name" to "Luke"), result)
            }

        @Test
        fun `Does not emit empty defer fragments`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { name: String }",
                    coordinate = "Query" to "hero",
                    query = "{ hero { ... @defer { name @skip(if: true) } } }",
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap

                val result = completer.executeExecutionPlan(ctx, fields, BuildExecutionPlan(fields))

                assertTrue(result is Value.Sync)
                assertEquals(emptyMap<String, Any?>(), result.getCompleted())
            }

        @Test
        fun `Emits children of empty defer fragments`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { name: String }",
                    coordinate = "Query" to "hero",
                    query = "{ hero { ... @defer { ... @defer { name } } } }",
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
                setRawFieldValue(ctx, fields.getValue("name"), Value.fromValue(resolved("Luke")))
                val plan = BuildExecutionPlan(fields)
                assertTrue(plan.collectedFieldsMap.isEmpty())
                assertEquals(1, plan.newCollectedFieldsMaps.size)

                val result = completer.executeExecutionPlan(ctx, fields, plan).await()

                assertEquals(mapOf("name" to "Luke"), result)
            }

        @Test
        fun `Can separately emit defer fragments with different labels with varying fields`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { id: ID, name: String }",
                    coordinate = "Query" to "hero",
                    query = """
                        {
                            hero {
                                ... @defer(label: "DeferID") { id }
                                ... @defer(label: "DeferName") { name }
                            }
                        }
                    """.trimIndent(),
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
                val id = CompletableDeferred<FieldResolutionResult>()
                val name = CompletableDeferred<FieldResolutionResult>()
                setRawFieldValue(ctx, fields.getValue("id"), Value.fromDeferred(id))
                setRawFieldValue(ctx, fields.getValue("name"), Value.fromDeferred(name))
                val plan = BuildExecutionPlan(fields)
                assertTrue(plan.collectedFieldsMap.isEmpty())
                assertEquals(2, plan.newCollectedFieldsMaps.size)

                val completion = completer.executeExecutionPlan(ctx, fields, plan)
                name.complete(resolved("Luke"))
                assertFalse(completion.asDeferred().isCompleted)
                id.complete(resolved("1"))
                val result = completion.await()

                assertEquals(mapOf("id" to "1", "name" to "Luke"), result)
                assertEquals(listOf("id", "name"), result.keys.toList())
            }

        @Test
        fun `Handles errors thrown in deferred fragments`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { id: ID, name: String }",
                    coordinate = "Query" to "hero",
                    query = """
                        {
                            hero {
                                id
                                ...NameFragment @defer
                            }
                        }
                        fragment NameFragment on Hero { name }
                    """.trimIndent(),
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
                setRawFieldValue(ctx, fields.getValue("id"), Value.fromValue(resolved("1")))
                setRawFieldValue(ctx, fields.getValue("name"), Value.fromThrowable(IllegalStateException("bad")))

                val result = completer.executeExecutionPlan(ctx, fields, BuildExecutionPlan(fields)).await()

                assertEquals(mapOf("id" to "1", "name" to null), result)
                val error = ctx.errorAccumulator.toList().single()
                assertEquals(listOf("hero", "name"), error.path)
                assertTrue(error.message.contains("bad"))
            }

        @Test
        fun `Handles non-nullable errors thrown outside deferred fragments`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { id: ID, nonNullName: String! }",
                    coordinate = "Query" to "hero",
                    query = """
                        {
                            hero {
                                nonNullName
                                ...NameFragment @defer
                            }
                        }
                        fragment NameFragment on Hero { id }
                    """.trimIndent(),
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
                val deferred = CompletableDeferred<FieldResolutionResult>()
                setRawFieldValue(ctx, fields.getValue("nonNullName"), Value.fromValue(resolved(null)))
                setRawFieldValue(ctx, fields.getValue("id"), Value.fromDeferred(deferred))

                val completion = completer.executeExecutionPlan(ctx, fields, BuildExecutionPlan(fields))

                assertTrue(completion.asDeferred().isCompleted)
                assertThrows<NonNullableFieldWasNullException> { completion.await() }
                assertEquals(listOf(listOf("hero", "nonNullName")), ctx.errorAccumulator.toList().map { it.path })
                deferred.complete(resolved("1"))
            }
    }

    private suspend fun completeWithFieldFailure(
        ctx: ExecutionParameters,
        responseKey: String,
    ): DeferMap {
        val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
        setRawFieldValue(ctx, fields.getValue(responseKey), Value.fromThrowable(IllegalStateException("failed")))
        ctx.currentObjectEngineResult.fieldResolutionState.complete(Unit)
        lateinit var deferMap: DeferMap
        val handler = DataFetcherExceptionHandler { parameters ->
            val executionParameters = parameters.dataFetchingEnvironment.engineExecutionContext.executionHandle as ExecutionParameters
            deferMap = executionParameters.deferMap
            SimpleDataFetcherExceptionHandler().handleException(parameters)
        }

        val result = FieldCompleter(handler, false).completeObject(ctx).await()

        assertEquals(mapOf(responseKey to null), result.value)
        return deferMap
    }

    private fun resolved(value: Any?) = FieldResolutionResult(value, emptyList(), CompositeLocalContext.empty, emptyMap(), value)
}
