package viaduct.engine.runtime.execution

import graphql.schema.DataFetchingEnvironment
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductDataFetchingEnvironment
import viaduct.engine.runtime.EngineExecutionContextExtensions.dataFetchingEnvironment

/**
 * Implementation of ViaductDataFetchingEnvironment that delegates DFE methods to GraphQL-Java
 * while exposing Viaduct's EngineExecutionContext.
 *
 * The [engineExecutionContext] passed here is typically created via [EngineExecutionContext.copy],
 * which preserves the correct [EngineExecutionContext.executionHandle]. The `init` block completes
 * the bidirectional link by setting the EEC's
 * [dataFetchingEnvironment][EngineExecutionContext.dataFetchingEnvironment] to this instance.
 */
class ViaductDataFetchingEnvironmentImpl(
    private val delegate: DataFetchingEnvironment,
    override val engineExecutionContext: EngineExecutionContext
) : ViaductDataFetchingEnvironment, DataFetchingEnvironment by delegate {
    init {
        // Complete the bidirectional link: EEC -> DFE (the reverse, DFE -> EEC, is the constructor param)
        engineExecutionContext.dataFetchingEnvironment = this
    }
}
