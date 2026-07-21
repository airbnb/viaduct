package viaduct.engine.runtime.execution

import viaduct.engine.api.Coordinate
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockTenantModuleDSL
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeBuilder
import viaduct.engine.runtime.mat.build

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
