@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.ErrorClassification
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQLContext
import graphql.GraphQLError
import graphql.execution.ExecutionStepInfo
import graphql.execution.MergedField
import graphql.execution.ResultPath
import graphql.language.AstPrinter
import graphql.language.Node
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.TypeResolver
import io.kotest.property.Arb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.failProperty
import viaduct.arbitrary.common.minViolation
import viaduct.arbitrary.common.randomSource
import viaduct.arbitrary.graphql.ExecutionInputComparator
import viaduct.arbitrary.graphql.arbRuntimeWiring
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createExecutionInput
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createGJGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createRuntimeWiring
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createViaductGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.engine.runtime.execution.RecordingInstrumentation.RecordingInstrumentationContext

/**
 * A [Conformer] is a test fixture to help write and run tests that check
 * that the viaduct modern engine conforms to the behavior of the graphql-java
 * engine.
 *
 * A [Conformer] is constructed from an SDL string. By default, the schema
 * created from the SDL will be backed by a generated wiring that returns
 * arbitrary but deterministic data. If a test case requires more control
 * over the behavior of resolvers, a handwritten map of resolvers can
 * also be provided.
 *
 * Example:
 * ```kotlin
 * // create a Conformer for the provided sdl using Arb-based wiring
 * Conformer("type Query { x:Int }") {
 *   // check conformance for a static query
 *   check("{x}")
 *
 *   // check conformance for arbitrary queries:
 *   Arb.viaductExecutionInput(schema).checkAll()
 * }
 * ```
 */
