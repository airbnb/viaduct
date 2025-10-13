package viaduct.engine.runtime.execution

import graphql.schema.DataFetchingEnvironment
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductDataFetchingEnvironment

/**
 * Implementation of ViaductDataFetchingEnvironment that delegates DFE methods to GraphQL-Java
 * while exposing Viaduct's EngineExecutionContext.
 */
class ViaductDataFetchingEnvironmentImpl(
    private val delegate: DataFetchingEnvironment,
    override val engineExecutionContext: EngineExecutionContext
) : ViaductDataFetchingEnvironment, DataFetchingEnvironment by delegate
