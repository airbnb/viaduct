package viaduct.service.runtime

import viaduct.engine.api.ExecutionInput as EngineExecutionInput
import viaduct.service.api.ExecutionInput

internal fun ExecutionInput.toEngineExecutionInput(): EngineExecutionInput =
    EngineExecutionInput(
        operationText,
        operationName,
        operationId,
        variables,
        executionId,
        requestContext
    )