@ExperimentalCoroutinesApi
internal class Conformer private constructor(
    val schema: ViaductSchema,
    private val fn: suspend Conformer.() -> Unit = {}
) {
    /** Create a [Conformer] backed by the provided resolvers */
    constructor(
        sdl: String,
        resolvers: Map<String, Map<String, DataFetcher<Any?>>>,
        typeResolvers: Map<String, TypeResolver> = emptyMap(),
        fn: suspend Conformer.() -> Unit = {}
    ) : this(createSchema(sdl, createRuntimeWiring(resolvers, typeResolvers)), fn)

    /**
     * Create a [Conformer] backed by deterministic [Arb]-based wiring
     *
     * See [arbRuntimeWiring] for configuration notes.
     */
    constructor(
        sdl: String,
        cfg: Config = Config.default,
        seed: Long = randomSource().seed,
        fn: suspend Conformer.() -> Unit = {}
    ) : this(createSchema(sdl, arbRuntimeWiring(sdl, seed, cfg)), fn)

    val modernRecorder = RecordingInstrumentation()
    val gjRecorder = RecordingInstrumentation()
    val sharedDocumentCache = DocumentCache()

    val modernGraphQL = createViaductGraphQL(schema, preparsedDocumentProvider = sharedDocumentCache, instrumentations = listOf(modernRecorder))
    val gjGraphQL = createGJGraphQL(schema, preparsedDocumentProvider = sharedDocumentCache, instrumentations = listOf(gjRecorder.asGJInstrumentation()))

    init {
        runBlocking {
            fn(this@Conformer)
        }
    }

    @JvmName("checkAllArb")
    fun checkAll(
        arb: Arb<ExecutionInput>,
        iter: Int = 1_000,
        checkNoModernErrors: Boolean = true,
        checkResultsEqual: Boolean = true,
        checkFetchesEqual: Boolean = true,
        checkInstrumentationsEqual: Boolean = true,
        extraChecks: CheckResult = CheckResult.Pass
    ) {
        val checkResult = mkCheckResult(
            checkNoModernErrors = checkNoModernErrors,
            checkResultsEqual = checkResultsEqual,
            checkFetchesEqual = checkFetchesEqual,
            checkInstrumentationsEqual = checkInstrumentationsEqual,
            extraChecks = extraChecks
        )
        val failure = arb.minViolation(ExecutionInputComparator, iter) {
            tryCheck(it, checkResult).isSuccess
        }
        failure?.let {
            failProperty(it.dump(), tryCheck(it, checkResult).exceptionOrNull())
        }
    }

    /**
     * Check conformance for the first [iter] items in this [Arb].
     * Any ExecutionInput that fails a conformance check will be collected
     * and simplified, and the simplest input will be thrown as an assertion error.
     *
     * For more on input simplification, see [Arb.minViolation]
     */
    fun Arb<ExecutionInput>.checkAll(
        iter: Int = 1_000,
        checkNoModernErrors: Boolean = true,
        checkResultsEqual: Boolean = true,
        checkFetchesEqual: Boolean = true,
        checkInstrumentationsEqual: Boolean = false,
        extraChecks: CheckResult = CheckResult.Pass
    ) = checkAll(this, iter, checkNoModernErrors, checkResultsEqual, checkFetchesEqual, checkInstrumentationsEqual, extraChecks)

    /**
     * Check conformance for a single query.
     * @param checkNoModernErrors assert that the modern response contains no graphql errors
     * @param checkResultsEqual assert that the graphql-java and modern engine returned the same responses
     *
     */
    fun check(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        checkNoModernErrors: Boolean = true,
        checkResultsEqual: Boolean = true,
        checkFetchesEqual: Boolean = true,
        checkInstrumentationsEqual: Boolean = false,
        graphQLContext: GraphQLContext = GraphQLContext.getDefault(),
        extraChecks: CheckResult = CheckResult.Pass
    ) {
        val input = createExecutionInput(schema, query, variables, context = graphQLContext)
        val checkResult = mkCheckResult(
            checkNoModernErrors = checkNoModernErrors,
            checkResultsEqual = checkResultsEqual,
            checkFetchesEqual = checkFetchesEqual,
            checkInstrumentationsEqual = checkInstrumentationsEqual,
            extraChecks = extraChecks
        )
        tryCheck(input, checkResult).getOrThrow()
    }

    private fun mkCheckResult(
        checkNoModernErrors: Boolean,
        checkResultsEqual: Boolean,
        checkFetchesEqual: Boolean,
        checkInstrumentationsEqual: Boolean,
        extraChecks: CheckResult
    ): CheckResult =
        CheckResult.all(
            if (checkNoModernErrors) CheckResult.NoModernErrors else CheckResult.Pass,
            if (checkFetchesEqual) CheckResult.FetchesEqual else CheckResult.Pass,
            if (checkResultsEqual) CheckResult.ResultsEqual else CheckResult.Pass,
            if (checkInstrumentationsEqual) CheckResult.InstrumentationsEqual else CheckResult.Pass,
            extraChecks
        )

    private fun tryCheck(
        input: ExecutionInput,
        checkResult: CheckResult
    ): Result<Unit> =
        runExecutionTest {
            gjRecorder.reset()
            modernRecorder.reset()

            val modernResult = modernGraphQL.executeAsync(input).await()
            val gjResult = gjGraphQL.executeAsync(input).await()

            runCatching {
                checkResult(
                    CheckCtx(gjResult, gjRecorder),
                    CheckCtx(modernResult, modernRecorder)
                )
            }
        }
}

data class CheckCtx(val executionResult: ExecutionResult, val recordingInstrumentation: RecordingInstrumentation)

/**
 * Functional interface for conformance checks.
 * The semantics of the arguments match the semantics of jupiter assertions and
 * are `(expected, actual)`
 *
 * Example:
 * ```kotlin
 *    val myChecker = CheckResult { expected, actual ->
 *      assertEquals(expected.executionResult.toSpecification(), actual.executionResult.toSpecification())
 *    }
 * ```
 */
