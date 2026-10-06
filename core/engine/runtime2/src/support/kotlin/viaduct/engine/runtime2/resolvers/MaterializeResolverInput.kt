package viaduct.engine.runtime2.resolvers

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.CycleTask
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.materializeResult

/**
 * Materializes a Resolver01-23 runtime object or declared Query-fragment input.
 *
 * Delegates to [materializeResult]: selected cells and value promises must be installed, while
 * values and selection bindings may still be pending. The caller's [operation] and [cycleChecker]
 * are passed through unchanged. Claimed field- and type-checker slots are awaited, combined, and
 * enforced before the raw value is awaited; unclaimed slots default open, and an applicable denial
 * short-circuits the raw read.
 *
 * Resolution uses its distinct runtime input materializer to reserve symbolic cells and value
 * promises before their producers install them. Correctness replay, including replay of
 * Resolution inputs, and nested `ctx.query` results use [materializeResult] directly.
 */
internal suspend fun ObjectEngineResult.materializeResolverInput(
    operation: SharedOperationContext<*>,
    cycleChecker: CycleCheckState,
    selections: MaterializeSelectionForest,
    reader: CycleTask,
): EngineObjectData.Sync = materializeResult(operation, selections, reader, cycleChecker)
