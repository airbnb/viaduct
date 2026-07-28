@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.schema.GraphQLObjectType
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.arbitrary.graphql.dump
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.FeatureTest
import viaduct.engine.api.mocks.MockTenantModuleDSL
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeBuilder
import viaduct.engine.runtime.mat.build
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.api.Viaduct

internal fun Viaduct.runQueryWithTimeout(
    input: ExecutionInput,
    timeout: kotlin.time.Duration = 2.seconds,
): ExecutionResult =
    runBlocking {
        withTimeout(timeout) {
            executeAsync(input).await()
        }
    }

internal fun FeatureTest.runQueryWithTimeout(
    query: String,
    variables: Map<String, Any?> = emptyMap(),
    timeout: kotlin.time.Duration = 2.seconds,
): graphql.ExecutionResult {
    val input =
        viaduct.engine.api.ExecutionInput(
            operationText = query,
            variables = variables,
            requestContext = Any(),
        )

    return runBlocking {
        withTimeout(timeout) {
            DefaultCoroutineInterop.enterThreadLocalCoroutineContext(coroutineContext) {
                engine.execute(input)
            }.await()
        }
    }
}

object ViaductAndInputComparator : Comparator<Pair<Viaduct, ExecutionInput>> {
    override fun compare(
        o1: Pair<Viaduct, ExecutionInput>,
        o2: Pair<Viaduct, ExecutionInput>,
    ): Int {
        val len1 = o1.first.dump().length + o1.second.operationText.length
        val len2 = o2.first.dump().length + o2.second.operationText.length
        return len1.compareTo(len2)
    }
}

fun dump(
    viaduct: Viaduct,
    input: ExecutionInput,
    result: Result<ExecutionResult>,
): String =
    buildString {
        appendLine()
        appendLine("== VIADUCT ==")
        appendLine(viaduct.dump())
        appendLine()
        appendLine("== INPUT ==")
        appendLine(input.toString())
        appendLine()
        appendLine("== RESULT ==")
        appendLine(result.map { it.toSpecification() })
    }

fun MockTenantModuleDSL<*>.objectType(name: String): GraphQLObjectType = schema.schema.getObjectType(name)!!

fun MockTenantModuleDSL<*>.createEngineObjectData(
    name: String,
    vararg pairs: Pair<String, Any?>,
): EngineObjectData = createEngineObjectData(objectType(name), pairs.toMap())

fun MockTenantModuleDSL<*>.createEngineObjectData(
    name: String,
    data: Map<String, Any?>,
): EngineObjectData = createEngineObjectData(objectType(name), data)

/**
 * Create a real [ExecutionParameters] representing the ExecutionParameters
 * used to execute the resolver at [coordinate] when executing [query].
 */
internal fun mkExecutionParameters(
    schemaSDL: String,
    coordinate: Coordinate,
    query: String,
    configure: MockTenantModuleDSL<Unit>.() -> Unit = {},
): ExecutionParameters {
    lateinit var parameters: ExecutionParameters

    EngineTestModule(schemaSDL) {
        configure()
        field(coordinate) {
            valueFromContext { context ->
                parameters = context.executionHandle as ExecutionParameters
                null
            }
        }
    }.runFeatureTest {
        runQuery(query)
    }

    return parameters
}

/** Build a [KeyTree] using the schema in an [ExecutionParameters]. */
internal fun KeyTree.Companion.build(
    parameters: ExecutionParameters,
    build: KeyTreeBuilder.() -> Unit = {},
): KeyTree = build(parameters.engineExecutionContext.activeSchema, build)