fun interface CheckResult : (CheckCtx, CheckCtx) -> Unit {
    companion object {
        /** make no assertions */
        val Pass: CheckResult = CheckResult { _, _ -> }

        /** assert that the actual ExecutionResult contains no errors */
        val NoModernErrors: CheckResult = CheckResult { (_), (act) ->
            assertEquals(emptyList<Any>(), act.errors)
        }

        val ResultsEqual: CheckResult = CheckResult { (exp), (act) ->
            // experimental strikt matcher
            // perf note: these strikt checkers use a function reference syntax, rather than the more common lambda syntax
            //   fun ref:    get(GraphQLError::getMessage).isEqualTo(expError.message)
            //   lambda:     get { message } isEqualTo(expError.message)
            // The perf difference is significant: the lambda syntax is 3-4x slower than the function ref style, which
            // significantly slows down property tests
            expectThat(act) {
                with(ExecutionResult::getErrors) {
                    hasSize(exp.errors.size)

                    // errors are recorded in the order that they were generated, which may be non-deterministic
                    // when executing a selection set in parallel.
                    // Normalize the error ordering before comparing
                    subject
                        .sortedWith(GraphQLErrorComparator)
                        .zip(exp.errors.sortedWith(GraphQLErrorComparator))
                        .forEach { (actError, expError) ->
                            expectThat(actError) {
                                get(GraphQLError::getMessage).isEqualTo(expError.message)
                                get(GraphQLError::getPath).isEqualTo(expError.path)
                                get(GraphQLError::getLocations).isEqualTo(expError.locations)
                                get(GraphQLError::getExtensions).isEqualTo(expError.extensions)
                                // errors can return an instance of the ErrorClassification interface, which is usually (but not always) a value
                                // of the ErrorType enumeration.
                                // This will cause aa naive check `errorType.isEqualTo(expError.errorType)` to fail
                                // when dealing with non-ErrorType classifications.
                                // To work around this, compare their string values instead of a direct isEqualTo check
                                with(GraphQLError::getErrorType) {
                                    get(ErrorClassification::toString).isEqualTo(expError.errorType.toString())
                                }
                            }
                        }
                }
            }

            val expData = exp.getData<Map<String, Any?>>()
            val actData = act.getData<Map<String, Any?>>()
            assertEquals(expData, actData)
            assertEqualsAndOrdered(ResultPath.rootPath(), expData, actData)
        }

        val FetchesEqual: CheckResult = CheckResult { (_, expRecorder), (_, actRecorder) ->
            assertDataFetchesEqual(expRecorder.dataFetchingEnvironments.toList(), actRecorder.dataFetchingEnvironments.toList())
        }

        val InstrumentationsEqual: CheckResult = CheckResult { (_, expRecorder), (_, actRecorder) ->
            assertInstrumentationEventsEqual(expRecorder, actRecorder)
        }

        private val objectMapper = ObjectMapper()

        // define a deterministic ordering for GraphQLErrors
        private val GraphQLErrorComparator = Comparator<GraphQLError> { a, b ->
            // try to do a cheap comparison first
            when (val comparison = a.message.compareTo(b.message)) {
                0 -> {
                    // cheap comparison didn't provide an ordering.
                    // fallback to a more expensive comparison
                    val astr = objectMapper.writeValueAsString(a.toSpecification())
                    val bstr = objectMapper.writeValueAsString(b.toSpecification())
                    astr.compareTo(bstr)
                }
                else -> comparison
            }
        }

        /** create a CheckResult that succeeds when all the provided checks succeed */
        fun all(vararg checks: CheckResult): CheckResult =
            checks.filter { it != Pass }.let { checksFiltered ->
                CheckResult { exp, act ->
                    checksFiltered.forEach { it(exp, act) }
                }
            }
    }
}

/**
 * Recursively assert that the actual result is in the same key order as the expected result.
 * A failure here is indicative of incorrect field merging.
 */
@Suppress("UNCHECKED_CAST")
private fun assertEqualsAndOrdered(
    path: ResultPath,
    exp: Any?,
    act: Any?
) {
    assertTrue((exp == null) == (act == null)) {
        "Null values mismatch at $path, expecting $exp but found $act"
    }
    when (exp) {
        is Map<*, *> -> {
            exp as Map<String, Any?>
            act as Map<String, Any?>

            exp.toList().zip(act.toList()).forEachIndexed { i, (expEntry, actEntry) ->
                val (expKey, expValue) = expEntry
                val (actKey, actValue) = actEntry
                assertEquals(expKey, actKey) {
                    "Incorrectly ordered results at $path, element $i: expected $expKey but found $actKey"
                }
                assertEqualsAndOrdered(path.segment(expKey), expValue, actValue)
            }
        }

        is List<*> -> {
            act as List<*>
            assertEquals(exp.size, act.size)
            exp.zip(act).forEachIndexed { i, (expItem, actItem) ->
                assertEqualsAndOrdered(path.segment(i), expItem, actItem)
            }
        }
        else -> {}
    }
}

private fun assertDataFetchesEqual(
    exp: List<DataFetchingEnvironment>,
    act: List<DataFetchingEnvironment>
) {
    val expGroups = exp.groupBy { it.executionStepInfo.path }
    val actGroups = act.groupBy { it.executionStepInfo.path }

    assertEquals(exp.size, act.size) {
        """
            | Expected fetches:
            | ${expGroups.map { (k, v) -> " - $k: ${v.size}" }.joinToString("\n| ")}
            |
            | Actual fetches:
            | ${actGroups.map { (k, v) -> " - $k: ${v.size}" }.joinToString("\n| ")}
        """.trimMargin()
    }

    assertEquals(expGroups.keys, actGroups.keys) {
        """
            | Expected fetch keys:
            | ${expGroups.keys.joinToString("\n| ")}
            |
            | Actual fetch keys:
            | ${actGroups.keys.joinToString("\n| ")}
        """.trimMargin()
    }

    // sanity check that all result paths had only 1 fetch
    expGroups.filterValues { it.size > 1 }
        .keys
        .let { pathsWithMultipleFetches ->
            check(pathsWithMultipleFetches.isEmpty()) {
                """
                    | sanity check failed; expected result contains paths with multiple fetches:
                    | ${pathsWithMultipleFetches.joinToString("\n| ") { " - $it" }}
                """.trimMargin()
            }
        }

    exp.forEach { expEnv ->
        val resultPath = expEnv.executionStepInfo.path
        val actEnv = actGroups[resultPath]!!.first()

        assertMergedFieldsEqual(resultPath, expEnv.mergedField, actEnv.mergedField)
        assertExecutionStepInfoEqual(expEnv.executionStepInfo, actEnv.executionStepInfo)
        assertEquals(expEnv.operationDefinition.name, actEnv.operationDefinition.name)
        assertEquals(expEnv.variables, actEnv.variables)
        assertEquals(expEnv.fragmentsByName.keys, actEnv.fragmentsByName.keys)
        assertEquals(expEnv.locale, actEnv.locale)
    }
}

internal fun assertMergedFieldsEqual(
    resultPath: ResultPath,
    exp: MergedField,
    act: MergedField
) {
    assertEquals(exp.fields.size, act.fields.size) {
        """
            | Merged field mismatch at $resultPath
            | Expected merged fields:
            | ${exp.fields.joinToString("\n| ") { " - $it" }}
            |
            | Actual merged fields:
            | ${act.fields.joinToString("\n| ") { " - $it" }}
        """.trimMargin()
    }

    exp.fields.zip(act.fields).forEachIndexed { i, (ef, af) ->
        assertEquals(ef.name, af.name) {
            """
                    $resultPath @ field [$i]: expected field ${ef.name} but found ${af.name}
            """.trimIndent()
        }

        assertTrue((ef.selectionSet == null) == (af.selectionSet == null)) {
            """
                    $resultPath @ field [$i]: subselection type mismatch
            """.trimIndent()
        }
        if (ef.selectionSet != null && af.selectionSet != null) {
            assertNodesEqual(ef.selectionSet, af.selectionSet)
        }
    }
}

internal fun assertExecutionStepInfoEqual(
    exp: ExecutionStepInfo,
    act: ExecutionStepInfo
) {
    assertEquals(exp.field == null, act.field == null)
    if (exp.field != null) {
        assertMergedFieldsEqual(exp.path, exp.field, act.field)

        // result key is derived from field
        assertEquals(exp.resultKey, act.resultKey)
    }
    assertEquals(exp.path, act.path)
    assertEquals(exp.arguments, act.arguments)
    assertEquals(exp.hasParent(), act.hasParent())
    if (exp.hasParent()) {
        assertExecutionStepInfoEqual(exp.parent, act.parent)
    }
    assertTypesEqual(exp.type, act.type)
    assertEquals(exp.objectType == null, act.objectType == null)
    if (exp.objectType != null) {
        assertTypesEqual(exp.objectType, act.objectType)
    }
    assertTypesEqual(exp.unwrappedNonNullType, act.unwrappedNonNullType)
    assertSame(exp.fieldDefinition, act.fieldDefinition)
    assertEquals(exp.isListType, act.isListType)
    assertEquals(exp.isNonNullType, act.isNonNullType)
}

internal fun assertTypesEqual(
    exp: GraphQLType,
    act: GraphQLType
) {
    when (exp) {
        is GraphQLList -> {
            assertTrue(act is GraphQLList)
            assertTypesEqual(exp.wrappedType, GraphQLTypeUtil.unwrapOne(act))
        }
        is GraphQLNonNull -> {
            assertTrue(act is GraphQLNonNull)
            assertTypesEqual(exp.wrappedType, GraphQLTypeUtil.unwrapOne(act))
        }
        else -> assertSame(exp, act)
    }
}

internal fun assertNodesEqual(
    exp: Node<*>,
    act: Node<*>
) {
    assertEquals(AstPrinter.printAst(exp), AstPrinter.printAst(act))
}

/**
 * Assert that the instrumentation hooks that are common to graphql-java and Viaduct
 * have equal call patterns.
 *
 * This does not check viaduct-specific instrumentation hooks, like `beginFetchObject`
 * and `beginCompleteObject`
 */
internal fun assertInstrumentationEventsEqual(
    exp: RecordingInstrumentation,
    act: RecordingInstrumentation
) {
    assertEquals(exp.fieldExecutionContexts.size, act.fieldExecutionContexts.size) {
        "fieldExecutionContext sizes are not equal"
    }
    exp.fieldExecutionContexts.zip(act.fieldExecutionContexts).forEach { (exp, act) ->
        assertInstrumentationContextsEqual(exp, act)
    }

    assertEquals(exp.fieldFetchingContexts.size, act.fieldFetchingContexts.size) {
        "fieldFetchingContext sizes are not equal"
    }
    exp.fieldFetchingContexts.zip(act.fieldFetchingContexts).forEach { (exp, act) ->
        assertInstrumentationContextsEqual(exp, act)
    }

    assertEquals(exp.fieldCompletionContexts.size, act.fieldCompletionContexts.size) {
        "fieldCompletionContext sizes are not equal"
    }
    exp.fieldCompletionContexts.zip(act.fieldCompletionContexts).forEach { (exp, act) ->
        assertInstrumentationContextsEqual(exp, act)
    }

    assertEquals(exp.fieldListCompletionContexts.size, act.fieldListCompletionContexts.size) {
        "fieldListCompletionContext sizes are not equal"
    }
    exp.fieldListCompletionContexts.zip(act.fieldListCompletionContexts).forEach { (exp, act) ->
        assertInstrumentationContextsEqual(exp, act, relaxed = true)
    }
}

internal fun <T> assertInstrumentationContextsEqual(
    exp: RecordingInstrumentationContext<T>,
    act: RecordingInstrumentationContext<T>,
    relaxed: Boolean = false,
) {
    /**
     * The onDispatched hook in particular has some strange behavior in graphql-java:
     *   - For the beginFieldList completion hook in particular, it is only called in specific cases where a CompletableFuture is returned in the graphql-java ExecutionStrategy.
     *     Graphql-java link is [here](https://github.com/graphql-java/graphql-java/blob/23f078da4f181751f972d6ce071093fa8e661df7/src/main/java/graphql/execution/ExecutionStrategy.java#L813).
     *     Conversely, for ViaductExecutionStrategy, the onDispatched hook is [always called](https://git.musta.ch/airbnb/treehouse/blob/466f6078ff9e130bf96c4b4eb1250625b199ea6f/projects/viaduct/oss/engine/runtime/src/main/kotlin/viaduct/engine/runtime/execution/FieldCompleter.kt#L335).
     *     As such, when checking for 'correctness' of the onDispatched hook, we have a mode for relaxing the semantics, where we only check for equality if the
     *     hook is `true` for the expected case.
     */
    if (relaxed) {
        if (exp.onDispatchedCalled.get()) {
            assertEquals(exp.onDispatchedCalled.get(), act.onDispatchedCalled.get())
        }
    } else {
        assertEquals(exp.onDispatchedCalled.get(), act.onDispatchedCalled.get())
    }

    assertEquals(exp.onCompletedCalled.get(), act.onCompletedCalled.get())
    // TODO: viaduct does not call the completedValue hook with the correct value
    //   https://app.asana.com/1/150975571430/project/1208357307661305/task/1210895182028900
    // assertEquals(exp.completedValue, act.completedValue)
    assertEquals(exp.completedException, act.completedException)
}
